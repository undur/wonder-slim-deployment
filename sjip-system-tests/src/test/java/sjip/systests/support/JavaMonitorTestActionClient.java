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
 * Client for JavaMonitor's {@code TestDirectAction} endpoints. These are the
 * programmatic entry points that exercise JavaMonitor's wire-construction code
 * paths (the {@code WOTaskdHandler.send*} methods) end-to-end against a real
 * wotaskd.
 *
 * <p>URLs look like {@code /cgi-bin/WebObjects/JavaMonitor.woa/test/<action>} —
 * the default direct-action handler. Every endpoint requires the JVM property
 * {@code sjip.testDirectActions.enabled=true} to be set, which the system-test
 * harness does via {@link PlatformProcess#startJavaMonitor}.
 */
public final class JavaMonitorTestActionClient {

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.version( HttpClient.Version.HTTP_1_1 )
			.connectTimeout( Duration.ofSeconds( 5 ) )
			.build();

	private final int _port;
	private TestReport _report;

	public JavaMonitorTestActionClient( final int port ) {
		_port = port;
	}

	public void recordInto( final TestReport report ) {
		_report = report;
	}

	public HttpResponse<String> addHost( final String name, final String hostType ) throws IOException, InterruptedException {
		return post( "addHost", Map.of( "name", name, "hostType", hostType ) );
	}

	public HttpResponse<String> removeHost( final String name ) throws IOException, InterruptedException {
		return post( "removeHost", Map.of( "name", name ) );
	}

	public HttpResponse<String> addApplication( final String name ) throws IOException, InterruptedException {
		return post( "addApplication", Map.of( "name", name ) );
	}

	public HttpResponse<String> removeApplication( final String name ) throws IOException, InterruptedException {
		return post( "removeApplication", Map.of( "name", name ) );
	}

	/**
	 * Reconfigures an existing application. {@code name} identifies the application;
	 * {@code newName} (optional) renames it; {@code autoRecover} (optional, "true"/"false")
	 * toggles auto-recover. The form value names mirror the JavaMonitor field names.
	 */
	public HttpResponse<String> configureApplication( final String name, final Map<String, String> changes ) throws IOException, InterruptedException {
		final Map<String, String> form = new LinkedHashMap<>();
		form.put( "name", name );
		form.putAll( changes );
		return post( "configureApplication", form );
	}

	public HttpResponse<String> addInstance( final String applicationName, final String hostName, final int id, final int port ) throws IOException, InterruptedException {
		return post( "addInstance", Map.of(
				"applicationName", applicationName,
				"hostName", hostName,
				"id", String.valueOf( id ),
				"port", String.valueOf( port ) ) );
	}

	public HttpResponse<String> removeInstance( final String hostName, final int port ) throws IOException, InterruptedException {
		return post( "removeInstance", Map.of(
				"hostName", hostName,
				"port", String.valueOf( port ) ) );
	}

	public HttpResponse<String> configureInstance( final String hostName, final int port, final Map<String, String> changes ) throws IOException, InterruptedException {
		final Map<String, String> form = new LinkedHashMap<>();
		form.put( "hostName", hostName );
		form.put( "port", String.valueOf( port ) );
		form.putAll( changes );
		return post( "configureInstance", form );
	}

	private HttpResponse<String> post( final String action, final Map<String, String> form ) throws IOException, InterruptedException {
		final String body = form.entrySet().stream()
				.map( e -> URLEncoder.encode( e.getKey(), StandardCharsets.UTF_8 ) + "=" + URLEncoder.encode( e.getValue(), StandardCharsets.UTF_8 ) )
				.collect( Collectors.joining( "&" ) );

		final HttpRequest request = HttpRequest.newBuilder()
				.uri( URI.create( "http://127.0.0.1:" + _port + "/cgi-bin/WebObjects/JavaMonitor.woa/test/" + action ) )
				.timeout( Duration.ofSeconds( 30 ) )
				.header( "content-type", "application/x-www-form-urlencoded" )
				.POST( BodyPublishers.ofString( body, StandardCharsets.UTF_8 ) )
				.build();

		if( _report != null ) {
			_report.wireSend( "test → JavaMonitor (POST /test/" + action + ")", body );
		}

		final HttpResponse<String> response = HTTP_CLIENT.send( request, BodyHandlers.ofString( StandardCharsets.UTF_8 ) );

		if( _report != null ) {
			_report.wireReceive( "JavaMonitor → test (HTTP " + response.statusCode() + ")", response.body() != null ? response.body() : "(empty body)" );
		}

		return response;
	}
}
