package sjip.systests.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client for JavaMonitor's {@code admin} direct-action endpoints — the programmatic
 * counterpart to JavaMonitor's UI for driving site-config operations from tests.
 *
 * <p>URLs look like {@code /cgi-bin/WebObjects/JavaMonitor.woa/admin/<action>} (the WO
 * dispatcher appends {@code "Action"} to the path segment when resolving the method,
 * so {@code admin/addHost} resolves to {@code AdminAction.addHostAction()}).
 */
public final class JavaMonitorAdminClient {

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.version( HttpClient.Version.HTTP_1_1 )
			.connectTimeout( Duration.ofSeconds( 5 ) )
			.build();

	private final int _port;
	private TestReport _report;

	public JavaMonitorAdminClient( final int port ) {
		_port = port;
	}

	/**
	 * Tells this client to record every subsequent exchange into the given report
	 * (as a paired {@code wireSend} / {@code wireReceive}). Pass {@code null} to stop.
	 */
	public void recordInto( final TestReport report ) {
		_report = report;
	}

	/**
	 * Invokes {@code addHostAction} on JavaMonitor with the given host name and type.
	 *
	 * @return the HTTP response — body should be {@code "OK"} on success
	 */
	public HttpResponse<String> addHost( final String hostName, final String hostType ) throws IOException, InterruptedException {
		final Map<String, String> form = new LinkedHashMap<>();
		form.put( "name", hostName );
		form.put( "hostType", hostType );
		return post( "addHost", form );
	}

	private HttpResponse<String> post( final String action, final Map<String, String> form ) throws IOException, InterruptedException {
		final String body = form.entrySet().stream()
				.map( e -> URLEncoder.encode( e.getKey(), StandardCharsets.UTF_8 ) + "=" + URLEncoder.encode( e.getValue(), StandardCharsets.UTF_8 ) )
				.collect( Collectors.joining( "&" ) );

		final HttpRequest request = HttpRequest.newBuilder()
				.uri( URI.create( "http://127.0.0.1:" + _port + "/cgi-bin/WebObjects/JavaMonitor.woa/admin/" + action ) )
				.timeout( Duration.ofSeconds( 30 ) )
				.header( "content-type", "application/x-www-form-urlencoded" )
				.POST( BodyPublishers.ofString( body, StandardCharsets.UTF_8 ) )
				.build();

		if( _report != null ) {
			_report.wireSend( "test → JavaMonitor (POST /admin/" + action + ")", body );
		}

		final HttpResponse<String> response = HTTP_CLIENT.send( request, BodyHandlers.ofString( StandardCharsets.UTF_8 ) );

		if( _report != null ) {
			_report.wireReceive( "JavaMonitor → test (HTTP " + response.statusCode() + ")", response.body() != null ? response.body() : "(empty body)" );
		}

		return response;
	}
}
