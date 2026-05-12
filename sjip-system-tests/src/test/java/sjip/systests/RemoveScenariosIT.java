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
 * Captures the wire shapes for {@code updateWotaskd → remove → {hostArray,
 * applicationArray, instanceArray}}. Mirrors the add-side scenarios.
 *
 * <p>The setup builds a fully-populated SiteConfig (one host, one application,
 * one instance) so each remove operation has something real to delete. Tests
 * are ordered: instance first, then application, then host, so each step
 * leaves the config in a state the next step can act on (you can't keep a
 * removed application's instance around; you can't keep a host with
 * orphaned instances).
 *
 * <p>One detail worth knowing: removing the local host triggers
 * {@code stopAllInstances + setSiteConfig(new MSiteConfig(null))} rather than
 * the normal {@code removeHost_W} path (see DirectAction.java:184). Since we
 * seed with {@code localhost}, removing it exercises that special branch —
 * which is part of the contract we want to lock in.
 */
@TestInstance( Lifecycle.PER_CLASS )
@TestMethodOrder( OrderAnnotation.class )
@ExtendWith( TestReportExtension.class )
class RemoveScenariosIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 12345;

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "RemoveScenarios" );

	@BeforeAll
	void bootWotaskd_andSeedFullConfig() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-remove-" );
		final int port = freePort();
		_wotaskd = PlatformProcess.startWotaskd( port, _configDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_client = new WotaskdClient( port );

		// Seed: host, application, instance.
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
	@Order( 1 )
	void removeInstance_succeeds_andPersistsSiteConfig( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state (host + application + instance)" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Remove the instance" );
		report.action( "POST monitorRequest{updateWotaskd: {remove: {instanceArray: [{hostName, port}]}}} to wotaskd" );

		final WireExchange exchange = sendRemove( "instanceArray", Map.of( "hostName", HOST_NAME, "port", INSTANCE_PORT ) );

		assertEquals( 200, exchange.statusCode(), "removeInstance should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "removeInstance-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "removeInstance-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after removeInstance", persisted );
		_snapshots.assertMatches( "removeInstance-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 2 )
	void removeApplication_succeeds_andPersistsSiteConfig( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state (instance already removed)" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Remove the application" );
		report.action( "POST monitorRequest{updateWotaskd: {remove: {applicationArray: [{name}]}}} to wotaskd" );

		final WireExchange exchange = sendRemove( "applicationArray", Map.of( "name", APP_NAME ) );

		assertEquals( 200, exchange.statusCode(), "removeApplication should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "removeApplication-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "removeApplication-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after removeApplication", persisted );
		_snapshots.assertMatches( "removeApplication-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 3 )
	void removeHost_succeeds_andPersistsSiteConfig( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state (instance + application already removed; only host left)" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Remove the host" );
		report.note( "Removing the local host triggers a different code path in wotaskd than removing a remote host — stopAllInstances + replacing the SiteConfig wholesale. The wire envelope is identical though." );
		report.action( "POST monitorRequest{updateWotaskd: {remove: {hostArray: [{name}]}}} to wotaskd" );

		final WireExchange exchange = sendRemove( "hostArray", Map.of( "name", HOST_NAME ) );

		assertEquals( 200, exchange.statusCode(), "removeHost should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "removeHost-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "removeHost-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after removeHost", persisted );
		_snapshots.assertMatches( "removeHost-SiteConfig", "xml", persisted );
	}

	private WireExchange sendRemove( final String arrayKey, final Map<String, Object> element ) throws Exception {
		final Map<String, Object> removeDict = new LinkedHashMap<>();
		removeDict.put( arrayKey, List.of( element ) );

		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "remove", removeDict );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", updateDict );

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
