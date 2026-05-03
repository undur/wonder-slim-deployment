package com.webobjects.monitor.wotaskd;
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
import java.net.http.HttpResponse.BodyHandlers;
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
import com.webobjects.appserver._private.WOHostUtilities;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.monitor._private.IInstanceController;
import com.webobjects.monitor._private.MUtil;
import com.webobjects.monitor._private.MonitorException;
import com.webobjects.monitor._private.StringExtensions;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MHost;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor._private.model.MSiteConfig;

import er.extensions.foundation.ERXProperties;
import x.FoundationCoder;
import x.ResponseWrapper;
import x.XUtil;

public class InstanceController implements IInstanceController {

	private final ScheduledExecutorService _scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
		final Thread t = new Thread( r, "InstanceController-scheduler" );
		t.setDaemon( true );
		return t;
	} );
	private final String _hostName;
	private boolean _isOnWindows = false;
	private boolean _shouldUseSpawn = true;
	private String spawningGrounds = null;
	private final Application theApplication = (Application)WOApplication.application();

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
	 * <pre>
	 * appName (String) -> { port (String) -> lastLifebeat (Instant) }
	 * </pre>
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

	private static final int FORCE_QUIT_DELAY = ERXProperties.intForKeyWithDefault( "WOTaskd.killTimeout", 120000 );
	private static final int RECEIVE_TIMEOUT = ERXProperties.intForKeyWithDefault( "WOTaskd.receiveTimeout", 5000 );
	private static final boolean FORCE_QUIT_TASK_ENABLED = ERXProperties.booleanForKeyWithDefault( "WOTaskd.forceQuitTaskEnabled", false );

	/**
	 * When true, instances are launched in a way that detaches them from wotaskd's process
	 * group, so killing wotaskd does not take the instances with it. Off by default; flip via
	 * the {@code WOTaskd.detachLaunch} system property. The legacy {@code Runtime.exec} path
	 * is preserved unchanged for the off case. Unix-only — has no effect on Windows.
	 */
	private static final boolean DETACH_LAUNCH = ERXProperties.booleanForKeyWithDefault( "WOTaskd.detachLaunch", false );

	public InstanceController() {
		MSiteConfig aConfig = theApplication.siteConfig();

		if( System.getProperties().getProperty( "os.name" ).toLowerCase().startsWith( "win" ) ) {
			_isOnWindows = true;
		}
		_shouldUseSpawn = StringExtensions.boolValue( System.getProperty( "WOShouldUseSpawn" ) );
		if( _shouldUseSpawn ) {
			final String userDir = System.getProperties().getProperty( "user.dir" );
			final String spawnScript = _isOnWindows ? "SpawnOfWotaskd.exe" : "SpawnOfWotaskd.sh";
			final String appDir = Path.of( userDir, "Contents", "Resources", spawnScript ).toString();

			spawningGrounds = appDir + " ";

			File theApp = new File( appDir );

			if( !(theApp.exists() && theApp.isFile()) ) {
				_shouldUseSpawn = false;
			}
		}

		// Used to do phased startup the first time startup
		_scheduler.schedule( this::_checkAutoRecoverStartup, aConfig.autoRecoverInterval(), TimeUnit.MILLISECONDS );

		_hostName = theApplication.host();
	}

	public void registerUnknownInstance( String name, String host, String port ) {
		_unknownAppLock.writeLock().lock();

		try {
			Instant currentTime = Instant.now();
			// Don't regenerate the localhost list for random applications
			if( WOHostUtilities.isLocalInetAddress( InetAddress.getByName( host ), false ) ) {
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

	public String portForUnregisteredAppNamed( String name ) {
		_unknownAppLock.readLock().lock();

		try {
			NSDictionary appDict = (NSDictionary)_unknownApplications.valueForKey( name );
			if( appDict != null ) {
				List<String> keysArray = appDict.allKeys();
				if( (keysArray != null) && (keysArray.size() > 0) ) {
					return (String)keysArray.get( 0 );
				}
			}
			return null;
		}
		finally {
			_unknownAppLock.readLock().unlock();
		}
	}

	public void triageUnknownInstances() {
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

	// this actually only returns unregistered applications
	@Override
	public StringBuffer generateAdaptorConfigXML() {
		StringBuffer sb = null;

		_unknownAppLock.readLock().lock();
		try {
			NSMutableDictionary unknownApps = _unknownApplications;
			sb = new StringBuffer();

			if( (unknownApps.count() == 0) ) {
				// the read lock is released in the finally block
				return sb;
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
				} // end Instance Enumeration

				sb.append( "  </application>\n" );
			} // end Application Enumeration
		}
		finally {
			_unknownAppLock.readLock().unlock();
		}
		return sb;
	}

	/********** Timer Targets **********/
	public void _checkAutoRecover() {
		logger.debug( "_checkAutoRecover START" );
		theApplication._lock.readLock().lock();
		try {
			MHost theHost = theApplication.siteConfig().localHost();
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
			theApplication._lock.readLock().unlock();
		}
		logger.debug( "_checkAutoRecover STOP" );
	}

	// This only runs once, on startup - then it starts the regular timer
	public void _checkAutoRecoverStartup() {
		logger.debug( "_checkAutoRecoverStartup START" );
		theApplication._lock.readLock().lock();
		try {
			MSiteConfig aConfig = theApplication.siteConfig();
			final List<MApplication> appArray = aConfig.applicationArray();
			int appArrayCount = appArray.size();
			final InstanceController localMonitor = this;

			Thread[] workers = new Thread[appArrayCount];

			for( int i = 0; i < workers.length; i++ ) {
				final int j = i;
				Runnable work = new Runnable() {
					public void run() {
						localMonitor._autoRecoverApplication( (MApplication)appArray.get( j ) );
					}
				};
				workers[j] = new Thread( work );
				workers[j].start();
			}

			try {
				for( int i = 0; i < workers.length; i++ ) {
					workers[i].join();
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
			theApplication._lock.readLock().unlock();
		}
		logger.debug( "_checkAutoRecoverStartup STOP" );
	}

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

	public void _checkSchedules() {
		logger.debug( "_checkSchedules START" );
		theApplication._lock.readLock().lock();
		try {

			MHost theHost = theApplication.siteConfig().localHost();
			if( theHost != null ) {
				final List<MInstance> instArray = theHost.instanceArray();
				int instArrayCount = instArray.size();

				if( instArrayCount == 0 )
					return;

				final Instant now = Instant.now();
				final Thread[] workers = new Thread[instArrayCount];
				final InstanceController localMonitor = this;

				for( int i = 0; i < instArrayCount; i++ ) {
					final int j = i;
					Runnable work = new Runnable() {
						public void run() {
							try {
								MInstance anInst = (MInstance)instArray.get( j );
								if( (anInst.isScheduled()) && (anInst.nearNextScheduledShutdown( now )) ) {
									if( anInst.isGracefullyScheduled() ) {
										localMonitor.stopInstance( anInst );
									}
									else {
										localMonitor.terminateInstance( anInst );
									}
									anInst.calculateNextScheduledShutdown();
								}
							}
							catch( MonitorException me ) {
								logger.error( "Exception while scheduling: " + me.getMessage() );
							}
						}
					};
					workers[j] = new Thread( work );
					workers[j].start();
				}

				try {
					for( int i = 0; i < workers.length; i++ ) {
						workers[i].join();
					}
				}
				catch( InterruptedException ie ) {
				}

			}
		}
		finally {
			theApplication._lock.readLock().unlock();
		}
		logger.debug( "_checkSchedules STOP" );
	}

	/********** Controlling Instances **********/
	// Returns null if success
	@Override
	public String startInstance( MInstance anInstance ) {
		MSiteConfig aConfig = theApplication.siteConfig();
		if( anInstance == null )
			return "Attempt to start null instance on " + _hostName;
		if( anInstance.host() != aConfig.localHost() )
			return anInstance.displayName() + " does not exist on " + _hostName + "; START instance failed";
		if( anInstance.isRunning_W() )
			//            return _hostName + ": " + anInstance.displayName() + " is already running";
			return null;
		if( anInstance.state == MUtil.STARTING )
			//            return _hostName + ": " + anInstance.displayName() + " is currently starting";
			return null;
		if( _testConnection( anInstance ) )
			return _hostName + ": " + anInstance.displayName() + " cannot be started because port " + anInstance.port() + " is still in use";

		String aFullPath = anInstance.path();

		if( aFullPath == null )
			return _hostName + ": Path for " + anInstance.displayName() + " does not exist";

		aFullPath = anInstance.path().trim();
		String arguments = anInstance.commandLineArguments();
		String aLaunchPath = aFullPath + " " + arguments;

		anInstance.willAttemptToStart();

		File aFile = new File( aFullPath );

		if( !aFile.exists() )
			return _hostName + ": Path '" + aFullPath + "' for " + anInstance.displayName() + " does not exist";
		if( !aFile.isFile() )
			return _hostName + ": Path '" + aFullPath + "' for " + anInstance.displayName() + " is not a file";

		if( _shouldUseSpawn ) {
			if( _isOnWindows ) {
				aLaunchPath = spawningGrounds + aLaunchPath;
			}
			else {
				aLaunchPath = spawningGrounds + aLaunchPath;
			}
		}

		try {
			logger.debug( "Starting Instance: " + aLaunchPath );
			if( DETACH_LAUNCH && !_isOnWindows ) {
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

	@Override
	public ResponseWrapper terminateInstance( MInstance anInstance ) throws MonitorException {
		if( !anInstance.isRunning_W() )
			return null;

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

	@Override
	public ResponseWrapper stopInstance( MInstance anInstance ) throws MonitorException {
		if( !anInstance.isRunning_W() )
			return null;

		//if WOTaskd.forceQuitTaskEnabled is true, setup a task to check the instance, this will retry WOTaskd.refuseNumRetries times
		//the timer elapses minimum is 60 seconds, default 3600 seconds (the default session timeout)
		//a force quit if WOTaskd.refuseNumRetries is reached and the instance is still alive
		//an ACCEPT will cancel the monitoring
		if( FORCE_QUIT_TASK_ENABLED ) {
			if( FORCE_QUIT_DELAY >= 60000 ) {
				anInstance.scheduleRefuseTask( new MInstanceTask.Refuse( anInstance, ERXProperties.intForKeyWithDefault( "WOTaskd.refuseNumRetries", 3 ) ), FORCE_QUIT_DELAY, FORCE_QUIT_DELAY );
			}
			else {
				logger.error( "WOtaskd.killTimeout: " + FORCE_QUIT_DELAY + " is too small. 60000 milliseconds is the minimum" );
			}
		}

		catchInstanceErrors( anInstance );
		final Map<String,Object> xmlDict = createInstanceRequestDictionary( "REFUSE", null, anInstance );
		return sendInstanceRequest( _hostName, anInstance, xmlDict );
	}

	public ResponseWrapper setAcceptInstance( MInstance anInstance ) throws MonitorException {
		catchInstanceErrors( anInstance );
		final Map<String,Object> xmlDict = createInstanceRequestDictionary( "ACCEPT", null, anInstance );
		return sendInstanceRequest( _hostName, anInstance, xmlDict );
	}

	@Override
	public ResponseWrapper queryInstance( MInstance anInstance ) throws MonitorException {
		catchInstanceErrors( anInstance );
		final Map<String,Object> xmlDict = createInstanceRequestDictionary( null, "STATISTICS", anInstance );
		return sendInstanceRequest( _hostName, anInstance, xmlDict );
	}

	private void catchInstanceErrors( MInstance anInstance ) throws MonitorException {
		MSiteConfig aConfig = theApplication.siteConfig();
		if( anInstance == null )
			throw new MonitorException( "Attempt to command null instance on " + _hostName );
		if( anInstance.host() != aConfig.localHost() )
			throw new MonitorException( anInstance.displayName() + " does not exist on " + _hostName + "; command failed" );
		if( !anInstance.isRunning_W() )
			throw new MonitorException( _hostName + ": " + anInstance.displayName() + " is not running" );
	}

	private static final Logger logger = LoggerFactory.getLogger( InstanceController.class );

	private static ResponseWrapper sendInstanceRequest( final String hostName, final MInstance anInstance, final Map<String,Object> xmlDict ) throws MonitorException {

		final String requestContentXML = new FoundationCoder().encodeRootObjectForKey( xmlDict, "instanceRequest" );
		final String urlString = MUtil.ADMIN_ACTION_STRING_PREFIX + anInstance.applicationName() + MUtil.ADMIN_ACTION_STRING_POSTFIX;

		try {
			final Builder requestBuilder = HttpRequest
					.newBuilder()
					.uri( URI.create( "http://%s:%s%s".formatted( anInstance.host().name(), anInstance.port(), urlString ) ) )
					.timeout( Duration.ofMillis( RECEIVE_TIMEOUT ) )
					.POST( BodyPublishers.ofString( requestContentXML ) );

			final HttpRequest request = requestBuilder.build();

			logger.info( "--> Sending request: =======" );
			logger.info( "{}", request );
			logger.info( requestContentXML );

			final HttpResponse<byte[]> response = XUtil.HTTP_CLIENT.send( request, BodyHandlers.ofByteArray() );
			logger.info( "--> Response received =======" );
			final ResponseWrapper responseWrapper = new ResponseWrapper( response.body(), response.headers() );
			logger.info( "--> End request phase =======" );
			
			anInstance.succeededInConnection();
			return responseWrapper;
		}
		catch( java.net.http.HttpTimeoutException te ) {
			anInstance.failedToConnect();
			throw new MonitorException( hostName + ": Timeout while connecting to " + anInstance.displayName() );
		}
		catch( Exception e ) {
			anInstance.failedToConnect();
			throw new MonitorException( hostName + ": Error while communicating with " + anInstance.displayName() + ": " + e );
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
	 * <pre>
	 * {
	 *   "commandInstance": {
	 *     "command": &lt;commandString&gt;,
	 *     // when commandString is "REFUSE", also:
	 *     "minimumActiveSessionsCount": &lt;int&gt;
	 *   }
	 * }
	 * </pre>
	 * <p>Recognised commands: {@code TERMINATE} (immediate kill), {@code REFUSE} (graceful
	 * shutdown — stop accepting new sessions, exit when the active-session count drops to
	 * the configured minimum), {@code ACCEPT} (resume taking new sessions, cancelling a
	 * prior REFUSE).
	 *
	 * <h4>Query form ({@code queryString} non-null)</h4>
	 * <p>Stores the query verb directly under {@code queryInstance}:
	 * <pre>
	 * {
	 *   "queryInstance": &lt;queryString&gt;
	 * }
	 * </pre>
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

	private boolean _testConnection( MInstance anInstance ) {
		try( Socket aSocket = new Socket() ) {
			aSocket.connect( new InetSocketAddress( anInstance.host().name(), anInstance.port() ), 1000 );
			return true;
		}
		catch( IOException e ) {
			return false;
		}
	}
}