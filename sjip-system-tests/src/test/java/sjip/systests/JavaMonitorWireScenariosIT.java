package sjip.systests;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sjip.systests.support.JavaMonitorTestActionClient;
import sjip.systests.support.PlatformProcess;
import sjip.systests.support.SnapshotAsserter;
import sjip.systests.support.TestReport;
import sjip.systests.support.TestReportExtension;
import sjip.systests.support.WireCapturingProxy;
import sjip.systests.support.WotaskdClient;

/**
 * End-to-end ITs that drive JavaMonitor's {@link sjip.monitor.test.TestDirectAction}
 * over HTTP and capture every byte JavaMonitor actually sends to wotaskd. This
 * is the gap that hand-built-envelope tests can't close: it locks in
 * JavaMonitor's <em>send</em>-side wire construction, not just wotaskd's
 * receive contract.
 *
 * <p>All operations go through {@link WOTaskdHandler#sendOverwriteToWotaskd} or
 * {@code WOTaskdHandler.send*} family methods, which means
 * {@code createUpdateRequestDictionary} is exercised for the application/
 * instance arrays — the exact code path that previously had no coverage.
 *
 * <p>Tests are ordered: each builds on the state established by the previous,
 * so the sequence reflects a realistic deployment lifecycle:
 *
 * <ol>
 *   <li>addHost (first host → bootstraps via {@code overwrite})</li>
 *   <li>addApplication</li>
 *   <li>addInstance</li>
 *   <li>configureApplication</li>
 *   <li>configureInstance</li>
 *   <li>removeInstance</li>
 *   <li>removeApplication</li>
 *   <li>removeHost</li>
 * </ol>
 *
 * <p>For each operation, we snapshot the JavaMonitor→wotaskd request body
 * captured by the wire proxy. wotaskd's resulting persisted state is also
 * snapshotted, partly as documentation of effect, partly as a defense against
 * silent failures (a {@code sendAdd*} that constructs the wrong envelope
 * might still return HTTP 200 but the SiteConfig won't update).
 */
