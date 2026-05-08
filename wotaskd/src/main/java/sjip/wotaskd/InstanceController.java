package sjip.wotaskd;
/*
� Copyright 2006 - 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. (�Apple�) in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple�s copyrights in this original Apple software (the �Apple Software�), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableDictionary;

import sjip.core.IInstanceController;
import sjip.core.MUtil;
import sjip.core.SjipException;
import sjip.core.model.MApplication;
import sjip.core.model.MHost;
import sjip.core.model.MInstance;
import sjip.core.model.MSiteConfig;
import sjip.x.FHosts;
import sjip.x.FProperties;
import sjip.x.FoundationCoder;
import sjip.x.ResponseWrapper;
import sjip.x.XUtil;

public class InstanceController implements IInstanceController {

	private static final Logger logger = LoggerFactory.getLogger( InstanceController.class );

	private static final int FORCE_QUIT_DELAY = FProperties.intValue( FProperties.K.FORCE_QUIT_DELAY );
	private static final int RECEIVE_TIMEOUT = FProperties.intValue( FProperties.K.RECEIVE_TIMEOUT );
	private static final boolean FORCE_QUIT_TASK_ENABLED = FProperties.booleanValue( FProperties.K.FORCE_QUIT_TASK_ENABLED );
	private static final boolean IS_ON_WINDOWS = FProperties.sysProp( "os.name" ).toLowerCase().startsWith( "win" );

	/**
	 * When true, instances are launched in a way that detaches them from wotaskd's process
	 * group, so killing wotaskd does not take the instances with it. Off by default; flip via
	 * the {@code WOTaskd.detachLaunch} system property. The legacy {@code Runtime.exec} path
	 * is preserved unchanged for the off case. Unix-only — has no effect on Windows.
	 */
	private static final boolean DETACH_LAUNCH = FProperties.booleanValue( FProperties.K.DETACH_LAUNCH );

	private final String _hostName;
	private final boolean _shouldUseSpawn;
	private final String spawningGrounds;

	private final ScheduledExecutorService _scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
		final Thread t = new Thread( r, "InstanceController-scheduler" );
		t.setDaemon( true );
		return t;
	} );

	/**
	 * Registry of running app instances that wotaskd <em>didn't</em> start — apps that
	 * showed up via lifebeat (heartbeat) without a corresponding entry in the SiteConfig.
	 *
	 * <h4>How an instance ends up here</h4>
	 * <p>The lifebeat handler accepts pings from any instance running on the local
	 * machine, even ones wotaskd has no record of, and stuffs them in here. That happens
	 * in two scenarios:
	 * <ul>
	 *   <li>An admin started a {@code .woa} from the shell directly instead of through
	 *       JavaMonitor.</li>
	 *   <li>A leftover JVM from a previous SiteConfig is still alive and pinging after
	 *       its instance entry was removed from the config.</li>
	 * </ul>
	 *
	 * <h4>What we do with them</h4>
	 * <ol>
	 *   <li><b>Adaptor routing.</b> {@link #generateAdaptorConfigXML()} emits these as
	 *       {@code <application>}/{@code <instance>} entries (with a negative {@code id}
	 *       sentinel) so the {@code mod_WebObjects} adaptor can route incoming HTTP
	 *       requests to manually-started apps too.</li>
	 *   <li><b>Lookup by name.</b> {@link #portForUnregisteredAppNamed(String)} returns
	 *       any known port for a given app name; called from
	 *       {@code DirectAction#defaultAction} when handling requests for apps that have
	 *       no managed instance.</li>
	 * </ol>
	 *
	 * <h4>Triage</h4>
	 * <p>{@link #triageUnknownInstances()} runs periodically and drops entries whose last
	 * lifebeat is older than 45 seconds — twice the default 30-second lifebeat interval,
	 * so two missed pings count as dead. Keeps the registry from growing without bound.
	 *
	 * <h4>Shape</h4>
	 * <p>Two-level nested map:
	 * {@snippet :
	 * appName (String) -> { port (String) -> lastLifebeat (Instant) }
	 * }
	 * <p>The outer key is the application name (e.g. {@code "Hugi"}), the inner key
	 * is the port the instance is listening on (e.g. {@code "2001"}, kept as a String
	 * since nothing parses it as an int), and the inner value is the timestamp of
	 * the last lifebeat received from that instance. The two-level shape lines up
	 * with the grouped-by-app structure {@link #generateAdaptorConfigXML()} emits.
	 *
	 * <p>FIXME: replace the inner map's value with an {@code UnknownApplication} record
	 * once we get there; the current shape predates records and uses Strings/Instant
	 * directly. // Hugi 2026-04-30
	 *
	 * <p>Concurrent access is gated by {@link #_unknownAppLock}.
	 *
	 * <h4>Is it pulling its weight?</h4>
	 * <p>If your deployment is JavaMonitor-only and nobody starts apps from the shell,
	 * this whole subsystem is dead weight. It only matters when something runs a WO app
	 * outside wotaskd's control.
	 */
	private final NSMutableDictionary _unknownApplications = new NSMutableDictionary();
	private final ReentrantReadWriteLock _unknownAppLock = new ReentrantReadWriteLock();

	/**
	 * Resolves the launch wrapper (if {@code WOShouldUseSpawn} is set), records the local
	 * host name, and schedules the one-shot startup auto-recovery sweep. The repeating
	 * auto-recovery and hourly schedule timers are armed by {@link #_checkAutoRecoverStartup}
	 * after that initial sweep completes.
	 *
	 * <p>The {@code WOShouldUseSpawn} dance looks for a per-platform spawn helper next to
	 * the wotaskd binary ({@code SpawnOfWotaskd.sh} on POSIX, {@code SpawnOfWotaskd.exe} on
	 * Windows) and, if it exists, prepends it to every instance launch command. If the helper
	 * isn't there, spawn mode silently falls back to direct {@code Runtime.exec}. The whole
	 * mechanism predates the {@link #DETACH_LAUNCH} path — see #2 for the long-term direction.
	 */
	public InstanceController() {
		final MSiteConfig aConfig = theApplication().siteConfig();

		final boolean spawnRequested = FProperties.booleanValue( FProperties.K.SHOULD_USE_SPAWN );
		if( spawnRequested ) {
			final String userDir = FProperties.sysProp( "user.dir" );
			final String spawnScript = IS_ON_WINDOWS ? "SpawnOfWotaskd.exe" : "SpawnOfWotaskd.sh";
			final String appDir = Path.of( userDir, "Contents", "Resources", spawnScript ).toString();
			final File theApp = new File( appDir );

			if( theApp.exists() && theApp.isFile() ) {
				_shouldUseSpawn = true;
				spawningGrounds = appDir + " ";
			}
			else {
				_shouldUseSpawn = false;
				spawningGrounds = null;
			}
		}
		else {
			_shouldUseSpawn = false;
			spawningGrounds = null;
		}

		// Used to do phased startup the first time startup
		_scheduler.schedule( this::_checkAutoRecoverStartup, aConfig.autoRecoverInterval(), TimeUnit.MILLISECONDS );

		_hostName = theApplication().host();
	}

	/**
	 * Records or refreshes a lifebeat from an instance that wotaskd doesn't have a
	 * SiteConfig entry for. Called from {@code LifebeatRequestHandler} for any "lifebeat"
	 * notification whose instance name doesn't match a registered {@link MInstance}.
	 *
	 * <p>Only applies to instances on the local machine — a remote address silently no-ops,
	 * since wotaskd has no business tracking foreign hosts' processes.
	 *
	 * <p>Failures (DNS lookup of {@code host}, locking, anything) are swallowed. Unknown
	 * instances are best-effort bookkeeping; losing one is harmless.
	 *
	 * <p>See the {@link #_unknownApplications} field doc for the bigger picture.
	 */
	public void registerUnknownInstance( String name, String host, String port ) {
		_unknownAppLock.writeLock().lock();

		try {
			Instant currentTime = Instant.now();
			// Don't regenerate the localhost list for random applications
			if( FHosts.isLocalInetAddress( InetAddress.getByName( host ), false ) ) {
				NSMutableDictionary appDict = (NSMutableDictionary)_unknownApplications.valueForKey( name );
				if( appDict != null ) {
					appDict.takeValueForKey( currentTime, port );
				}
				else {
					_unknownApplications.takeValueForKey( new NSMutableDictionary( currentTime, port ), name );
				}
			}
		}
		catch( Exception e ) {
			// Just ignore it - unregistered instances are second class citizens anyway
		}
		finally {
			_unknownAppLock.writeLock().unlock();
		}
	}

	/**
	 * Returns *some* port at which an unknown instance of the named app is currently
	 * listening, or {@code null} if no unknown instance with that name has been seen
	 * recently. Picks the first port from the dictionary's iteration order — arbitrary
	 * when multiple unknown instances of the same app are alive, which is uncommon in
	 * practice. Used by {@code DirectAction#defaultAction} to route requests at app names
	 * that have no managed instance but do have a manually-started one lifebeating.
	 */
	public String portForUnregisteredAppNamed( String name ) {
		_unknownAppLock.readLock().lock();

		try {
			NSDictionary appDict = (NSDictionary)_unknownApplications.valueForKey( name );
			if( appDict != null ) {
				List<String> keysArray = appDict.allKeys();
				if( (keysArray != null) && (keysArray.size() > 0) ) {
					return keysArray.get( 0 );
				}
			}
			return null;
		}
		finally {
			_unknownAppLock.readLock().unlock();
		}
	}

	/**
	 * Drops {@link #_unknownApplications} entries whose last lifebeat is older than 45
	 * seconds — twice the default 30-second lifebeat interval, so two missed pings count
	 * as dead. Called from {@link #_checkAutoRecover}'s periodic timer; keeps the registry
	 * from growing without bound when manually-started apps come and go.
	 *
	 * <p>The 45-second cutoff is hardcoded; making it configurable hasn't been worth the
	 * effort given how marginal this whole subsystem is.
	 */
	private void triageUnknownInstances() {
		_unknownAppLock.writeLock().lock();

		try {
			NSMutableDictionary unknownApps = _unknownApplications;
			// Should make this configurable?
			Instant cutOffDate = Instant.now().minusSeconds( 45 );

			List<String> unknownAppKeys = unknownApps.allKeys();

			for( String unknownAppKey : unknownAppKeys ) {
				NSMutableDictionary appDict = (NSMutableDictionary)unknownApps.valueForKey( unknownAppKey );

				if( appDict != null ) {
					List<String> appDictKeys = appDict.allKeys();

					for( String appDictKey : appDictKeys ) {
						Instant lastLifebeat = (Instant)appDict.valueForKey( appDictKey );
						if( (lastLifebeat != null) && (lastLifebeat.isBefore( cutOffDate )) ) {
							appDict.removeObjectForKey( appDictKey );
						}
					}
					if( appDict.count() == 0 ) {
						unknownApps.removeObjectForKey( unknownAppKey );
					}
				}
			}
		}
		finally {
			_unknownAppLock.writeLock().unlock();
		}
	}

	/**
	 * Emits {@code <application>}/{@code <instance>} XML fragments for every unknown
	 * instance currently in {@link #_unknownApplications}, formatted for inclusion in
	 * {@code WOConfig.xml} so the {@code mod_WebObjects} adaptor can route requests to
	 * manually-started apps too. The "config" of the method's name refers to the adaptor's
	 * {@code WOConfig.xml}, not wotaskd's {@code SiteConfig.xml}.
	 *
	 * <p>Despite the generic name, this method only handles the unknown-instance leg —
	 * the registered-instance fragments are emitted separately by {@code MSiteConfig}.
	 *
	 * <p>Each {@code <instance>} gets a sentinel {@code id="-<port>"} (the negative-port
	 * convention is how the adaptor distinguishes unknown from registered), and the host
	 * is always wotaskd's own (an unknown instance is by definition local).
	 */
	@Override
	public String generateAdaptorConfigXML() {
		StringBuilder sb = null;

		_unknownAppLock.readLock().lock();

		try {
			NSMutableDictionary unknownApps = _unknownApplications;
			sb = new StringBuilder();

			if( (unknownApps.count() == 0) ) {
				return sb.toString();
			}

			for( Enumeration e = unknownApps.keyEnumerator(); e.hasMoreElements(); ) {
				String appName = (String)e.nextElement();
				NSMutableDictionary appDict = (NSMutableDictionary)unknownApps.valueForKey( appName );

				sb.append( "  <application name=\"" );
				sb.append( appName );
				sb.append( "\">\n" );

				for( Enumeration e2 = appDict.keyEnumerator(); e2.hasMoreElements(); ) {
					String port = (String)e2.nextElement();
					sb.append( "    <instance" );

					sb.append( " id=\"-" );
					sb.append( port );
					sb.append( "\" port=\"" );
					sb.append( port );
					sb.append( "\" host=\"" );
					sb.append( _hostName );

					sb.append( "\"/>\n" );
				}

				sb.append( "  </application>\n" );
			}
		}
		finally {
			_unknownAppLock.readLock().unlock();
		}

		return sb.toString();
	}

	/********** Timer Targets **********/

	/**
	 * Periodic auto-recovery sweep. For every local instance flagged as auto-recovering
	 * or scheduled, restarts it if it isn't currently running and isn't already in the
	 * STARTING state. Also calls {@link #triageUnknownInstances()} on each pass to expire
	 * stale unknown-app entries.
	 *
	 * <p>Runs at the interval configured by {@code MSiteConfig.autoRecoverInterval}.
	 * Holds the application read lock for the duration; instance starts happen synchronously
	 * inside the lock, which is fine because {@link #startInstance} only kicks off the
	 * launch — it doesn't wait for the instance to come up.
	 */
	private void _checkAutoRecover() {
		logger.debug( "_checkAutoRecover START" );
		theApplication()._lock.readLock().lock();
		try {
			MHost theHost = theApplication().siteConfig().localHost();
			if( theHost != null ) {
				List<MInstance> instArray = theHost.instanceArray();
				int instArrayCount = instArray.size();

				for( int i = 0; i < instArrayCount; i++ ) {
					MInstance anInst = instArray.get( i );

					if( (!anInst.isRunning_W()) && (anInst.state != MUtil.STARTING) &&
							((anInst.isAutoRecovering()) || (anInst.isScheduled())) ) {
						anInst.setRefusingNewSessions( false );
						startInstance( anInst );
					}
				}
			}
			triageUnknownInstances();
		}
		finally {
			theApplication()._lock.readLock().unlock();
		}
		logger.debug( "_checkAutoRecover STOP" );
	}

	/**
	 * One-shot startup auto-recovery, fired once after the initial
	 * {@code autoRecoverInterval} delay. Walks every application in the SiteConfig in
	 * parallel (one thread per app) and inside each thread starts that app's local
	 * instances serially, optionally with a {@code timeForStartup} pause between them
	 * (the per-app "phased startup" knob — staggers heavy-startup apps so they don't
	 * thrash the host on boot).
	 *
	 * <p>After the parallel sweep returns, arms the two repeating timers that drive the
	 * rest of the controller's lifecycle: the auto-recovery sweep ({@link #_checkAutoRecover})
	 * at {@code autoRecoverInterval}, and the schedule sweep ({@link #_checkSchedules})
	 * hourly on the hour.
	 */
	private void _checkAutoRecoverStartup() {
		logger.debug( "_checkAutoRecoverStartup START" );
		theApplication()._lock.readLock().lock();
		try {
			MSiteConfig aConfig = theApplication().siteConfig();
			final List<MApplication> appArray = aConfig.applicationArray();
			int appArrayCount = appArray.size();
			final InstanceController instanceController = this;

			Thread[] workers = new Thread[appArrayCount];

			for( int i = 0; i < workers.length; i++ ) {
				final int j = i;
				Runnable work = new Runnable() {
					@Override
					public void run() {
						instanceController._autoRecoverApplication( appArray.get( j ) );
					}
				};
				workers[j] = new Thread( work );
				workers[j].start();
			}

			try {
				for( Thread worker : workers ) {
					worker.join();
				}
			}
			catch( InterruptedException ie ) {
			}

			// Repeating, hourly, timer for _checkSchedules — fires every hour on the hour
			final Instant now = Instant.now();
			final long delayUntilNextHour = Duration.between( now, now.truncatedTo( ChronoUnit.HOURS ).plus( 1, ChronoUnit.HOURS ) ).toMillis();
			_scheduler.scheduleAtFixedRate( this::_checkSchedules, delayUntilNextHour, TimeUnit.HOURS.toMillis( 1 ), TimeUnit.MILLISECONDS );

			// Regular auto-recovery timer
			_scheduler.scheduleAtFixedRate( this::_checkAutoRecover, aConfig.autoRecoverInterval(), aConfig.autoRecoverInterval(), TimeUnit.MILLISECONDS );

		}
		finally {
			theApplication()._lock.readLock().unlock();
		}
		logger.debug( "_checkAutoRecoverStartup STOP" );
	}

	/**
	 * Worker for {@link #_checkAutoRecoverStartup} — starts every local, auto-recovering
	 * or scheduled instance of the given application that isn't already running. When the
	 * application has phased startup enabled, sleeps {@code timeForStartup} seconds between
	 * instance launches so they don't thunder onto the host all at once.
	 *
	 * <p>Falls back to {@link MInstance#TIME_FOR_STARTUP} if the per-app value isn't set.
	 */
	private void _autoRecoverApplication( MApplication anApplication ) {
		List<MInstance> instArray = anApplication.instanceArray();
		int instArrayCount = instArray.size();

		long timeForStartup;
		Integer tfs = anApplication.timeForStartup();
		if( tfs != null ) {
			timeForStartup = tfs.intValue();
		}
		else {
			timeForStartup = MInstance.TIME_FOR_STARTUP;
		}
		timeForStartup *= 1000;

		boolean phasedStartup = false;
		Boolean pS = anApplication.phasedStartup();
		if( pS != null ) {
			phasedStartup = pS.booleanValue();
		}

		for( int i = 0; i < instArrayCount; i++ ) {
			MInstance anInst = instArray.get( i );

			if( (anInst.isLocal_W()) && (!anInst.isRunning_W()) && (anInst.state != MUtil.STARTING) &&
					((anInst.isAutoRecovering()) || (anInst.isScheduled())) ) {
				anInst.setRefusingNewSessions( false );
				startInstance( anInst );

				if( (phasedStartup) && (i < instArrayCount - 1) ) {
					try {
						Thread.sleep( timeForStartup );
					}
					catch( InterruptedException ie ) {
					}
				} // end phased if
			} // end instance if
		} // end for
	}

	/**
	 * Hourly scheduled-shutdown sweep. For every local instance with a scheduled shutdown
	 * approaching (via {@link MInstance#nearNextScheduledShutdown}), kicks off the right
	 * shutdown shape: {@link #stopInstance} for graceful (REFUSE-based, drains active
	 * sessions) or {@link #terminateInstance} for hard (immediate kill). After dispatching,
	 * {@link MInstance#calculateNextScheduledShutdown} rolls the schedule forward to the
	 * next occurrence so the same instance doesn't keep matching every tick.
	 *
	 * <p>Each instance is dispatched in its own thread; the sweep waits for all of them
	 * before returning. Exceptions from a single instance's shutdown are logged but don't
	 * stop the sweep.
	 *
	 * <p>See #9 for the open question about day-of-week / off-by-one behaviour in
	 * {@code calculateNextScheduledShutdown} after the java.time migration.
	 */
	private void _checkSchedules() {
		logger.debug( "_checkSchedules START" );
		theApplication()._lock.readLock().lock();
		try {

			MHost theHost = theApplication().siteConfig().localHost();
			if( theHost != null ) {
				final List<MInstance> instArray = theHost.instanceArray();
				int instArrayCount = instArray.size();

				if( instArrayCount == 0 ) {
					return;
				}

				final Instant now = Instant.now();
				final Thread[] workers = new Thread[instArrayCount];
				final InstanceController instanceController = this;

				for( int i = 0; i < instArrayCount; i++ ) {
					final int j = i;
					Runnable work = new Runnable() {
						@Override
						public void run() {
							try {
								MInstance anInst = instArray.get( j );
								if( (anInst.isScheduled()) && (anInst.nearNextScheduledShutdown( now )) ) {
									if( anInst.isGracefullyScheduled() ) {
										instanceController.stopInstance( anInst );
									}
									else {
										instanceController.terminateInstance( anInst );
									}
									anInst.calculateNextScheduledShutdown();
								}
							}
							catch( SjipException me ) {
								logger.error( "Exception while scheduling: " + me.getMessage() );
							}
						}
					};
					workers[j] = new Thread( work );
					workers[j].start();
				}

				try {
					for( Thread worker : workers ) {
						worker.join();
					}
				}
				catch( InterruptedException ie ) {
				}

			}
		}
		finally {
			theApplication()._lock.readLock().unlock();
		}
		logger.debug( "_checkSchedules STOP" );
	}

	/********** Controlling Instances **********/

	/**
	 * Launches the given instance. Validates that the instance is local, isn't already
	 * running or starting, that nothing's listening on its port, and that its launch path
	 * exists and is a file. Then either invokes the detached launch path (POSIX-only,
	 * orphans the child to PID 1 — see {@link #startInstanceDetached}) when
	 * {@link #DETACH_LAUNCH} is enabled, or falls back to {@code Runtime.exec} for the
	 * legacy attached-process behaviour.
	 *
	 * <p>Fire-and-forget: success means "the launch was kicked off without immediate
	 * error," not "the instance is up." Liveness is observed asynchronously via lifebeats.
	 *
	 * @return {@code null} on success, or a human-readable error message describing why
	 *         the launch couldn't be attempted (instance is null, isn't local, port is
	 *         already taken, path doesn't exist, etc.). Errors during the actual
	 *         {@code exec} call are also returned in this form.
	 */
	@Override
	public String startInstance( MInstance anInstance ) {
		MSiteConfig aConfig = theApplication().siteConfig();

		if( anInstance == null ) {
			return "Attempt to start null instance on " + _hostName;
		}

		if( anInstance.host() != aConfig.localHost() ) {
			return anInstance.displayName() + " does not exist on " + _hostName + "; START instance failed";
		}

		if( anInstance.isRunning_W() || (anInstance.state == MUtil.STARTING) ) {
			//            return _hostName + ": " + anInstance.displayName() + " is currently starting";
			return null;
		}

		if( _testConnection( anInstance ) ) {
			return _hostName + ": " + anInstance.displayName() + " cannot be started because port " + anInstance.port() + " is still in use";
		}

		String aFullPath = anInstance.path();

		if( aFullPath == null ) {
			return _hostName + ": Path for " + anInstance.displayName() + " does not exist";
		}

		aFullPath = anInstance.path().trim();
		String arguments = anInstance.commandLineArguments();
		String aLaunchPath = aFullPath + " " + arguments;

		anInstance.willAttemptToStart();

		File aFile = new File( aFullPath );

		if( !aFile.exists() ) {
			return _hostName + ": Path '" + aFullPath + "' for " + anInstance.displayName() + " does not exist";
		}

		if( !aFile.isFile() ) {
			return _hostName + ": Path '" + aFullPath + "' for " + anInstance.displayName() + " is not a file";
		}

		if( _shouldUseSpawn ) {
			if( IS_ON_WINDOWS ) {
				aLaunchPath = spawningGrounds + aLaunchPath;
			}
			else {
				aLaunchPath = spawningGrounds + aLaunchPath;
			}
		}

		try {
			logger.debug( "Starting Instance: " + aLaunchPath );

			if( DETACH_LAUNCH && !IS_ON_WINDOWS ) {
				logger.info( "starting instance {}:{} in detached mode", anInstance.applicationName(), anInstance.port() );
				startInstanceDetached( aFullPath, anInstance.commandLineArgumentsAsArray() );
			}
			else {
				logger.info( "starting instance {}:{} in normal mode (attached to the wotaskd process)", anInstance.applicationName(), anInstance.port() );
				Runtime.getRuntime().exec( aLaunchPath );
			}
		}
		catch( IOException ioe ) {
			logger.error( "Failed to start " + anInstance.displayName() + ": " + ioe );
			return _hostName + ": Failed to start " + anInstance.displayName() + ": " + ioe;
		}

		return null;
	}

	/**
	 * Launches the instance in a way that detaches it from wotaskd's process group, so
	 * killing wotaskd does not take the instance with it. POSIX-only.
	 *
	 * <p>Runs {@code /bin/sh -c "exec <cmd> </dev/null >/dev/null 2>&1 &"}: the trailing
	 * {@code &} backgrounds the child within the shell, then the shell exits, and the
	 * orphaned child is reparented to PID 1 (init). The WOApp itself redirects its own
	 * stdout/stderr to the configured {@code -WOOutputPath} during startup, so anything
	 * written between fork and that redirection is discarded by the {@code /dev/null}
	 * setup here — same observable behaviour as the legacy {@code Runtime.exec} path.
	 *
	 * <p>The {@code Process} returned by the shell is waited on briefly; once {@code sh}
	 * exits (which is immediate), wotaskd has no further handle on the actual instance.
	 *
	 * @param launchPath path to the WOApp executable
	 * @param args command-line arguments (already-typed; no shell quoting headaches needed
	 *             for individual element values, but the assembled command line is shell-quoted
	 *             before being handed to {@code sh -c}).
	 */
	private static void startInstanceDetached( final String launchPath, final List<String> args ) throws IOException {
		final StringBuilder shellCmd = new StringBuilder( "exec " ).append( shellQuote( launchPath ) );
		for( final String arg : args ) {
			shellCmd.append( ' ' ).append( shellQuote( arg ) );
		}
		shellCmd.append( " </dev/null >/dev/null 2>&1 &" );

		final ProcessBuilder pb = new ProcessBuilder( "/bin/sh", "-c", shellCmd.toString() );
		final Process p = pb.start();
		try {
			p.waitFor();
		}
		catch( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * POSIX-quote a single argument so {@code /bin/sh -c} reads it as one token. Wraps the
	 * value in single quotes and escapes any embedded single quotes by closing-and-reopening
	 * the quoted string ({@code 'foo'\''bar'}). Safe for arbitrary content including spaces,
	 * newlines, and shell metacharacters.
	 */
	private static String shellQuote( final String s ) {
		return "'" + s.replace( "'", "'\\''" ) + "'";
	}

	/**
	 * Hard shutdown: sends {@code TERMINATE} to the instance's admin endpoint, expecting
	 * the instance to exit immediately. No-op if the instance isn't currently running.
	 *
	 * <p>If {@link #FORCE_QUIT_TASK_ENABLED} is on and the instance doesn't actually exit,
	 * a follow-up {@link MInstanceTask.ForceQuit} fires after {@link #FORCE_QUIT_DELAY}ms
	 * to do something more aggressive. The minimum allowed delay is 60 seconds; sub-minute
	 * values are logged as errors and the task is skipped.
	 *
	 * @return the instance's response wrapper, or {@code null} if the instance wasn't
	 *         running (no-op case).
	 * @throws SjipException if validation fails (null instance, non-local, not running)
	 *                       or the HTTP call fails.
	 * @see #stopInstance for the graceful (REFUSE-based) alternative.
	 */
	@Override
	public ResponseWrapper terminateInstance( MInstance anInstance ) throws SjipException {
		if( !anInstance.isRunning_W() ) {
			return null;
		}

		//if WOTaskd.forceQuitTaskEnabled is true, setup a task to check
		//the instance, if it still doesn't die, then force a QUIT command when
		//the timer elapses, minimum is 60 seconds, default 120 seconds
		if( FORCE_QUIT_TASK_ENABLED ) {
			if( FORCE_QUIT_DELAY >= 60000 ) {
				anInstance.scheduleForceQuit( new MInstanceTask.ForceQuit( anInstance ), FORCE_QUIT_DELAY );
			}
			else {
				logger.error( "WOtaskd.killTimeout: " + FORCE_QUIT_DELAY + " is too small. 60000 milliseconds is the minimum" );
			}
		}

		catchInstanceErrors( anInstance );
		final Map<String,Object> xmlDict = createInstanceRequestDictionary( "TERMINATE", null, anInstance );
		return sendInstanceRequest( _hostName, anInstance, xmlDict );
	}

	/**
	 * Graceful shutdown: sends {@code REFUSE} to the instance, telling it to stop
	 * accepting new sessions and exit when its active-session count drops to the
	 * configured minimum. No-op if the instance isn't currently running.
	 *
	 * <p>If {@link #FORCE_QUIT_TASK_ENABLED} is on, schedules a periodic
	 * {@link MInstanceTask.Refuse} watcher that retries up to {@code WOTaskd.refuseNumRetries}
	 * times (default 3) before force-quitting an instance that still hasn't exited. An
	 * intervening {@link #setAcceptInstance ACCEPT} cancels the watcher.
	 *
	 * <p>Caller-facing method name: this is the "graceful stop" verb. The wire-level command
	 * is {@code REFUSE}, which is why this method maps to that string in the request dictionary
	 * — the protocol vocabulary doesn't quite match the local API vocabulary.
	 *
	 * @return the instance's response wrapper, or {@code null} if the instance wasn't
	 *         running.
	 * @throws SjipException on validation or HTTP failure.
	 * @see #terminateInstance for the immediate-kill alternative.
	 */
	@Override
	public ResponseWrapper stopInstance( MInstance anInstance ) throws SjipException {
		if( !anInstance.isRunning_W() ) {
			return null;
		}

		//if WOTaskd.forceQuitTaskEnabled is true, setup a task to check the instance, this will retry WOTaskd.refuseNumRetries times
		//the timer elapses minimum is 60 seconds, default 3600 seconds (the default session timeout)
		//a force quit if WOTaskd.refuseNumRetries is reached and the instance is still alive
		//an ACCEPT will cancel the monitoring
		if( FORCE_QUIT_TASK_ENABLED ) {
			if( FORCE_QUIT_DELAY >= 60000 ) {
				anInstance.scheduleRefuseTask( new MInstanceTask.Refuse( anInstance, FProperties.intValue( FProperties.K.REFUSE_NUM_RETRIES ) ), FORCE_QUIT_DELAY, FORCE_QUIT_DELAY );
			}
			else {
				logger.error( "WOtaskd.killTimeout: " + FORCE_QUIT_DELAY + " is too small. 60000 milliseconds is the minimum" );
			}
		}

		catchInstanceErrors( anInstance );
		final Map<String,Object> xmlDict = createInstanceRequestDictionary( "REFUSE", null, anInstance );
		return sendInstanceRequest( _hostName, anInstance, xmlDict );
	}

	/**
	 * Reverses a prior {@link #stopInstance REFUSE}: tells the instance to resume
	 * accepting new sessions. Cancels any pending refuse-watcher task on the instance.
	 *
	 * @throws SjipException on validation or HTTP failure.
	 */
	public ResponseWrapper setAcceptInstance( MInstance anInstance ) throws SjipException {
		catchInstanceErrors( anInstance );
		final Map<String,Object> xmlDict = createInstanceRequestDictionary( "ACCEPT", null, anInstance );
		return sendInstanceRequest( _hostName, anInstance, xmlDict );
	}

	/**
	 * Sends a {@code STATISTICS} query to the instance's admin endpoint and returns its
	 * response. The response body is an ASCII property-list of the instance's
	 * {@code WOStatisticsStore.statistics()} dictionary; see the survey on
	 * {@code wonder-slim-deployment#22} for the actual contents (and what JavaMonitor
	 * does with them today, which is "almost nothing").
	 *
	 * @throws SjipException on validation or HTTP failure.
	 */
	@Override
	public ResponseWrapper queryInstance( MInstance anInstance ) throws SjipException {
		catchInstanceErrors( anInstance );
		final Map<String,Object> xmlDict = createInstanceRequestDictionary( null, "STATISTICS", anInstance );
		return sendInstanceRequest( _hostName, anInstance, xmlDict );
	}

	/**
	 * Common preflight for the four command/query methods ({@link #terminateInstance},
	 * {@link #stopInstance}, {@link #setAcceptInstance}, {@link #queryInstance}). Throws
	 * with a useful message if the target is null, on a different host, or not currently
	 * running. Exists to keep those four methods short and consistent rather than repeating
	 * the same three checks inline.
	 *
	 * <p>The method name is "catch errors" but it actually throws them — historical naming.
	 */
	private void catchInstanceErrors( MInstance anInstance ) throws SjipException {

		if( anInstance == null ) {
			throw new SjipException( "Attempt to command null instance on " + _hostName );
		}

		if( anInstance.host() != theApplication().siteConfig().localHost() ) {
			throw new SjipException( anInstance.displayName() + " does not exist on " + _hostName + "; command failed" );
		}

		if( !anInstance.isRunning_W() ) {
			throw new SjipException( _hostName + ": " + anInstance.displayName() + " is not running" );
		}
	}

	/**
	 * Encodes the request dictionary as XML, POSTs it to the instance's
	 * {@code /cgi-bin/WebObjects/<App>.woa/womp/instanceRequest} endpoint, and wraps the
	 * response. Updates the instance's connection bookkeeping
	 * ({@link MInstance#succeededInConnection} / {@link MInstance#failedToConnect}) based
	 * on the outcome.
	 *
	 * <p>Distinguishes {@code HttpTimeoutException} (different error message: "Timeout
	 * while connecting") from other failures so operators can tell stuck-instance from
	 * unreachable-instance at a glance. Both still increment the instance's failure counter.
	 *
	 * @throws SjipException on timeout or any other communication failure. The original
	 *                       exception is included in the message but not chained as a
	 *                       cause, since {@code SjipException} doesn't currently expose a
	 *                       chained-cause constructor.
	 */
	private static ResponseWrapper sendInstanceRequest( final String hostName, final MInstance anInstance, final Map<String,Object> xmlDict ) throws SjipException {

		final String requestContentXML = new FoundationCoder().encodeRootObjectForKey( xmlDict, "instanceRequest" );
		final String urlString = MUtil.ADMIN_ACTION_STRING_PREFIX + anInstance.applicationName() + MUtil.ADMIN_ACTION_STRING_POSTFIX;

		try {
			final Builder requestBuilder = HttpRequest
					.newBuilder()
					.uri( URI.create( "http://%s:%s%s".formatted( anInstance.host().name(), anInstance.port(), urlString ) ) )
					.timeout( Duration.ofMillis( RECEIVE_TIMEOUT ) )
					.POST( BodyPublishers.ofString( requestContentXML ) );

			final HttpRequest request = requestBuilder.build();

			final HttpResponse<String> response = XUtil.sendRequest( request );
			final ResponseWrapper responseWrapper = new ResponseWrapper( response.body(), response.headers() );

			anInstance.succeededInConnection();
			return responseWrapper;
		}
		catch( java.net.http.HttpTimeoutException te ) {
			anInstance.failedToConnect();
			throw new SjipException( hostName + ": Timeout while connecting to " + anInstance.displayName() );
		}
		catch( Exception e ) {
			anInstance.failedToConnect();
			throw new SjipException( hostName + ": Error while communicating with " + anInstance.displayName() + ": " + e );
		}
	}

	/**
	 * Builds the request body wotaskd sends to a running instance's
	 * {@code /cgi-bin/WebObjects/<App>.woa/womp/instanceRequest} endpoint.
	 *
	 * <p>Exactly one of {@code commandString} and {@code queryString} should be non-null.
	 * Passing both produces a hybrid request with a {@code commandInstance} <em>and</em>
	 * a {@code queryInstance} entry; the instance only acts on whichever it sees first,
	 * so the dual form isn't useful in practice.
	 *
	 * <h4>Command form ({@code commandString} non-null)</h4>
	 * <p>Wraps the command in a nested dictionary under the {@code commandInstance} key:
	 * {@snippet :
	 * {
	 *   "commandInstance": {
	 *     "command": <commandString>,
	 *     // when commandString is "REFUSE", also:
	 *     "minimumActiveSessionsCount": <int>
	 *   }
	 * }
	 * }
	 * <p>Recognised commands: {@code TERMINATE} (immediate kill), {@code REFUSE} (graceful
	 * shutdown — stop accepting new sessions, exit when the active-session count drops to
	 * the configured minimum), {@code ACCEPT} (resume taking new sessions, cancelling a
	 * prior REFUSE).
	 *
	 * <h4>Query form ({@code queryString} non-null)</h4>
	 * <p>Stores the query verb directly under {@code queryInstance}:
	 * {@snippet :
	 * {
	 *   "queryInstance": <queryString>
	 * }
	 * }
	 * <p>The only query verb in use is {@code STATISTICS}, which asks the instance to
	 * return its runtime statistics.
	 *
	 * @param commandString {@code TERMINATE} / {@code REFUSE} / {@code ACCEPT}, or
	 *                      {@code null} to skip the command leg.
	 * @param queryString   {@code STATISTICS}, or {@code null} to skip the query leg.
	 * @param anInstance    the target instance; only consulted when {@code commandString}
	 *                      is {@code "REFUSE"}, to embed its
	 *                      {@code minimumActiveSessionsCount}.
	 * @return a fresh dictionary; the caller hands it to
	 *         {@link FoundationCoder#encodeRootObjectForKey} under the root tag
	 *         {@code "instanceRequest"} before posting.
	 */
	private Map<String,Object> createInstanceRequestDictionary( final String commandString, final String queryString, final MInstance anInstance ) {
		final Map<String,Object> instanceRequest = new HashMap<>( 2 );

		if( commandString != null ) {
			final Map<String,Object> commandInstance = new HashMap<>( 2 );

			commandInstance.put( "command", commandString );

			if( commandString.equals( "REFUSE" ) ) {
				commandInstance.put( "minimumActiveSessionsCount", anInstance.minimumActiveSessionsCount() );
			}

			instanceRequest.put( "commandInstance", commandInstance );
		}

		if( queryString != null ) {
			instanceRequest.put( "queryInstance", queryString );
		}

		return instanceRequest;
	}

	/**
	 * Probes whether something is already listening on the instance's port. Returns
	 * {@code true} if a TCP connect succeeds within 1 second — meaning the port is taken
	 * and {@link #startInstance} should refuse to launch. {@code false} on connection
	 * refused, timeout, or any other I/O error — port is available.
	 *
	 * <p>Used as a "don't start an instance over an existing one" guard. It can't tell
	 * whether the listener is the right app or some unrelated service that happens to
	 * have grabbed the port; either way, {@code startInstance} declines.
	 */
	private boolean _testConnection( MInstance anInstance ) {
		try( Socket aSocket = new Socket() ) {
			aSocket.connect( new InetSocketAddress( anInstance.host().name(), anInstance.port() ), 1000 );
			return true;
		}
		catch( IOException e ) {
			return false;
		}
	}

	private static Application theApplication() {
		return (Application)WOApplication.application();
	}
}