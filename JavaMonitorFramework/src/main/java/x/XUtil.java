package x;

import java.net.http.HttpClient;

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
	public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();

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
	 * @return The first non-null value among the given candidates, or {@code null} if all are null.
	 */
	@SafeVarargs
	public static <E> E firstNonNull( E... candidates ) {
		for( E candidate : candidates ) {
			if( candidate != null ) {
				return candidate;
			}
		}
		return null;
	}
}
