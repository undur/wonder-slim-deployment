package x;

import java.net.http.HttpClient;

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
}
