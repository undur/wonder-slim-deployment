package sjip.systests;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import sjip.systests.support.PlatformProcess;
import sjip.systests.support.SnapshotAsserter;
import sjip.systests.support.TestReport;
import sjip.systests.support.TestReportExtension;
import sjip.systests.support.WotaskdClient;
import sjip.systests.support.WotaskdClient.WireExchange;

/**
 * Scenario: wotaskd is pre-seeded with one host and one application, then asked
 * to add a single instance via {@code updateWotaskd / add / instanceArray}.
 * Instances reference their host and application by name, so the test setup
 * has to put both in place first.
 *
 * <p>The minimal instance envelope carries {@code applicationName}, {@code id},
 * {@code hostName}, and {@code port} — the identity fields wotaskd uses to look
 * up instances later (e.g. when dispatching {@code commandWotaskd}). Other
 * fields (scheduling, additional args) are left out so the snapshot captures
 * the minimum shape; richer-field variants belong in separate tests.
 */
@TestInstance( Lifecycle.PER_CLASS )
@TestMethodOrder( OrderAnnotation.class )
@ExtendWith( TestReportExtension.class )
class AddInstanceScenarioIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 12345;

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "AddInstanceScenario" );

	@BeforeAll
	void bootWotaskd_andSeedHostAndApplication() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-addinst-" );
		final int port = freePort();
		_wotaskd = PlatformProcess.startWotaskd( port, _configDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_client = new WotaskdClient( port );

		// Seed: add the host and the application that the instance will reference.
		// Each is a separate `add` envelope; not snapshotted here — those shapes are
		// covered by AddHostScenarioIT and AddApplicationScenarioIT.
		sendAdd( "hostArray", Map.of( "name", HOST_NAME, "type", HOST_TYPE ) );
		sendAdd( "applicationArray", Map.of( "name", APP_NAME ) );
	}

	@AfterAll
	void shutdownWotaskd() {
		if( _wotaskd != null ) {
			_wotaskd.close();
		}
	}

	@Test
	@Order( 1 )
	void addInstance_succeeds_andPersistsSiteConfig( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state (host + application already added)" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Add an instance" );
		report.action( "POST monitorRequest{updateWotaskd: {add: {instanceArray: [{applicationName, id, hostName, port}]}}} to wotaskd" );

		final Map<String, Object> instance = new LinkedHashMap<>();
		instance.put( "applicationName", APP_NAME );
		instance.put( "id", INSTANCE_ID );
		instance.put( "hostName", HOST_NAME );
		instance.put( "port", INSTANCE_PORT );

		final Map<String, Object> addDict = new LinkedHashMap<>();
		addDict.put( "instanceArray", List.of( instance ) );

		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "add", addDict );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", updateDict );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );

		assertEquals( 200, exchange.statusCode(), "addInstance should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "addInstance-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "addInstance-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after addInstance", persisted );
		_snapshots.assertMatches( "addInstance-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 2 )
	void querySite_afterAddInstance_reportsTheNewInstance( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.note( "Verifies a subsequent SITE query reports the instance added by the previous test." );
		report.action( "POST monitorRequest{queryWotaskd: SITE} to wotaskd" );

		final WireExchange exchange = _client.querySite( null );

		assertEquals( 200, exchange.statusCode(), "queryWotaskd=SITE should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "querySite-afterAddInstance-response", "xml", exchange.responseXml() );
	}

	private void sendAdd( final String arrayKey, final Map<String, Object> element ) throws Exception {
		final Map<String, Object> addDict = new LinkedHashMap<>();
		addDict.put( arrayKey, List.of( element ) );

		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "add", addDict );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", updateDict );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );
		assertEquals( 200, exchange.statusCode(), "seed add " + arrayKey + " should return HTTP 200" );
	}

	private static int freePort() throws IOException {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
