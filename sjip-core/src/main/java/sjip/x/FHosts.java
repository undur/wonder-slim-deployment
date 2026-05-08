package sjip.x;

import java.net.InetAddress;

import com.webobjects.appserver._private.WOHostUtilities;

/**
 * Single facade for host-address checks. Delegates to {@link WOHostUtilities} today;
 * the shim shape means that backing layer can be swapped later without touching call
 * sites. Same pattern as {@link FProperties}.
 *
 * <p>The two methods exposed here are the ones the platform actually uses. Both decide
 * whether a given {@link InetAddress} is "local" — the same wotaskd host the call site
 * is running on. The distinction between them is subtle and worth knowing:
 *
 * <ul>
 *   <li>{@link #isLocalInetAddress} respects the {@code WOHost} property: if the operator
 *       has explicitly set a single host address for the application, this method does
 *       strict equality against that address only. If {@code WOHost} is unset, it falls
 *       back to checking the union of all local network interfaces.</li>
 *   <li>{@link #isAnyLocalInetAddress} always checks the union — every local interface
 *       <em>and</em> the configured {@code WOHost} address — regardless of whether
 *       {@code WOHost} was set.</li>
 * </ul>
 *
 * <p>Use {@code isLocalInetAddress} for security-shaped checks where the operator's
 * stated host policy should be honoured (e.g. lifebeat reception, model-side host
 * matching). Use {@code isAnyLocalInetAddress} for permissive routing decisions where
 * "request came from somewhere on this machine" is the relevant question (e.g. choosing
 * to include unregistered instances in a {@code woconfig} response).
 *
 * <p>The {@code initOnFailure} parameter controls whether to regenerate the cached list
 * of local interfaces on a miss. Pass {@code true} when a stale cache could cause a
 * legitimate-but-recent address to be rejected (network interfaces changing during
 * runtime); {@code false} when the call is hot and the cost of regeneration matters.
 */
public final class FHosts {

	private FHosts() {}

	/**
	 * @see WOHostUtilities#isLocalInetAddress(InetAddress, boolean)
	 */
	public static boolean isLocalInetAddress( final InetAddress address, final boolean initOnFailure ) {
		return WOHostUtilities.isLocalInetAddress( address, initOnFailure );
	}

	/**
	 * @see WOHostUtilities#isAnyLocalInetAddress(InetAddress, boolean)
	 */
	public static boolean isAnyLocalInetAddress( final InetAddress address, final boolean initOnFailure ) {
		return WOHostUtilities.isAnyLocalInetAddress( address, initOnFailure );
	}
}
