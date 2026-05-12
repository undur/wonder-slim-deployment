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
 * Scenario: a fresh wotaskd is told to add a single host via
 * {@code updateWotaskd / add / hostArray}. We assert that
 * <ul>
 *   <li>the request and response wire shapes match committed snapshots,</li>
 *   <li>the persisted {@code SiteConfig.xml} on disk matches a committed snapshot, and</li>
 *   <li>a follow-up {@code queryWotaskd=SITE} reports the new host.</li>
 * </ul>
 *
 * <p>Tests are ordered so {@code addHost} runs before the follow-up query;
 * mutation-then-readback is the scenario, not three independent cases.
 */
@TestInstance( Lifecycle.PER_CLASS )
@TestMethodOrder( OrderAnnotation.class )
@ExtendWith( TestReportExtension.class )
class AddHostScenarioIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "AddHostScenario" );

	@BeforeAll
	void bootWotaskd() throws IOException, InterruptedException {
		_configDir = Files.createTempDirectory( "sjip-systests-addhost-" );
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
	void addHost_succeeds_andPersistsSiteConfig( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Add a host" );
		report.action( "POST monitorRequest{updateWotaskd: {add: {hostArray: [{name: " + HOST_NAME + ", type: " + HOST_TYPE + "}]}}} to wotaskd" );

		final Map<String, Object> host = new LinkedHashMap<>();
		host.put( "name", HOST_NAME );
		host.put( "type", HOST_TYPE );

		final Map<String, Object> addDict = new LinkedHashMap<>();
		addDict.put( "hostArray", List.of( host ) );

		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "add", addDict );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", updateDict );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );

		assertEquals( 200, exchange.statusCode(), "addHost should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "addHost-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "addHost-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after addHost", persisted );
		_snapshots.assertMatches( "addHost-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 2 )
	void querySite_afterAddHost_reportsTheNewHost( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.note( "Verifies that a subsequent SITE query reports the host added by the previous test." );
		report.action( "POST monitorRequest{queryWotaskd: SITE} to wotaskd" );

		final WireExchange exchange = _client.querySite( null );

		assertEquals( 200, exchange.statusCode(), "queryWotaskd=SITE should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "querySite-afterAddHost-response", "xml", exchange.responseXml() );
	}

	private static int freePort() throws IOException {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
