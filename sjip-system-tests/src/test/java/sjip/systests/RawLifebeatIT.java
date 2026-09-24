package sjip.systests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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

import sjip.systests.support.PlatformProcess;
import sjip.systests.support.WotaskdClient;
import sjip.systests.support.WotaskdClient.WireExchange;

/**
 * Lifebeats as WO instances send them: HTTP/1.1 without a Host header - no headers at all - several beats over one
 * connection. wotaskd runs on Jetty, whose HTTP parser rejects HTTP/1.1 without a Host header, so these beats are served
 * by the lifebeat detector ahead of HTTP (WOLifebeatDetector, WOLifebeatConnection) - a path the HTTP-client-based
 * scenarios in LifebeatScenariosIT never reach.
 *
 * The expected replies are the classic WO adaptor's, byte for byte, as captured from a classic wotaskd.
 */
@TestInstance( Lifecycle.PER_CLASS )
class RawLifebeatIT {

	private static final String HOST_NAME = "localhost";
	private static final String APP_NAME = "RawApp";
	private static final int INSTANCE_ID = 1;
	private static final int INSTANCE_PORT = 23456;

	private static final byte[] OK = ascii( "HTTP/1.1 200 Apple WebObjects\r\n\r\n\r\n" );
	private static final byte[] ACKNOWLEDGED = ascii( "HTTP/1.0 200 Apple WebObjects\r\n\r\n\r\n" );
	private static final byte[] BAD = ascii( "HTTP/1.0 400 Apple WebObjects\r\n\r\n\r\n" );
	private static final byte[] DIE = ascii( "HTTP/1.0 500 Apple WebObjects\r\n\r\n\r\n" );

	/** Every classic reply has the same length, so each beat's reply is read as exactly that many bytes */
	private static final int REPLY_LENGTH = OK.length;

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private int _port;

	@BeforeAll
	void bootWotaskd_andSeedConfig() throws Exception {
		_configDir = Files.createTempDirectory( "sjip-systests-rawlifebeat-" );
		_port = freePort();
		_wotaskd = PlatformProcess.startWotaskd( _port, _configDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_client = new WotaskdClient( _port );

		sendAdd( "hostArray", Map.of( "name", HOST_NAME, "type", "UNIX" ) );
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
	void beatsOnOnePersistentSocket_getClassicRepliesAligned() throws Exception {
		try( WOLifebeatSocket socket = new WOLifebeatSocket( _port ) ) {
			assertArrayEquals( OK, socket.beat( "hasStarted" ), "hasStarted" );

			for( int i = 0; i < 3; i++ ) {
				assertArrayEquals( OK, socket.beat( "lifebeat" ), "lifebeat " + i );
			}

			socket.assertNothingMoreToRead();
		}
	}

	@Test
	void willStop_getsEmptyAcknowledgement() throws Exception {
		try( WOLifebeatSocket socket = new WOLifebeatSocket( _port ) ) {
			assertArrayEquals( OK, socket.beat( "hasStarted" ), "hasStarted" );
			assertArrayEquals( ACKNOWLEDGED, socket.beat( "willStop" ), "willStop" );
		}
	}

	@Test
	void malformedBeat_getsBadRequest() throws Exception {
		try( WOLifebeatSocket socket = new WOLifebeatSocket( _port ) ) {
			assertArrayEquals( BAD, socket.send( "lifebeat&" + APP_NAME ), "beat with two of four fields" );
		}
	}

	@Test
	void instanceMarkedToDie_getsForceQuitOnNextBeat() throws Exception {
		try( WOLifebeatSocket socket = new WOLifebeatSocket( _port ) ) {
			assertArrayEquals( OK, socket.beat( "hasStarted" ), "hasStarted" );

			final List<Object> quit = new ArrayList<>();
			quit.add( "QUIT" );
			quit.add( Map.of( "hostName", HOST_NAME, "port", INSTANCE_PORT ) );
			final WireExchange exchange = _client.sendMonitorRequest( Map.of( "commandWotaskd", quit ), null );
			assertEquals( 200, exchange.statusCode(), "QUIT command" );

			assertArrayEquals( DIE, socket.beat( "lifebeat" ), "lifebeat after QUIT" );
		}
	}

	/**
	 * The detector must claim only header-less beats. A beat carrying headers (ng-objects' lifebeat thread sends Host) is
	 * ordinary HTTP and must get Jetty's ordinary response - not the classic adaptor's lifebeat reply.
	 */
	@Test
	void beatWithHeaders_takesTheOrdinaryHttpPath() throws Exception {
		try( Socket socket = new Socket( "127.0.0.1", _port ) ) {
			socket.setSoTimeout( 5000 );
			socket.getOutputStream().write( ascii( "GET /cgi-bin/WebObjects/wotaskd.woa/wlb?lifebeat&" + APP_NAME + "&" + HOST_NAME + "&" + INSTANCE_PORT + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n" ) );
			socket.getOutputStream().flush();

			final String head = readHead( socket.getInputStream() );
			assertTrue( head.startsWith( "HTTP/1.1 200 " ), "status line: " + head );
			assertTrue( head.toLowerCase().contains( "content-length: 0" ), "ordinary HTTP framing, got: " + head );
		}
	}

	/**
	 * Sends beats the way WO instances do: no headers, over one connection kept open across beats.
	 */
	private static final class WOLifebeatSocket implements AutoCloseable {

		private final Socket _socket;
		private final OutputStream _out;
		private final InputStream _in;

		WOLifebeatSocket( final int port ) throws IOException {
			_socket = new Socket( "127.0.0.1", port );
			_socket.setSoTimeout( 5000 );
			_out = _socket.getOutputStream();
			_in = _socket.getInputStream();
		}

		byte[] beat( final String notification ) throws IOException {
			return send( notification + "&" + APP_NAME + "&" + HOST_NAME + "&" + INSTANCE_PORT );
		}

		byte[] send( final String query ) throws IOException {
			_out.write( ascii( "GET /cgi-bin/WebObjects/wotaskd.woa/wlb?" + query + " HTTP/1.1\r\n\r\n" ) );
			_out.flush();
			return _in.readNBytes( REPLY_LENGTH );
		}

		/** Nothing beyond the classic replies may arrive on the connection */
		void assertNothingMoreToRead() throws IOException {
			_socket.setSoTimeout( 500 );
			assertThrows( SocketTimeoutException.class, _in::read, "bytes left on the socket after the last reply" );
		}

		@Override
		public void close() throws IOException {
			_socket.close();
		}
	}

	private void sendAdd( final String arrayKey, final Map<String, Object> element ) throws Exception {
		final Map<String, Object> addDict = new LinkedHashMap<>();
		addDict.put( arrayKey, List.of( element ) );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", Map.of( "add", addDict ) );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );
		assertEquals( 200, exchange.statusCode(), "seed add " + arrayKey + " should return HTTP 200" );
	}

	private static String readHead( final InputStream in ) throws IOException {
		final StringBuilder sb = new StringBuilder();

		while( !sb.toString().endsWith( "\r\n\r\n" ) ) {
			final int b = in.read();

			if( b < 0 ) {
				break;
			}

			sb.append( (char)b );
		}

		return sb.toString();
	}

	private static byte[] ascii( final String string ) {
		return string.getBytes( StandardCharsets.US_ASCII );
	}

	private static int freePort() throws IOException {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
