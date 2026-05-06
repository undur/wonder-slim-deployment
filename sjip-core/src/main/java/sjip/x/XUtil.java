package sjip.x;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;

/**
 * Catch-all utility holder for cross-cutting framework-side helpers that don't yet have
 * a more specific home. Items here are expected to migrate out as natural homes appear.
 */
public class XUtil {

	private XUtil() {}

	/**
	 * A single, shared {@link HttpClient} for all outbound HTTP traffic from the deployment
	 * tooling (JavaMonitor → wotaskd, wotaskd → application, JavaMonitor `/stats` fetches, …).
	 *
	 * <p>{@code HttpClient} is designed to be reused — its connection pool is keyed by
	 * destination internally, so one instance serves all hosts efficiently. Constructing a new
	 * one per request allocates a fresh selector, connection pool, and I/O thread group, and
	 * also forfeits HTTP keep-alive on the underlying TCP connections. The JDK Javadoc
	 * explicitly recommends sharing.
	 *
	 * <p>Per-request configuration (timeouts, headers, body) belongs on the {@link
	 * java.net.http.HttpRequest.Builder} at the call site, not on the client.
	 */
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();

	/**
	 * Sends {@code request} through the shared {@link HttpClient} and returns the response with
	 * its body decoded as UTF-8 text. All outbound HTTP traffic for the deployment tooling
	 * routes through here; this is the chokepoint for future request logging, recording, and
	 * tracing.
	 */
	public static HttpResponse<String> sendRequest( final HttpRequest request ) throws IOException, InterruptedException {
		return HTTP_CLIENT.send( request, BodyHandlers.ofString() );
	}

	/**
	 * Builds the canonical wotaskd-protocol error envelope as an in-memory dictionary,
	 * shaped:
	 * <pre>
	 * { rootKey: { errorResponse: [ message ] } }
	 * </pre>
	 *
	 * <p>{@code rootKey} is typically {@code "monitorResponse"} (JavaMonitor↔wotaskd) or
	 * {@code "instanceResponse"} (wotaskd↔WOApp). Used at sites where the response could
	 * not be parsed and downstream code expects an already-parsed dict it can treat as if
	 * parsing had succeeded.
	 */
	public static NSDictionary<String, Object> errorResponseDict( final String rootKey, final String message ) {
		final NSArray<Object> messages = new NSArray<>( message );
		final NSDictionary<String, Object> inner = new NSDictionary<>( messages, "errorResponse" );
		return new NSDictionary<>( inner, rootKey );
	}

	/**
	 * Builds the canonical wotaskd-protocol error envelope and returns it as wire-encoded XML.
	 *
	 * <p>Resulting XML has shape (rendered with {@code rootKey="monitorResponse"}):
	 * <pre>
	 * &lt;monitorResponse type="NSDictionary"&gt;
	 *   &lt;errorResponse type="NSArray"&gt;
	 *     &lt;element type="NSString"&gt;...message...&lt;/element&gt;
	 *   &lt;/errorResponse&gt;
	 * &lt;/monitorResponse&gt;
	 * </pre>
	 */
	public static String errorResponseXML( final String rootKey, final String message ) {
		final NSArray<Object> messages = new NSArray<>( message );
		final NSDictionary<String, Object> inner = new NSDictionary<>( messages, "errorResponse" );
		return new FoundationCoder().encodeRootObjectForKey( inner, rootKey );
	}

	/**
	 * FIXME: Eliminate. Each call site of this method needs to be checked beforehand // Hugi 2024-11-02
	 */
	@Deprecated
	public static boolean boolValue( final String s ) {

		if( s == null ) {
			return false;
		}

		return s.equalsIgnoreCase( "_YES" ) ||
				s.equalsIgnoreCase( "Y" ) ||
				s.equalsIgnoreCase( "YES" ) ||
				s.equalsIgnoreCase( "true" ) ||
				s.equalsIgnoreCase( "1" );
	}

	/**
	 * FIXME: Eliminate. Each call site of this method needs to be checked beforehand // Hugi 2024-11-02
	 */
	@Deprecated
	public static boolean isValidXMLString( final String s ) {

		if( s == null || s.isEmpty() ) {
			return false;
		}

		for( int i = 0; i < s.length(); i++ ) {
			char aChar = s.charAt( i );

			if( (!Character.isLetterOrDigit( aChar )) && (aChar != '-') && (aChar != '.') ) {
				return false;
			}
		}

		return true;
	}
}
