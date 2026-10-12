package sjip.systests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.sun.net.httpserver.HttpServer;

import sjip.systests.support.PlatformProcess;
import sjip.systests.support.WotaskdClient;
import sjip.systests.support.WotaskdClient.WireExchange;

/**
 * wotaskd identifies itself as {@code slim-wotaskd} in requests to instances. Applications may block HttpClient's
 * default User-Agent ({@code Java-http-client/<version>}) as bot traffic, which cuts wotaskd off from them.
 *
 * A plain HTTP server stands in for the instance, recording the User-Agent of every request it gets.
 */
@TestInstance( Lifecycle.PER_CLASS )
class UserAgentIT {

	private static final String HOST_NAME = "localhost";
	private static final String APP_NAME = "AgentApp";

	private Path _configDir;
	private PlatformProcess _wotaskd;
	private WotaskdClient _client;
	private HttpServer _instance;
	private int _port;
	private int _instancePort;

	/** "<path> <User-Agent>" for each request the instance received */
	private final List<String> _received = new CopyOnWriteArrayList<>();

	@BeforeAll
	void bootWotaskd_andStandInInstance() throws Exception {
		_instancePort = freePort();
		_instance = HttpServer.create( new InetSocketAddress( "127.0.0.1", _instancePort ), 0 );
		_instance.createContext( "/", exchange -> {
			_received.add( exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst( "User-Agent" ) );
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders( 404, -1 );
			exchange.close();
		} );
		_instance.start();

		_configDir = Files.createTempDirectory( "sjip-systests-useragent-" );
		_port = freePort();
		_wotaskd = PlatformProcess.startWotaskd( _port, _configDir );
		_wotaskd.awaitReady( Duration.ofSeconds( 30 ) );
		_client = new WotaskdClient( _port );

		sendAdd( "hostArray", Map.of( "name", HOST_NAME, "type", "UNIX" ) );
		sendAdd( "applicationArray", Map.of( "name", APP_NAME ) );
		sendAdd( "instanceArray", Map.of( "applicationName", APP_NAME, "id", 1, "hostName", HOST_NAME, "port", _instancePort ) );
	}

	@AfterAll
	void shutdown() {
		if( _wotaskd != null ) {
			_wotaskd.close();
		}

		if( _instance != null ) {
			_instance.stop( 0 );
		}
	}

	/**
	 * hasStarted sets off the type probe, and an instance query makes wotaskd ask the instance for its statistics
	 * (womp/instanceRequest) - both requests from wotaskd to the instance.
	 */
	@Test
	void requestsToInstances_carryOurUserAgent() throws Exception {
		final HttpClient http = HttpClient.newHttpClient();
		final HttpRequest hasStarted = HttpRequest.newBuilder( URI.create( "http://127.0.0.1:%d/cgi-bin/WebObjects/wotaskd.woa/wlb?hasStarted&%s&%s&%d".formatted( _port, APP_NAME, HOST_NAME, _instancePort ) ) ).build();
		assertEquals( 200, http.send( hasStarted, BodyHandlers.discarding() ).statusCode(), "hasStarted" );

		final WireExchange query = _client.sendMonitorRequest( Map.of( "queryWotaskd", "INSTANCE" ), null );
		assertEquals( 200, query.statusCode(), "instance query" );

		awaitRequest( "/womp/instanceRequest" );

		for( final String received : _received ) {
			assertTrue( received.endsWith( " slim-wotaskd" ), "request to the instance without our User-Agent: " + received );
		}
	}

	/**
	 * The type probe runs in the background, so the statistics query may arrive before or after it
	 */
	private void awaitRequest( final String pathSuffix ) throws InterruptedException {
		final long deadline = System.nanoTime() + Duration.ofSeconds( 10 ).toNanos();

		while( System.nanoTime() < deadline ) {
			if( _received.stream().anyMatch( r -> r.split( " " )[0].endsWith( pathSuffix ) ) ) {
				return;
			}
			Thread.sleep( 50 );
		}

		throw new AssertionError( "the instance never got a request ending in " + pathSuffix + ", got: " + _received );
	}

	private void sendAdd( final String arrayKey, final Map<String, Object> element ) throws Exception {
		final Map<String, Object> addDict = new LinkedHashMap<>();
		addDict.put( arrayKey, List.of( element ) );

		final Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put( "updateWotaskd", Map.of( "add", addDict ) );

		final WireExchange exchange = _client.sendMonitorRequest( requestBody, null );
		assertEquals( 200, exchange.statusCode(), "seed add " + arrayKey + " should return HTTP 200" );
	}

	private static int freePort() throws Exception {
		try( ServerSocket s = new ServerSocket( 0 ) ) {
			return s.getLocalPort();
		}
	}
}
