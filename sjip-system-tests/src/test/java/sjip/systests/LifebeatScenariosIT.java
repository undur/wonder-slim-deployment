package sjip.systests;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
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

import sjip.systests.support.PlatformProcess;
import sjip.systests.support.SnapshotAsserter;
import sjip.systests.support.TestReport;
import sjip.systests.support.TestReportExtension;
import sjip.systests.support.WotaskdClient;
import sjip.systests.support.WotaskdClient.WireExchange;

/**
 * Captures the wire shape for lifebeat notifications sent by managed
 * instances to wotaskd.
 *
 * <p>The lifebeat protocol is HTTP GET with an ampersand-separated query
 * string (not key=value pairs):
 *
 * <pre>
 * GET /cgi-bin/WebObjects/wotaskd.woa/wlb?&lt;notification&gt;&amp;&lt;instanceName&gt;&amp;&lt;hostname&gt;&amp;&lt;port&gt;
 * </pre>
 *
 * <p>The {@code notification} is one of {@code hasStarted}, {@code lifebeat},
 * {@code willStop}, {@code willCrash}. Response semantics:
 * <ul>
 *   <li>{@code hasStarted} → HTTP 200</li>
 *   <li>{@code lifebeat} → HTTP 200, OR HTTP 500 if wotaskd has marked the instance for death</li>
 *   <li>{@code willStop} / {@code willCrash} → HTTP 200 with empty body</li>
 *   <li>Malformed (wrong number of params, unknown notification) → HTTP 400</li>
 * </ul>
 *
 * <p>What gets snapshotted: the response status code, response body, and the
 * exact URL we sent (so the URL format is documented in the report). State
 * changes that lifebeats trigger live on transient fields of {@code MInstance}
 * and aren't part of the persisted {@code SiteConfig.xml}; their effect shows
 * up in {@code queryWotaskd=INSTANCE} responses but the state values are
 * timing-dependent and would need careful normalization, so we don't lock
 * them in here.
 */
@TestInstance( Lifecycle.PER_CLASS )
@ExtendWith( TestReportExtension.class )
class LifebeatScenariosIT {

	private static final String HOST_NAME = "localhost";
	private static final String HOST_TYPE = "UNIX";
	private static final String APP_NAME = "DemoApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 12345;

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private final SnapshotAsserter _snapshots = new SnapshotAsserter( "LifebeatScenarios" );

	@BeforeAll
	void bootWotaskd_andSeedFullConfig() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-lifebeat-" );
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
	void hasStarted_returns200( TestReport report ) throws Exception {
		runLifebeat( report, "hasStarted" );
	}

	@Test
	void lifebeat_returns200( TestReport report ) throws Exception {
		runLifebeat( report, "lifebeat" );
	}

	@Test
	void willStop_returns200( TestReport report ) throws Exception {
		runLifebeat( report, "willStop" );
	}

	@Test
	void willCrash_returns200( TestReport report ) throws Exception {
		runLifebeat( report, "willCrash" );
	}

	@Test
	void malformed_unknownNotification_returns400( TestReport report ) throws Exception {
		final String url = "http://127.0.0.1:" + _wotaskd.port() + "/cgi-bin/WebObjects/wotaskd.woa/wlb?bogusNotification&" + APP_NAME + "&" + HOST_NAME + "&" + INSTANCE_PORT;

		report.heading( "Send a lifebeat with an unrecognised notification type" );
		report.note( "Wotaskd should reject unknown notification types. The exact wire shape: same four-field query string format, with an invalid value at position 0." );
		report.action( "GET " + redactPort( url ) );

		final RawResponse response = sendRawLifebeat( url );

		report.wireSend( "test → wotaskd (GET /wlb?bogusNotification&...)", "(empty body)" );
		report.wireReceive( "wotaskd → test (HTTP " + response.statusCode() + ")", response.body().isEmpty() ? "(empty body)" : response.body() );

		_snapshots.assertMatches( "malformed-unknownNotification-response-status", "txt",
				"HTTP " + response.statusCode() + "\nbody: " + (response.body().isEmpty() ? "(empty)" : response.body()) );
	}