@TestInstance( Lifecycle.PER_CLASS )
@TestMethodOrder( OrderAnnotation.class )
@ExtendWith( TestReportExtension.class )
class JavaMonitorWireScenariosIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 12345;

	private Path _wotaskdConfigDir;
	private Path _javaMonitorConfigDir;
	private PlatformProcess _wotaskd;
	private PlatformProcess _javaMonitor;
	private WireCapturingProxy _proxy;
	private WotaskdClient _wotaskdClient;
	private JavaMonitorTestActionClient _javaMonitorClient;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "JavaMonitorWireScenarios" );

	@BeforeAll
	void bootSubsystems() throws IOException, InterruptedException {
		_wotaskdConfigDir = Files.createTempDirectory( "sjip-systests-jmwire-wotaskd-" );
		_javaMonitorConfigDir = Files.createTempDirectory( "sjip-systests-jmwire-jm-" );

		final int wotaskdPort = freePort();
		_wotaskd = PlatformProcess.startWotaskd( wotaskdPort, _wotaskdConfigDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_wotaskdClient = new WotaskdClient( wotaskdPort );

		_proxy = WireCapturingProxy.start( "127.0.0.1", wotaskdPort );

		final int javaMonitorPort = freePort();
		_javaMonitor = PlatformProcess.startJavaMonitor( javaMonitorPort, _javaMonitorConfigDir, _proxy.port() );
		_javaMonitor.awaitReady( Duration.ofSeconds( 30 ) );
		_javaMonitorClient = new JavaMonitorTestActionClient( javaMonitorPort );
	}

	@AfterAll
	void shutdownSubsystems() {
		if( _javaMonitor != null ) {
			_javaMonitor.close();
		}
		if( _proxy != null ) {
			_proxy.close();
		}
		if( _wotaskd != null ) {
			_wotaskd.close();
		}
	}

	@Test
	@Order( 1 )
	void addHost( TestReport report ) throws Exception {
		runScenarioStep( report, "addHost", "addHost",
				() -> _javaMonitorClient.addHost( HOST_NAME, HOST_TYPE ) );
	}

	@Test
	@Order( 2 )
	void addApplication( TestReport report ) throws Exception {
		runScenarioStep( report, "addApplication", "addApplication",
				() -> _javaMonitorClient.addApplication( APP_NAME ) );
	}

	@Test
	@Order( 3 )
	void addInstance( TestReport report ) throws Exception {
		runScenarioStep( report, "addInstance", "addInstance",
				() -> _javaMonitorClient.addInstance( APP_NAME, HOST_NAME, INSTANCE_ID, INSTANCE_PORT ) );
	}

	@Test
	@Order( 4 )
	void configureApplication( TestReport report ) throws Exception {
		runScenarioStep( report, "configureApplication", "configureApplication",
				() -> _javaMonitorClient.configureApplication( APP_NAME, Map.of( "autoRecover", "true" ) ) );
	}

	@Test
	@Order( 5 )
	void configureInstance( TestReport report ) throws Exception {
		runScenarioStep( report, "configureInstance", "configureInstance",
				() -> _javaMonitorClient.configureInstance( HOST_NAME, INSTANCE_PORT, Map.of() ) );
	}

	@Test
	@Order( 6 )
	void removeInstance( TestReport report ) throws Exception {
		runScenarioStep( report, "removeInstance", "removeInstance",
				() -> _javaMonitorClient.removeInstance( HOST_NAME, INSTANCE_PORT ) );
	}

	@Test
	@Order( 7 )
	void removeApplication( TestReport report ) throws Exception {
		runScenarioStep( report, "removeApplication", "removeApplication",
				() -> _javaMonitorClient.removeApplication( APP_NAME ) );
	}

	@Test
	@Order( 8 )
	void removeHost( TestReport report ) throws Exception {
		runScenarioStep( report, "removeHost", "removeHost",
				() -> _javaMonitorClient.removeHost( HOST_NAME ) );
	}

	private void runScenarioStep( final TestReport report, final String stepName, final String snapshotPrefix, final Callable<HttpResponse<String>> action ) throws Exception {
		_javaMonitorClient.recordInto( report );
		_proxy.recordInto( report, "/wa/monitorRequest" );

		report.heading( "Initial state (before " + stepName + ")" );
		report.state( "wotaskd SiteConfig.xml", java.nio.file.Files.readString( _wotaskd.siteConfigFile(), java.nio.charset.StandardCharsets.UTF_8 ) );

		// Reset the proxy's exchange log so we can isolate this step's traffic.
		// (Earlier steps in this class's scenario will have left their own exchanges
		// in there, plus lifebeat noise from JavaMonitor's main thread.)
		final int proxyExchangesBefore = _proxy.exchanges().size();

		report.heading( stepName );
		report.action( "POST JavaMonitor /test/" + stepName );

		final HttpResponse<String> response = action.call();

		assertEquals( 200, response.statusCode(), stepName + " should return HTTP 200; body was: " + response.body() );
		assertEquals( "OK", response.body().trim(), stepName + " response body should be 'OK'; was: " + response.body() );

		// Snapshot only the monitorRequest exchanges added by this step.
		final var stepExchanges = _proxy.exchanges().subList( proxyExchangesBefore, _proxy.exchanges().size() ).stream()
				.filter( e -> e.path().contains( "/wa/monitorRequest" ) )
				.toList();

		for( int i = 0; i < stepExchanges.size(); i++ ) {
			final var ex = stepExchanges.get( i );
			final String suffix = stepExchanges.size() > 1 ? "-" + (i + 1) : "";
			_snapshots.assertMatches( snapshotPrefix + suffix + "-request", "xml", ex.requestBody() );
			_snapshots.assertMatches( snapshotPrefix + suffix + "-response", "xml", ex.responseBody() );
		}

		report.heading( "Resulting state" );
		final String persisted = java.nio.file.Files.readString( _wotaskd.siteConfigFile(), java.nio.charset.StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml", persisted );
		_snapshots.assertMatches( snapshotPrefix + "-SiteConfig", "xml", persisted );
	}

	@FunctionalInterface
	private interface Callable<T> {
		T call() throws Exception;
	}

	private static int freePort() throws IOException {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
