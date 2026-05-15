package sjip.systests.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import sjip.core.x.FoundationCoder;

/**
 * Wire-protocol client for talking to a running wotaskd. Constructs the XML
 * envelopes JavaMonitor would send, POSTs them, and captures both request
 * and response bodies for snapshot assertions.
 *
 * <p>The relevant endpoints (WO dispatch appends {@code "Action"} to the
 * URL path segment when resolving the method, so the URLs use the unsuffixed name):
 * <ul>
 *   <li>{@code /cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest} — the
 *       admin channel. Receives {@code monitorRequest} XML envelopes.</li>
 *   <li>{@code /cgi-bin/WebObjects/wotaskd.woa/wa/default} — the
 *       human-readable status page (mostly for readiness probes).</li>
 * </ul>
 */
public final class WotaskdClient {

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.version( HttpClient.Version.HTTP_1_1 )
			.connectTimeout( Duration.ofSeconds( 5 ) )
			.build();

	private final int _port;
	private TestReport _report;

	public WotaskdClient( final int port ) {
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
	 * Sends a {@code monitorRequest} to wotaskd. The {@code requestBody} dict is wrapped
	 * by {@link FoundationCoder} under the {@code "monitorRequest"} root key, posted, and
	 * the response body parsed back via {@link FoundationCoder} into a dict.
	 *
	 * @return a result holding both the raw request/response XML strings (for snapshot
	 *         assertions) and the decoded response dict (for runtime checks).
	 */
	public WireExchange sendMonitorRequest( final Map<String, Object> requestBody, final String password ) throws IOException, InterruptedException {
		final String requestXml = new FoundationCoder().encodeRootObjectForKey( requestBody, "monitorRequest" );

		final HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri( URI.create( "http://127.0.0.1:" + _port + "/cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest" ) )
				.timeout( Duration.ofSeconds( 30 ) )
				.header( "content-type", "text/xml" )
				.POST( BodyPublishers.ofString( requestXml, StandardCharsets.UTF_8 ) );

		if( password != null ) {
			builder.header( "password", password );
		}

		if( _report != null ) {
			_report.wireSend( "test → wotaskd (POST /wa/monitorRequest)", requestXml );
		}

		final HttpResponse<String> response = HTTP_CLIENT.send( builder.build(), BodyHandlers.ofString( StandardCharsets.UTF_8 ) );
		final String responseXml = response.body();

		if( _report != null ) {
			_report.wireReceive( "wotaskd → test (HTTP " + response.statusCode() + ")", responseXml != null ? responseXml : "(empty body)" );
		}

		final Map<String, Object> responseDict;
		if( responseXml != null && !responseXml.isEmpty() ) {
			try {
				@SuppressWarnings("unchecked")
				final Map<String, Object> decoded = (Map<String, Object>) new FoundationCoder().decodeRootObjectFromString( responseXml );
				responseDict = decoded;
			}
			catch( final Exception e ) {
				throw new IOException( "Failed to decode wotaskd response: " + e + "\nBody was: " + responseXml, e );
			}
		}
		else {
			responseDict = null;
		}

		return new WireExchange( requestXml, response.statusCode(), responseXml, responseDict );
	}

	/**
	 * Convenience for the common {@code queryWotaskd=SITE} call — returns the full
	 * site config as wotaskd currently sees it.
	 */
	public WireExchange querySite( final String password ) throws IOException, InterruptedException {
		final Map<String, Object> body = new HashMap<>();
		body.put( "queryWotaskd", "SITE" );
		return sendMonitorRequest( body, password );
	}

	/**
	 * A captured request/response pair plus the decoded response dict.
	 *
	 * @param requestXml    the XML body posted to wotaskd
	 * @param statusCode    the HTTP status code from wotaskd
	 * @param responseXml   the XML body wotaskd returned
	 * @param responseDict  the decoded response dict, or {@code null} if the body was empty
	 */
	public record WireExchange(
			String requestXml,
			int statusCode,
			String responseXml,
			Map<String, Object> responseDict ) {}
}
