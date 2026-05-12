package sjip.systests;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sjip.systests.support.PlatformProcess;
import sjip.systests.support.SnapshotAsserter;
import sjip.systests.support.TestReport;
import sjip.systests.support.TestReportExtension;
import sjip.systests.support.WotaskdClient;
import sjip.systests.support.WotaskdClient.WireExchange;

/**
 * Smoke test for wotaskd's bootstrap path. Starts wotaskd against an empty config
 * directory, verifies it boots cleanly, and that querying it returns an empty
 * site config. Establishes the baseline test infrastructure with minimal scenario
 * complexity.
 */
@TestInstance( Lifecycle.PER_CLASS )
@ExtendWith( TestReportExtension.class )
class WotaskdBootSmokeIT {

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "WotaskdBootSmoke" );

	@BeforeAll
	void bootWotaskd() throws IOException, InterruptedException {
		_configDir = Files.createTempDirectory( "sjip-systests-wotaskd-" );
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
	void wotaskdBoots_andIsAlive( TestReport report ) {
		report.note( "Verifies wotaskd subprocess is alive after `awaitReady` returns." );
		report.action( "Check `_wotaskd.isAlive()`" );
		assertTrue( _wotaskd.isAlive(), "wotaskd should be running after awaitReady" );
	}

	@Test
	void wotaskdBoots_createsEmptySiteConfigFile( TestReport report ) throws IOException {
		final Path siteConfig = _wotaskd.siteConfigFile();
		report.note( "On startup, wotaskd writes a SiteConfig.xml to its configured directory even if no config exists yet." );
		report.action( "Read wotaskd's SiteConfig.xml from its config directory" );
		assertTrue( Files.exists( siteConfig ), "SiteConfig.xml should be created on wotaskd startup at " + siteConfig );
		report.state( "wotaskd SiteConfig.xml after boot", Files.readString( siteConfig, StandardCharsets.UTF_8 ) );
	}

	@Test
	void querySite_returnsEmptyConfig( TestReport report ) throws Exception {
		_client.recordInto( report );
		report.note( "Sends a `queryWotaskd=SITE` to the freshly-booted wotaskd and expects an empty SiteConfig back." );
		report.action( "POST monitorRequest{queryWotaskd: SITE} to wotaskd" );

		final WireExchange exchange = _client.querySite( null );

		assertEquals( 200, exchange.statusCode(), "queryWotaskd=SITE should return HTTP 200" );
		assertNotNull( exchange.responseDict(), "decoded response should not be null" );

		_snapshots.assertMatches( "querySite-emptyConfig-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "querySite-emptyConfig-response", "xml", exchange.responseXml() );
	}

	/**
	 * @return an OS-assigned free TCP port that's known to be available at this instant
	 *         (race-prone for the period between this call and the subprocess binding;
	 *         in practice fine for serial tests).
	 */
	private static int freePort() throws IOException {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
