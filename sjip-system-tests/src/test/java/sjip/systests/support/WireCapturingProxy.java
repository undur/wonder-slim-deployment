package sjip.systests.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * In-process HTTP forward proxy for capturing the JavaMonitor↔wotaskd wire. Listens on a
 * local port, captures the request and response bodies for every exchange, and forwards
 * to the configured upstream. JavaMonitor talks to this proxy (via
 * {@code -WOLifebeatDestinationPort <proxy-port>}) instead of directly to wotaskd, so the
 * test JVM observes every byte that crosses the wire.
 *
 * <p>Each captured exchange is appended to {@link #exchanges()} and (optionally) recorded
 * as a {@code wireSend} / {@code wireReceive} pair on the active {@link TestReport}.
 *
 * <p>Implementation deliberately small — only the bits the existing tests use are
 * supported (POSTs and GETs, single Content-Type per direction, no chunked transfer).
 */
public final class WireCapturingProxy implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger( WireCapturingProxy.class );

	public record CapturedExchange(
			String method,
			String path,
			String requestBody,
			int statusCode,
			String responseBody ) {}

	private final HttpServer _server;
	private final HttpClient _upstreamClient;
	private final String _upstreamBase;
	private final List<CapturedExchange> _exchanges = new CopyOnWriteArrayList<>();
	private final AtomicReference<TestReport> _report = new AtomicReference<>();
	private final AtomicReference<String> _reportPathFilter = new AtomicReference<>();

	private WireCapturingProxy( final HttpServer server, final HttpClient upstreamClient, final String upstreamBase ) {
		_server = server;
		_upstreamClient = upstreamClient;
		_upstreamBase = upstreamBase;
	}

	/**
	 * Starts a proxy listening on a free local port and forwarding every request to the
	 * given upstream host:port. The proxy is fully started when this method returns.
	 */
	public static WireCapturingProxy start( final String upstreamHost, final int upstreamPort ) throws IOException {
		final HttpServer server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		final HttpClient upstream = HttpClient.newBuilder()
				.version( HttpClient.Version.HTTP_1_1 )
				.connectTimeout( Duration.ofSeconds( 5 ) )
				.build();
		final String upstreamBase = "http://" + upstreamHost + ":" + upstreamPort;

		final WireCapturingProxy proxy = new WireCapturingProxy( server, upstream, upstreamBase );
		server.createContext( "/", proxy::handle );
		// A real thread pool, not the default single-thread executor, so lifebeat traffic
		// from JavaMonitor doesn't block the addHost POST that's the actual scenario.
		server.setExecutor( Executors.newCachedThreadPool( r -> {
			final Thread t = new Thread( r, "wire-capturing-proxy" );
			t.setDaemon( true );
			return t;
		} ) );
		server.start();
		return proxy;
	}

	/** The port the proxy is listening on. */
	public int port() {
		return _server.getAddress().getPort();
	}

	/**
	 * Tells the proxy to record subsequent exchanges into the given report (as a paired
	 * {@code wireSend} / {@code wireReceive}). When {@code pathSubstring} is non-null,
	 * only exchanges whose request path contains the substring are recorded — useful for
	 * filtering out the lifebeat traffic that JavaMonitor emits constantly. Pass
	 * {@code null} for {@code report} to stop recording.
	 */
	public void recordInto( final TestReport report, final String pathSubstring ) {
		_report.set( report );
		_reportPathFilter.set( pathSubstring );
	}

	/** Captured exchanges in arrival order. */
	public List<CapturedExchange> exchanges() {
		return List.copyOf( _exchanges );
	}

	@Override
	public void close() {
		_server.stop( 0 );
	}

	private void handle( final HttpExchange exchange ) throws IOException {
		try {
			final String method = exchange.getRequestMethod();
			final String path = exchange.getRequestURI().toString();
			logger.debug( "proxy received {} {}", method, path );
			final byte[] requestBodyBytes = exchange.getRequestBody().readAllBytes();
			final String requestBody = new String( requestBodyBytes, StandardCharsets.UTF_8 );

			final HttpRequest.Builder upstreamBuilder = HttpRequest.newBuilder()
					.uri( URI.create( _upstreamBase + path ) )
					.timeout( Duration.ofSeconds( 30 ) );

			exchange.getRequestHeaders().forEach( ( name, values ) -> {
				// Skip hop-by-hop and Java HttpClient-restricted headers.
				if( isRestrictedHeader( name ) ) {
					return;
				}
				for( final String value : values ) {
					upstreamBuilder.header( name, value );
				}
			} );

			if( "GET".equalsIgnoreCase( method ) ) {
				upstreamBuilder.GET();
			}
			else {
				upstreamBuilder.method( method, HttpRequest.BodyPublishers.ofByteArray( requestBodyBytes ) );
			}

			final HttpResponse<byte[]> upstreamResponse = _upstreamClient.send( upstreamBuilder.build(), BodyHandlers.ofByteArray() );
			final byte[] responseBodyBytes = upstreamResponse.body() != null ? upstreamResponse.body() : new byte[0];
			final String responseBody = new String( responseBodyBytes, StandardCharsets.UTF_8 );
			logger.debug( "proxy got upstream response HTTP {} ({} bytes), forwarding back", upstreamResponse.statusCode(), responseBodyBytes.length );

			final CapturedExchange captured = new CapturedExchange( method, path, requestBody, upstreamResponse.statusCode(), responseBody );
			_exchanges.add( captured );

			final TestReport report = _report.get();
			final String filter = _reportPathFilter.get();
			if( report != null && (filter == null || path.contains( filter )) ) {
				report.wireSend( "JavaMonitor → wotaskd (" + method + " " + path + ")", requestBody.isEmpty() ? "(empty body)" : requestBody );
				report.wireReceive( "wotaskd → JavaMonitor (HTTP " + upstreamResponse.statusCode() + ")", responseBody.isEmpty() ? "(empty body)" : responseBody );
			}

			upstreamResponse.headers().map().forEach( ( name, values ) -> {
				if( isRestrictedResponseHeader( name ) ) {
					return;
				}
				for( final String value : values ) {
					exchange.getResponseHeaders().add( name, value );
				}
			} );

			exchange.sendResponseHeaders( upstreamResponse.statusCode(), responseBodyBytes.length == 0 ? -1 : responseBodyBytes.length );
			if( responseBodyBytes.length > 0 ) {
				exchange.getResponseBody().write( responseBodyBytes );
			}
		}
		catch( final InterruptedException e ) {
			Thread.currentThread().interrupt();
			logger.warn( "proxy interrupted", e );
			exchange.sendResponseHeaders( 502, -1 );
		}
		catch( final Exception e ) {
			logger.warn( "proxy forwarding failed", e );
			exchange.sendResponseHeaders( 502, -1 );
		}
		finally {
			exchange.close();
		}
	}

	private static boolean isRestrictedHeader( final String name ) {
		final String n = name.toLowerCase();
		// HttpClient forbids setting these on requests; let it manage them.
		return n.equals( "connection" ) || n.equals( "content-length" ) || n.equals( "expect" ) || n.equals( "host" ) || n.equals( "upgrade" );
	}

	private static boolean isRestrictedResponseHeader( final String name ) {
		final String n = name.toLowerCase();
		return n.equals( "transfer-encoding" ) || n.equals( "content-length" );
	}
}
