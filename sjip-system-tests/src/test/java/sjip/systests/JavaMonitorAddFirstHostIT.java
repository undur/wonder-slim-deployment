package sjip.systests;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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

import sjip.systests.support.JavaMonitorAdminClient;
import sjip.systests.support.PlatformProcess;
import sjip.systests.support.SnapshotAsserter;
import sjip.systests.support.TestReport;
import sjip.systests.support.TestReportExtension;
import sjip.systests.support.WireCapturingProxy;
import sjip.systests.support.WotaskdClient;
import sjip.systests.support.WotaskdClient.WireExchange;

/**
 * Scenario: a fresh JavaMonitor and a fresh wotaskd, both with empty configs. The test
 * drives JavaMonitor's {@code admin/addHost} direct action to add wotaskd as the first
 * host in JavaMonitor's site config. JavaMonitor's production code path
 * ({@code WOTaskdHandler.sendOverwriteToWotaskd}) pushes the new site config to that
 * wotaskd over the wire.
 *
 * <p>A {@link WireCapturingProxy} sits between JavaMonitor and wotaskd, so the test JVM
 * observes every byte that crosses the wire. JavaMonitor's
 * {@code -WOLifebeatDestinationPort} points at the proxy; the proxy forwards to wotaskd's
 * real port.
 *
 * <p>We assert:
 * <ul>
 *   <li>JavaMonitor returns 200 OK,</li>
 *   <li>the captured JavaMonitor→wotaskd request/response wire bodies match committed snapshots,</li>
 *   <li>the persisted {@code SiteConfig.xml} on wotaskd matches a committed snapshot, and</li>
 *   <li>a follow-up {@code queryWotaskd=SITE} reports the new host.</li>
 * </ul>
 *
 * <p>Scope: exercises only the "first host being added" path, which triggers
 * {@code sendOverwriteToWotaskd}. The "add a host while peers exist" path also triggers
 * {@code sendAddHostToWotaskds}; that requires a second wotaskd on a different port, but
 * JavaMonitor today uses a single global {@code lifebeatDestinationPort} to reach every
 * host. Until {@code MHost} carries its own port, multi-wotaskd scenarios aren't
 * reachable from a single JVM. See FIXMEs in {@code MHost.sendRequestToWotaskd} and
 * {@code WOTaskdComms.sendRequestToWotaskdArray}.
 */
@TestInstance( Lifecycle.PER_CLASS )
@TestMethodOrder( OrderAnnotation.class )
@ExtendWith( TestReportExtension.class )
class JavaMonitorAddFirstHostIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";

	private Path _wotaskdConfigDir;
	private Path _javaMonitorConfigDir;
	private PlatformProcess _wotaskd;
	private PlatformProcess _javaMonitor;
	private WireCapturingProxy _proxy;
	private WotaskdClient _wotaskdClient;
	private JavaMonitorAdminClient _javaMonitorClient;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "JavaMonitorAddFirstHost" );

	@BeforeAll
	void bootSubsystems() throws IOException, InterruptedException {
		_wotaskdConfigDir = Files.createTempDirectory( "sjip-systests-jmaddhost-wotaskd-" );
		_javaMonitorConfigDir = Files.createTempDirectory( "sjip-systests-jmaddhost-jm-" );

		final int wotaskdPort = freePort();
		_wotaskd = PlatformProcess.startWotaskd( wotaskdPort, _wotaskdConfigDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_wotaskdClient = new WotaskdClient( wotaskdPort );

		_proxy = WireCapturingProxy.start( "127.0.0.1", wotaskdPort );

		final int javaMonitorPort = freePort();
		_javaMonitor = PlatformProcess.startJavaMonitor( javaMonitorPort, _javaMonitorConfigDir, _proxy.port() );
		_javaMonitor.awaitReady( Duration.ofSeconds( 30 ) );
		_javaMonitorClient = new JavaMonitorAdminClient( javaMonitorPort );
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
	void addFirstHost_propagatesToWotaskd( TestReport report ) throws Exception {
		_javaMonitorClient.recordInto( report );
		// Filter out the lifebeat probes JavaMonitor sends constantly; only the
		// monitorRequest is part of this scenario.
		_proxy.recordInto( report, "/wa/monitorRequest" );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml (empty)", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Drive JavaMonitor's addHost direct action" );
		report.note( "JavaMonitor's `WOTaskdHandler.sendOverwriteToWotaskd` should push the new SiteConfig to wotaskd over the wire — captured by the proxy below." );
		report.action( "POST JavaMonitor /admin/addHost (name=" + HOST_NAME + ", hostType=" + HOST_TYPE + ")" );

		final HttpResponse<String> response = _javaMonitorClient.addHost( HOST_NAME, HOST_TYPE );

		assertEquals( 200, response.statusCode(), "addHost should return HTTP 200; body was: " + response.body() );
		assertEquals( "OK", response.body().trim(), "addHost response body should be 'OK'" );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after addHost", persisted );
		_snapshots.assertMatches( "wotaskd-SiteConfig-after-addFirstHost", "xml", persisted );

		// Lock in the wire envelopes JavaMonitor actually sent. JavaMonitor also emits
		// frequent lifebeat probes (path "/wlb"); those aren't part of this scenario, so
		// we filter to the monitorRequest path.
		final var monitorRequestExchanges = _proxy.exchanges().stream()
				.filter( e -> e.path().contains( "/wa/monitorRequest" ) )
				.toList();
		assertEquals( 1, monitorRequestExchanges.size(), "expected exactly one JavaMonitor→wotaskd monitorRequest for the overwrite" );
		final WireCapturingProxy.CapturedExchange overwriteExchange = monitorRequestExchanges.get( 0 );
		_snapshots.assertMatches( "javamonitor-to-wotaskd-overwrite-request", "xml", overwriteExchange.requestBody() );
		_snapshots.assertMatches( "javamonitor-to-wotaskd-overwrite-response", "xml", overwriteExchange.responseBody() );
	}

	@Test
	@Order( 2 )
	void querySite_afterAddHost_reportsTheNewHost( TestReport report ) throws Exception {
		_wotaskdClient.recordInto( report );

		report.note( "Confirms wotaskd's persisted state by querying it directly." );
		report.action( "POST monitorRequest{queryWotaskd: SITE} to wotaskd" );

		final WireExchange exchange = _wotaskdClient.querySite( null );

		assertEquals( 200, exchange.statusCode(), "queryWotaskd=SITE should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "wotaskd-querySite-afterAddHost-response", "xml", exchange.responseXml() );
	}

	private static int freePort() throws IOException {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
