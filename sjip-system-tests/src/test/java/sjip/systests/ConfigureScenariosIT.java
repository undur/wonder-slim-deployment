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
 * Captures the wire shapes for {@code updateWotaskd → configure → {site,
 * hostArray, applicationArray, instanceArray}}. Each sub-shape replaces values
 * on an existing entry (or the site-wide settings dict).
 *
 * <p>{@code applicationArray} carries an optional {@code oldname} key for
 * renames; {@code instanceArray} carries an optional {@code oldport} for
 * port-changes. Both rename paths are covered here because they're the most
 * likely shapes to drift during the upcoming domain refactor.
 *
 * <p>This is the largest single test class in the inventory — four sub-shapes
 * plus two rename variants — but each test is small and isolated to one
 * configure variant.
 */
@TestInstance( Lifecycle.PER_CLASS )
@TestMethodOrder( OrderAnnotation.class )
@ExtendWith( TestReportExtension.class )
class ConfigureScenariosIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 12345;

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "ConfigureScenarios" );

	@BeforeAll
	void bootWotaskd_andSeedFullConfig() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-configure-" );
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
	@Order( 1 )
	void configureSite_updatesSiteWideSettings( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Configure the site dict" );
		report.note( "The `site` sub-shape touches site-wide knobs — viewRefresh settings, password, etc. We send a minimal change here: just a new viewRefreshRate." );
		report.action( "POST monitorRequest{updateWotaskd: {configure: {site: {viewRefreshRate: 30}}}} to wotaskd" );

		final Map<String, Object> siteDict = new LinkedHashMap<>();
		siteDict.put( "viewRefreshRate", 30 );

		final Map<String, Object> configureDict = new LinkedHashMap<>();
		configureDict.put( "site", siteDict );

		final WireExchange exchange = sendConfigure( configureDict );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "configureSite-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "configureSite-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after configureSite", persisted );
		_snapshots.assertMatches( "configureSite-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 2 )
	void configureHost_updatesHostFields( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Configure the host" );
		report.note( "Configuring a host replaces the host's values dict wholesale — the full intended state goes in the envelope. We change `type` here from UNIX to MACOSX." );
		report.action( "POST monitorRequest{updateWotaskd: {configure: {hostArray: [{name, type}]}}} to wotaskd" );

		final Map<String, Object> hostDict = new LinkedHashMap<>();
		hostDict.put( "name", HOST_NAME );
		hostDict.put( "type", "MACOSX" );

		final Map<String, Object> configureDict = new LinkedHashMap<>();
		configureDict.put( "hostArray", List.of( hostDict ) );

		final WireExchange exchange = sendConfigure( configureDict );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "configureHost-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "configureHost-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after configureHost", persisted );
		_snapshots.assertMatches( "configureHost-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 3 )
	void configureApplication_updatesAppFields( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Configure the application (no rename)" );
		report.action( "POST monitorRequest{updateWotaskd: {configure: {applicationArray: [{name, autoRecover: YES}]}}} to wotaskd" );

		final Map<String, Object> appDict = new LinkedHashMap<>();
		appDict.put( "name", APP_NAME );
		appDict.put( "autoRecover", Boolean.TRUE );

		final Map<String, Object> configureDict = new LinkedHashMap<>();
		configureDict.put( "applicationArray", List.of( appDict ) );

		final WireExchange exchange = sendConfigure( configureDict );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "configureApplication-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "configureApplication-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after configureApplication", persisted );
		_snapshots.assertMatches( "configureApplication-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 4 )
	void configureApplication_withRename_usesOldName( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state (application still named " + APP_NAME + ")" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Rename the application" );
		report.note( "When the new `name` doesn't match an existing application, wotaskd falls back to looking up by `oldname`. The wire envelope carries both keys." );
		report.action( "POST monitorRequest{updateWotaskd: {configure: {applicationArray: [{name: RenamedApp, oldname: DemoApp}]}}} to wotaskd" );

		final Map<String, Object> appDict = new LinkedHashMap<>();
		appDict.put( "name", "RenamedApp" );
		appDict.put( "oldname", APP_NAME );

		final Map<String, Object> configureDict = new LinkedHashMap<>();
		configureDict.put( "applicationArray", List.of( appDict ) );

		final WireExchange exchange = sendConfigure( configureDict );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "configureApplication-rename-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "configureApplication-rename-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after rename", persisted );
		_snapshots.assertMatches( "configureApplication-rename-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 5 )
	void configureInstance_updatesInstanceFields( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Configure the instance (no port change)" );
		report.action( "POST monitorRequest{updateWotaskd: {configure: {instanceArray: [{hostName, port, scheduled: YES}]}}} to wotaskd" );

		final Map<String, Object> instDict = new LinkedHashMap<>();
		instDict.put( "applicationName", "RenamedApp" );
		instDict.put( "id", INSTANCE_ID );
		instDict.put( "hostName", HOST_NAME );
		instDict.put( "port", INSTANCE_PORT );
		instDict.put( "scheduled", Boolean.TRUE );

		final Map<String, Object> configureDict = new LinkedHashMap<>();
		configureDict.put( "instanceArray", List.of( instDict ) );

		final WireExchange exchange = sendConfigure( configureDict );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "configureInstance-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "configureInstance-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after configureInstance", persisted );
		_snapshots.assertMatches( "configureInstance-SiteConfig", "xml", persisted );
	}

	@Test
	@Order( 6 )
	void configureInstance_withPortChange_usesOldPort( TestReport report ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state (instance still on port " + INSTANCE_PORT + ")" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		final int NEW_PORT = 54321;

		report.heading( "Change the instance's port" );
		report.note( "When the new port doesn't match an existing instance, wotaskd falls back to looking up by `oldport`. The wire envelope carries both keys." );
		report.action( "POST monitorRequest{updateWotaskd: {configure: {instanceArray: [{hostName, port: " + NEW_PORT + ", oldport: " + INSTANCE_PORT + "}]}}} to wotaskd" );

		final Map<String, Object> instDict = new LinkedHashMap<>();
		instDict.put( "applicationName", "RenamedApp" );
		instDict.put( "id", INSTANCE_ID );
		instDict.put( "hostName", HOST_NAME );
		instDict.put( "port", NEW_PORT );
		instDict.put( "oldport", INSTANCE_PORT );

		final Map<String, Object> configureDict = new LinkedHashMap<>();
		configureDict.put( "instanceArray", List.of( instDict ) );

		final WireExchange exchange = sendConfigure( configureDict );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( "configureInstance-portchange-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( "configureInstance-portchange-response", "xml", exchange.responseXml() );

		report.heading( "Resulting state" );
		final String persisted = Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 );
		report.state( "wotaskd SiteConfig.xml after port change", persisted );
		_snapshots.assertMatches( "configureInstance-portchange-SiteConfig", "xml", persisted );
	}

	private WireExchange sendConfigure( final Map<String, Object> configureDict ) throws Exception {
		final Map<String, Object> updateDict = new LinkedHashMap<>();
		updateDict.put( "configure", configureDict );

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
