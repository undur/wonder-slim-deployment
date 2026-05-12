package sjip.systests;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
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
 * Captures the remaining wire shapes that don't fit cleanly into the
 * update/command/query categories:
 *
 * <ul>
 *   <li>{@code updateWotaskd → clear} — dead branch on the wotaskd side (no
 *       client sends this today) but the dispatch still recognises it. We
 *       snapshot the shape for completeness; if the branch is later removed,
 *       this test will fail and force a deliberate decision.</li>
 *   <li>{@code updateWotaskd → sync} — JavaMonitor sends this to re-establish
 *       canonical state on wotaskds that previously had errors.</li>
 *   <li>{@code woconfigAction} (GET) — the adaptor-config XML used by WO
 *       HTTP adaptors. Small, deterministic shape.</li>
 * </ul>
 */
@TestInstance( Lifecycle.PER_CLASS )
@ExtendWith( TestReportExtension.class )
class MiscWireShapesIT {

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.version( HttpClient.Version.HTTP_1_1 )
			.connectTimeout( Duration.ofSeconds( 5 ) )
			.build();

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "MiscWireShapes" );

	@BeforeAll
	void bootWotaskd_andSeedHostAndApp() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-misc-" );
		final int port = freePort();
		_wotaskd = PlatformProcess.startWotaskd( port, _configDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_client = new WotaskdClient( port );

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
	void clear_command_dispatchIsRecognised( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Send clear" );
		report.note( "`clear` is a dead branch on the wotaskd side — no client in our codebase sends it today, but the dispatch still recognises it (see FIXME at DirectAction.java:145). Snapshotted for completeness so a future deletion is a deliberate decision." );
		report.action( "POST monitorRequest{updateWotaskd: {clear: \"y\"}} to wotaskd" );

		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "clear", "y" );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", updateDict );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "clear-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "clear-response", "xml", exchange.responseXml() );
	}

	@Test
	void sync_pushesFullSiteConfigAsBaseline( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Send sync" );
		report.note( "`sync` is what JavaMonitor pushes to wotaskds that previously had errors, to re-establish canonical state. The envelope carries a complete SiteConfig dict, similar to overwrite but with different intent (the receiver merges rather than replacing)." );
		report.action( "POST monitorRequest{updateWotaskd: {sync: {SiteConfig: {...}}}} to wotaskd" );

		final Map<String, Object> siteConfigDict = new LinkedHashMap<>();
		siteConfigDict.put( "hostArray", List.of( Map.of( "name", HOST_NAME, "type", HOST_TYPE ) ) );
		siteConfigDict.put( "applicationArray", List.of( Map.of( "name", APP_NAME ) ) );

		final Map<String, Object> syncDict = new LinkedHashMap<>();
		syncDict.put( "SiteConfig", siteConfigDict );

		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "sync", syncDict );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", updateDict );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );

		assertEquals( 200, exchange.statusCode() );

		_snapshots.assertMatches( "sync-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "sync-response", "xml", exchange.responseXml() );
	}

	@Test
	void woconfigAction_returnsAdaptorConfigXml( TestReport report ) throws Exception {
		report.heading( "GET woconfig" );
		report.note( "woconfigAction returns adaptor-config XML used by WO HTTP adaptors. Distinct shape from monitorRequest envelopes. Headers indicate text/xml and a Last-Modified timestamp." );
		report.action( "GET /cgi-bin/WebObjects/wotaskd.woa/wa/woconfig" );

		final HttpRequest request = HttpRequest.newBuilder()
				.uri( URI.create( "http://127.0.0.1:" + _wotaskd.port() + "/cgi-bin/WebObjects/wotaskd.woa/wa/woconfig" ) )
				.timeout( Duration.ofSeconds( 10 ) )
				.GET()
				.build();

		final HttpResponse<String> response = HTTP_CLIENT.send( request, BodyHandlers.ofString( StandardCharsets.UTF_8 ) );

		report.wireSend( "test → wotaskd (GET /wa/woconfig)", "(empty body)" );
		report.wireReceive( "wotaskd → test (HTTP " + response.statusCode() + ")", response.body() );

		assertEquals( 200, response.statusCode() );

		_snapshots.assertMatches( "woconfig-response", "xml", response.body() );
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
