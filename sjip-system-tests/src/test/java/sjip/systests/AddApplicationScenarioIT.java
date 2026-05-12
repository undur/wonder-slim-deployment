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
 * Scenario: a fresh wotaskd is told to add a single application via
 * {@code updateWotaskd / add / applicationArray}. Snapshots the request/response
 * wire shapes and the persisted SiteConfig that results.
 *
 * <p>Mirrors {@link AddHostScenarioIT} for the application side. Like that test
 * we send a minimal envelope — just {@code name} — so the snapshot captures the
 * smallest meaningful application shape; tests that exercise a richer set of
 * fields (paths, port windows, scheduling) belong elsewhere.
 */
@TestInstance( Lifecycle.PER_CLASS )
@TestMethodOrder( OrderAnnotation.class )
@ExtendWith( TestReportExtension.class )
class AddApplicationScenarioIT {

	private static final String APP_NAME = "DemoApp";

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "AddApplicationScenario" );

	@BeforeAll
	void bootWotaskd() throws IOException, InterruptedException {
		_configDir = Files.createTempDirectory( "sjip-systests-addapp-" );
		final int port = freePort();
		_wotaskd = PlatformProcess.startWotaskd( port, _configDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_client = new WotaskdClient( port );
	}

	@AfterAll
	void shutdownWotaskd() {
		if( _wotaskd != null ) {
			_wotaskd.close();
		}
	}

	@Test
	@Order( 1 )
	void addApplication_succeeds_andPersistsSiteConfig( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Add an application" );
		report.action( "POST monitorRequest{updateWotaskd: {add: {applicationArray: [{name: " + APP_NAME + "}]}}} to wotaskd" );

		final Map<String, Object> application = new LinkedHashMap<>();
		application.put( "name", APP_NAME );

		final Map<String, Object> addDict = new LinkedHashMap<>();
		addDict.put( "applicationArray", List.of( application ) );

		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "add", addDict );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", updateDict );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );

		assertEquals( 200, exchange.statusCode(), "addApplication should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "addApplication-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "addApplication-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after addApplication", persisted );
		_snapshots.assertMatches( "addApplication-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 2 )
	void querySite_afterAddApplication_reportsTheNewApplication( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.note( "Verifies a subsequent SITE query reports the application added by the previous test." );
		report.action( "POST monitorRequest{queryWotaskd: SITE} to wotaskd" );

		final WireExchange exchange = _client.querySite( null );

		assertEquals( 200, exchange.statusCode(), "queryWotaskd=SITE should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "querySite-afterAddApplication-response", "xml", exchange.responseXml() );
	}

	private static int freePort() throws IOException {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
