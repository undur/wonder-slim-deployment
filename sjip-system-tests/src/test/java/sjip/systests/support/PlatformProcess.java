package sjip.systests.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subprocess manager for a single platform component (wotaskd or JavaMonitor).
 * Spawns the component's launch script with a given port and an isolated config
 * directory, polls the admin endpoint until ready, and surfaces graceful
 * shutdown plus log streaming for diagnostic purposes.
 *
 * <p>Intended lifecycle:
 * {@snippet :
 * try (PlatformProcess wotaskd = PlatformProcess.startWotaskd(port, configDir)) {
 *     wotaskd.awaitReady(Duration.ofSeconds(30));
 *     // run scenario, assert artifacts
 * }
 * }
 *
 * <p>{@link #close()} sends a shutdown request, then terminates the process if
 * it hasn't exited within a grace period.
 */
public final class PlatformProcess implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger( PlatformProcess.class );

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.version( HttpClient.Version.HTTP_1_1 )
			.connectTimeout( Duration.ofSeconds( 2 ) )
			.build();

	private final String _label;
	private final int _port;
	private final Path _configDirectory;
	private final Process _process;
	private final ScheduledExecutorService _logForwarder;

	private PlatformProcess( final String label, final int port, final Path configDirectory, final Process process, final ScheduledExecutorService logForwarder ) {
		_label = label;
		_port = port;
		_configDirectory = configDirectory;
		_process = process;
		_logForwarder = logForwarder;
	}

	/**
	 * Starts a wotaskd subprocess from the built wotaskd.woa.
	 *
	 * @param port             TCP port for wotaskd to listen on
	 * @param configDirectory  isolated directory for {@code SiteConfig.xml}; should be empty or
	 *                         pre-populated by the caller
	 */
	public static PlatformProcess startWotaskd( final int port, final Path configDirectory ) throws IOException {
		final Path woa = locateWoa( "wotaskd" );
		return start( "wotaskd", woa, port, configDirectory, List.of() );
	}

	/**
	 * Starts a JavaMonitor subprocess from the built JavaMonitor.woa.
	 *
	 * @param port                       TCP port for JavaMonitor to listen on
	 * @param configDirectory            isolated directory for JavaMonitor's {@code SiteConfig.xml}; the
	 *                                   caller should pre-populate it (e.g. with the wotaskd this JavaMonitor
	 *                                   should talk to) before launch
	 * @param lifebeatDestinationPort    the port the wotaskd this JavaMonitor should talk to is listening on.
	 *                                   JavaMonitor reads this via {@code WOApplication.lifebeatDestinationPort()}
	 *                                   when building wotaskd request URLs.
	 */
	public static PlatformProcess startJavaMonitor( final int port, final Path configDirectory, final int lifebeatDestinationPort ) throws IOException {
		final Path woa = locateWoa( "JavaMonitor" );
		final List<String> extraArgs = List.of( "-WOLifebeatDestinationPort", String.valueOf( lifebeatDestinationPort ) );
		return start( "JavaMonitor", woa, port, configDirectory, extraArgs );
	}

	private static Path locateWoa( final String moduleName ) {
		// Assume the test runs from sjip-system-tests/, so each module's target sits at ../<module>/target/<module>.woa.
		final Path candidate = Path.of( "..", moduleName, "target", moduleName + ".woa" ).toAbsolutePath().normalize();
		if( !Files.isDirectory( candidate ) ) {
			throw new IllegalStateException( moduleName + ".woa not found at " + candidate + " — run `mvn install` on the " + moduleName + " module first" );
		}
		return candidate;
	}

	private static PlatformProcess start( final String label, final Path woaDirectory, final int port, final Path configDirectory, final List<String> extraArgs ) throws IOException {
		final Path launchScript = woaDirectory.resolve( label );
		if( !Files.isExecutable( launchScript ) ) {
			throw new IllegalStateException( "Launch script not executable: " + launchScript );
		}

		Files.createDirectories( configDirectory );

		final List<String> command = new ArrayList<>();
		command.add( launchScript.toString() );
		command.add( "-WOPort" );
		command.add( String.valueOf( port ) );
		command.add( "-WODeploymentConfigurationDirectory" );
		command.add( configDirectory.toString() );
		command.addAll( extraArgs );

		logger.info( "Starting {} on port {} with config dir {}", label, port, configDirectory );

		final ProcessBuilder pb = new ProcessBuilder( command )
				.redirectErrorStream( true )
				.directory( woaDirectory.toFile() );

		final Process process = pb.start();

		// Forward subprocess stdout/stderr to our logger so test output is debuggable.
		final ScheduledExecutorService logForwarder = Executors.newSingleThreadScheduledExecutor( r -> {
			final Thread t = new Thread( r, label + "-output-drain" );
			t.setDaemon( true );
			return t;
		} );
		logForwarder.submit( () -> drainOutput( label, process ) );

		return new PlatformProcess( label, port, configDirectory, process, logForwarder );
	}

	private static void drainOutput( final String label, final Process process ) {
		try( final BufferedReader reader = new BufferedReader( new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
			String line;
			while( (line = reader.readLine()) != null ) {
				logger.info( "[{}] {}", label, line );
			}
		}
		catch( final IOException e ) {
			logger.debug( "[{}] output drain ended: {}", label, e.toString() );
		}
	}

	/**
	 * Polls wotaskd's admin endpoint until it responds, or fails after {@code timeout} elapses.
	 * Readiness is "the socket accepts connections AND wotaskd has installed its request handlers" —
	 * checked by hitting the {@code /cgi-bin/WebObjects/wotaskd.woa/wa/default} URL (WO dispatch
	 * appends {@code "Action"} to find {@code defaultAction}) and accepting any HTTP response
	 * (success or auth-failure both indicate the handler is registered).
	 */
	public void awaitReady( final Duration timeout ) throws InterruptedException {
		final Instant deadline = Instant.now().plus( timeout );
		// The launch script's name (e.g. "wotaskd", "JavaMonitor") is also the .woa basename
		// and the WO app name in the URL.
		final String probeUrl = "http://127.0.0.1:" + _port + "/cgi-bin/WebObjects/" + _label + ".woa/wa/default";

		while( Instant.now().isBefore( deadline ) ) {
			if( !_process.isAlive() ) {
				throw new IllegalStateException( _label + " exited before becoming ready (exit code " + _process.exitValue() + ")" );
			}

			try {
				final HttpResponse<Void> response = HTTP_CLIENT.send(
						HttpRequest.newBuilder( URI.create( probeUrl ) ).timeout( Duration.ofMillis( 500 ) ).GET().build(),
						BodyHandlers.discarding() );
				// Any HTTP response means the server is up and the request handler is wired.
				logger.info( "{} ready on port {} (probe returned HTTP {})", _label, _port, response.statusCode() );
				return;
			}
			catch( final IOException e ) {
				// Not ready yet — connection refused or timed out. Wait briefly and retry.
				Thread.sleep( 250 );
			}
		}

		throw new IllegalStateException( _label + " did not become ready within " + timeout );
	}

	/**
	 * @return the port this component is listening on
	 */
	public int port() {
		return _port;
	}

	/**
	 * @return the isolated config directory for this component
	 */
	public Path configDirectory() {
		return _configDirectory;
	}

	/**
	 * @return the path to this component's {@code SiteConfig.xml} on disk
	 */
	public Path siteConfigFile() {
		return _configDirectory.resolve( "SiteConfig.xml" );
	}

	/**
	 * Returns true if the underlying process is still running.
	 */
	public boolean isAlive() {
		return _process.isAlive();
	}

	@Override
	public void close() {
		if( !_process.isAlive() ) {
			logger.debug( "{} already exited (exit code {})", _label, _process.exitValue() );
			_logForwarder.shutdownNow();
			return;
		}

		logger.info( "Shutting down {}", _label );
		_process.destroy();

		try {
			if( !_process.waitFor( 10, TimeUnit.SECONDS ) ) {
				logger.warn( "{} did not exit cleanly within 10s; forcing", _label );
				_process.destroyForcibly();
				_process.waitFor( 5, TimeUnit.SECONDS );
			}
		}
		catch( final InterruptedException e ) {
			Thread.currentThread().interrupt();
			_process.destroyForcibly();
		}

		_logForwarder.shutdownNow();
		logger.info( "{} stopped (exit code {})", _label, _process.exitValue() );
	}
}