	@Test
	void malformed_wrongFieldCount_returns400( TestReport report ) throws Exception {
		// Two fields instead of four — wotaskd's parser requires exactly four.
		final String url = "http://127.0.0.1:" + _wotaskd.port() + "/cgi-bin/WebObjects/wotaskd.woa/wlb?lifebeat&" + APP_NAME;

		report.heading( "Send a lifebeat with too few fields" );
		report.note( "Wotaskd's lifebeat parser requires exactly four ampersand-separated fields. Anything else is rejected." );
		report.action( "GET " + redactPort( url ) );

		final RawResponse response = sendRawLifebeat( url );

		report.wireSend( "test → wotaskd (GET /wlb?lifebeat&...)", "(empty body)" );
		report.wireReceive( "wotaskd → test (HTTP " + response.statusCode() + ")", response.body().isEmpty() ? "(empty body)" : response.body() );

		_snapshots.assertMatches( "malformed-wrongFieldCount-response-status", "txt",
				"HTTP " + response.statusCode() + "\nbody: " + (response.body().isEmpty() ? "(empty)" : response.body()) );
	}

	private void runLifebeat( final TestReport report, final String notification ) throws Exception {
		final String url = "http://127.0.0.1:" + _wotaskd.port() + "/cgi-bin/WebObjects/wotaskd.woa/wlb?" + notification + "&" + APP_NAME + "&" + HOST_NAME + "&" + INSTANCE_PORT;

		report.heading( "Send a " + notification + " lifebeat" );
		report.note( "URL format: /wlb?<notification>&<instanceName>&<hostname>&<port>. Ampersand-separated, not key=value." );
		report.action( "GET " + redactPort( url ) );

		final RawResponse response = sendRawLifebeat( url );

		report.wireSend( "test → wotaskd (GET /wlb?" + notification + "&...)", "(empty body)" );
		report.wireReceive( "wotaskd → test (HTTP " + response.statusCode() + ")", response.body().isEmpty() ? "(empty body)" : response.body() );

		assertEquals( 200, response.statusCode(), notification + " should return HTTP 200; got body: " + response.body() );

		_snapshots.assertMatches( notification + "-response-status", "txt",
				"HTTP " + response.statusCode() + "\nbody: " + (response.body().isEmpty() ? "(empty)" : response.body()) );
	}

	/**
	 * Replace the wotaskd port in URLs with a placeholder so reports are stable
	 * across runs (the OS assigns a different free port each time).
	 */
	private String redactPort( final String url ) {
		return url.replaceFirst( ":\\d+/", ":<port>/" );
	}

	private record RawResponse( int statusCode, String body ) {}

	/**
	 * Sends an HTTP/1.0 GET to wotaskd with {@code Connection: close}, then reads
	 * the entire response until the server closes the socket. This is more
	 * predictable than {@link java.net.http.HttpClient} for lifebeat-style
	 * requests where wotaskd's response is small and unframed (no
	 * {@code Content-Length}, no chunked encoding); the JDK HttpClient can hang
	 * for minutes waiting for body-end signals that never come.
	 */
	private RawResponse sendRawLifebeat( final String url ) throws IOException {
		final URI uri = URI.create( url );
		final String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
		final String host = uri.getHost();
		final int port = uri.getPort();

		try( final Socket socket = new Socket( host, port ) ) {
			socket.setSoTimeout( 5000 );

			final OutputStream out = socket.getOutputStream();
			// HTTP/1.1 (not 1.0) — wotaskd's lifebeat handler nulls out the response for
			// HTTP/1.0 requests (see DirectAction.java around line 132), which would prevent
			// us from observing the 400 for malformed requests. `Connection: close` tells the
			// server to close the socket after responding, which gives us a clean end-of-body
			// signal for responses that don't carry a Content-Length.
			out.write( ("GET " + pathAndQuery + " HTTP/1.1\r\nhost: " + host + ":" + port + "\r\nconnection: close\r\n\r\n").getBytes( StandardCharsets.UTF_8 ) );
			out.flush();

			final BufferedReader reader = new BufferedReader( new InputStreamReader( socket.getInputStream(), StandardCharsets.UTF_8 ) );

			final String statusLine = reader.readLine();
			if( statusLine == null ) {
				throw new IOException( "no response from wotaskd" );
			}
			final String[] statusParts = statusLine.split( " ", 3 );
			final int statusCode = Integer.parseInt( statusParts[1] );

			String line;
			while( (line = reader.readLine()) != null && !line.isEmpty() ) {
				// discard response headers
			}

			final StringBuilder body = new StringBuilder();
			while( (line = reader.readLine()) != null ) {
				if( body.length() > 0 ) {
					body.append( '\n' );
				}
				body.append( line );
			}

			return new RawResponse( statusCode, body.toString() );
		}
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
