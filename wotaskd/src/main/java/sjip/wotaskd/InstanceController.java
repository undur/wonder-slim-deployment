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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sjip.core.IInstanceController;
import sjip.core.MUtil;
import sjip.core.SjipException;
import sjip.core.model.MApplication;
import sjip.core.model.MHost;
import sjip.core.model.MInstance;
import sjip.core.model.MSiteConfig;
import sjip.core.x.FProperties;
import sjip.core.x.FoundationCoder;
import sjip.core.x.ResponseWrapper;
import sjip.core.x.XUtil;

public class InstanceController implements IInstanceController {

	private static final Logger logger = LoggerFactory.getLogger( InstanceController.class );

	private static final int FORCE_QUIT_DELAY = FProperties.K.FORCE_QUIT_DELAY.value();
	private static final int RECEIVE_TIMEOUT = FProperties.K.RECEIVE_TIMEOUT.value();
	private static final boolean FORCE_QUIT_TASK_ENABLED = FProperties.K.FORCE_QUIT_TASK_ENABLED.value();
	private static final boolean IS_ON_WINDOWS = FProperties.sysProp( "os.name" ).toLowerCase().startsWith( "win" );

	/**
	 * When true, instances are launched in a way that detaches them from wotaskd's process
	 * group, so killing wotaskd does not take the instances with it. Off by default; flip via
	 * the {@code WOTaskd.detachLaunch} system property. The legacy {@code Runtime.exec} path
	 * is preserved unchanged for the off case. Unix-only — has no effect on Windows.
	 */
	private static final boolean DETACH_LAUNCH = FProperties.K.DETACH_LAUNCH.value();

	private final AppTaskd _appTaskd;

	private final String _hostName;
	private final boolean _shouldUseSpawn;
	private final String spawningGrounds;

	private final ScheduledExecutorService _scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
		final Thread t = new Thread( r, "InstanceController-scheduler" );
		t.setDaemon( true );
		return t;
	} );

	/**
	 * Registry of running app instances that wotaskd <em>didn't</em> start (manual launches,
	 * leftover JVMs from removed SiteConfig entries). Maintained for adaptor routing of
	 * unknown apps; see {@link UnknownInstanceRegistry} for the full story.
	 */
	private final UnknownInstanceRegistry _unknownRegistry;

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
	public InstanceController( final String hostName, final AppTaskd appTaskd ) {
		_appTaskd = appTaskd;
		final MSiteConfig aConfig = appSiteConfig();

		final boolean spawnRequested = FProperties.K.SHOULD_USE_SPAWN.value();
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

		_hostName = hostName;
		_unknownRegistry = new UnknownInstanceRegistry( hostName );

		// Used to do phased startup the first time startup
		_scheduler.schedule( this::_checkAutoRecoverStartup, aConfig.autoRecoverInterval(), TimeUnit.MILLISECONDS );
	}

	public void registerUnknownInstance( String name, String host, String port ) {
		_unknownRegistry.register( name, host, port );
	}

	@Override
	public String generateAdaptorConfigXML() {
		return _unknownRegistry.generateAdaptorConfigXML();
	}

	/********** Timer Targets **********/

	/**
	 * Periodic auto-recovery sweep. For every local instance flagged as auto-recovering
	 * or scheduled, restarts it if it isn't currently running and isn't already in the
	 * STARTING state. Also triages {@link #_unknownRegistry} on each pass to expire stale
	 * unknown-app entries.
	 *
	 * <p>Runs at the interval configured by {@code MSiteConfig.autoRecoverInterval}.
	 * Holds the application read lock for the duration; instance starts happen synchronously
	 * inside the lock, which is fine because {@link #startInstance} only kicks off the
	 * launch — it doesn't wait for the instance to come up.
	 */
	private void _checkAutoRecover() {
		logger.debug( "_checkAutoRecover START" );
		appLock().readLock().lock();
		try {
			MHost theHost = appSiteConfig().localHost();
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
			_unknownRegistry.triage();
		}
		finally {
			appLock().readLock().unlock();
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
		appLock().readLock().lock();
		try {
			MSiteConfig aConfig = appSiteConfig();
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
			appLock().readLock().unlock();
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
		appLock().readLock().lock();
		try {

			MHost theHost = appSiteConfig().localHost();
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
			appLock().readLock().unlock();
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
		MSiteConfig aConfig = appSiteConfig();

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
				anInstance.scheduleRefuseTask( new MInstanceTask.Refuse( anInstance, FProperties.K.REFUSE_NUM_RETRIES.value() ), FORCE_QUIT_DELAY, FORCE_QUIT_DELAY );
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

		if( anInstance.host() != appSiteConfig().localHost() ) {
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
	private static Map<String,Object> createInstanceRequestDictionary( final String commandString, final String queryString, final MInstance anInstance ) {
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
	private static boolean _testConnection( final MInstance instance ) {
		try( Socket aSocket = new Socket() ) {
			aSocket.connect( new InetSocketAddress( instance.host().name(), instance.port() ), 1000 );
			return true;
		}
		catch( IOException e ) {
			return false;
		}
	}
	
	private MSiteConfig appSiteConfig() {
		return _appTaskd.siteConfig();
	}
	
	private ReentrantReadWriteLock appLock() {
		return _appTaskd.lock();
	}
}