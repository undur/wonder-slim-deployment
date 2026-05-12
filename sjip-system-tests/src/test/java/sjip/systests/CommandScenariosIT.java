package sjip.systests;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * Captures the wire shapes for {@code commandWotaskd} — the wotaskd-side
 * verb for telling specific instances to start, stop, refuse, accept, quit,
 * or clear their death count.
 *
 * <p>The wire shape is unusual: {@code commandWotaskd} is an {@code NSArray}
 * (not a dict like the {@code updateWotaskd}/{@code queryWotaskd} envelopes).
 * Index 0 is the command name; subsequent indices are instance identifiers
 * ({@code {hostName, port}} dicts).
 *
 * <p>The seeded instance is hosted at a numeric IP ({@code 1.2.3.4}) that's
 * deliberately non-local, so each command takes wotaskd's "non-local instance"
 * branch which short-circuits to a success element without trying to actually
 * manipulate a process. That gives us a stable, deterministic capture of the
 * success-response shape for all six commands.
 *
 * <p>Tests for error-response shapes (instance not found, command rejected,
 * etc.) are future work — the error shape includes the local machine's
 * hostname, which would need normalization to be CI-stable.
 */
@TestInstance( Lifecycle.PER_CLASS )
@ExtendWith( TestReportExtension.class )
class CommandScenariosIT {

	private static final String HOST_NAME = "1.2.3.4"; // numeric IP — parses without DNS, non-local
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 12345;

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "CommandScenarios" );

	@BeforeAll
	void bootWotaskd_andSeedFullConfig() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-command-" );
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
	void start_command_captures_wireShape( TestReport report ) throws Exception {
		runCommand( report, "START" );
	}

	@Test
	void stop_command_captures_wireShape( TestReport report ) throws Exception {
		runCommand( report, "STOP" );
	}

	@Test
	void refuse_command_captures_wireShape( TestReport report ) throws Exception {
		runCommand( report, "REFUSE" );
	}

	@Test
	void accept_command_captures_wireShape( TestReport report ) throws Exception {
		runCommand( report, "ACCEPT" );
	}

	@Test
	void quit_command_captures_wireShape( TestReport report ) throws Exception {
		runCommand( report, "QUIT" );
	}

	@Test
	void clear_command_captures_wireShape( TestReport report ) throws Exception {
		runCommand( report, "CLEAR" );
	}

	private void runCommand( final TestReport report, final String command ) throws Exception {
		_client.recordInto( report );

		report.heading( "Initial state" );
		report.state( "wotaskd SiteConfig.xml", Files.readString( _wotaskd.siteConfigFile(), StandardCharsets.UTF_8 ) );

		report.heading( "Send " + command + " to the instance" );
		report.action( "POST monitorRequest{commandWotaskd: [" + command + ", {hostName, port}]} to wotaskd" );

		final List<Object> commandArray = new ArrayList<>();
		commandArray.add( command );
		commandArray.add( Map.of( "hostName", HOST_NAME, "port", INSTANCE_PORT ) );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "commandWotaskd", commandArray );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );

		assertEquals( 200, exchange.statusCode() );
		assertNotNull( exchange.responseDict() );

		_snapshots.assertMatches( command.toLowerCase() + "-request", "xml", exchange.requestXml() );
		_snapshots.assertMatches( command.toLowerCase() + "-response", "xml", exchange.responseXml() );
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
