package sjip.systests;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
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
 * Captures the wire shapes for {@code queryWotaskd}'s three non-SITE variants:
 * {@code HOST}, {@code APPLICATION}, and {@code INSTANCE}. The {@code SITE}
 * variant is already covered by {@link WotaskdBootSmokeIT} and several other
 * scenarios.
 *
 * <h2>Normalization caveat</h2>
 *
 * <p>HOST and INSTANCE responses include machine-dependent values: HOST carries
 * {@code processorType} and {@code operatingSystem} read from {@code os.arch}
 * and {@code os.name}; INSTANCE carries a {@code statistics} sub-dict with
 * timing fields. To keep snapshots stable across CI machines, we apply a
 * regex normalizer to those responses before snapshotting — the *shape* (which
 * keys exist) is locked in, but the volatile *values* get replaced with
 * placeholders. That means a domain refactor that changes the *structure*
 * still trips the snapshot, but one that just produces different values
 * (different OS, different timing) doesn't.
 */
@TestInstance( Lifecycle.PER_CLASS )
@ExtendWith( TestReportExtension.class )
class QueryScenariosIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 12345;

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "QueryScenarios" );

	@BeforeAll
	void bootWotaskd_andSeedFullConfig() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-query-" );
		final int port = freePort();
		_wotaskd = PlatformProcess.startWotaskd( port, _configDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_client = new WotaskdClient( port );

		sendAdd( "hostArray", Map.of( "name", HOST_NAME, "type", HOST_TYPE ) );
		sendAdd( "applicationArray", Map.of( "name", APP_NAME ) );
		sendAdd( "instanceArray", Map.of( "applicationName", APP_NAME, "id", INSTANCE_ID, "hostName", HOST_NAME, "port", INSTANCE_PORT ) );
	}

	@AfterAll
	void shutdownWotaskd() {
		if( _wotaskd != null ) {
			_wotaskd.close();
		}
	}

	@Test
	void query_HOST_returnsHostMetadata( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Query the local host" );
		report.note( "HOST returns the local wotaskd's machine info — processor type, OS, running-instance count." );
		report.action( "POST monitorRequest{queryWotaskd: HOST} to wotaskd" );

		final WireExchange exchange = sendQuery( "HOST" );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "queryHost-request", "xml", exchange.requestXml() );

		// processorType and operatingSystem are read from the local machine's system
		// properties; normalize so snapshots are stable across CI.
		final String normalizedResponse = exchange.responseXml()
				.replaceAll( "(<processorType type=\"NSString\">)[^<]+(</processorType>)", "$1{NORMALIZED-os.arch}$2" )
				.replaceAll( "(<operatingSystem type=\"NSString\">)[^<]+(</operatingSystem>)", "$1{NORMALIZED-os.name os.version}$2" );

		_snapshots.assertMatches( "queryHost-response-normalized", "xml", normalizedResponse );
	}

	@Test
	void query_APPLICATION_returnsApplicationsAndRunningCounts( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Query applications" );
		report.note( "APPLICATION returns each configured application with its current running-instances count." );
		report.action( "POST monitorRequest{queryWotaskd: APPLICATION} to wotaskd" );

		final WireExchange exchange = sendQuery( "APPLICATION" );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "queryApplication-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "queryApplication-response", "xml", exchange.responseXml() );
	}

	@Test
	void query_INSTANCE_returnsInstancesOnLocalHost( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Query instances on the local host" );
		report.note( "INSTANCE returns every instance whose host matches the local wotaskd's host, with state, refusal flag, statistics, deaths, and next-shutdown info." );
		report.action( "POST monitorRequest{queryWotaskd: INSTANCE} to wotaskd" );

		final WireExchange exchange = sendQuery( "INSTANCE" );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "queryInstance-request", "xml", exchange.requestXml() );

		// `statistics` is a sub-dict that includes timing values which may vary by run.
		// Mask anything inside <statistics>...</statistics> with a placeholder. The
		// rest of the response (state, deaths, etc.) should be deterministic for an
		// instance that's never been started.
		final String normalizedResponse = exchange.responseXml().replaceAll(
				"(?s)(<statistics type=\"NSDictionary\">)(.*?)(</statistics>)",
				"$1{NORMALIZED-statistics-subdict}$3" );

		_snapshots.assertMatches( "queryInstance-response-normalized", "xml", normalizedResponse );
	}

	private WireExchange sendQuery( final String queryKind ) throws Exception {
		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "queryWotaskd", queryKind );
		return _client.sendMonitorRequest( requestBody, null );
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
