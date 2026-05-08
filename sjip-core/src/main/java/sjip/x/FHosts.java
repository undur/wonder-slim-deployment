package sjip.x;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host-address checks. Lets the platform decide whether a given {@link InetAddress}
 * is "local" — the same machine the call site is running on — and whether an HTTP
 * request arrived through a web-server adaptor (mod_WebObjects, modulo, etc.)
 * versus a direct connection.
 *
 * <p>Replaces our former dependency on WO's {@code WOHostUtilities} and the
 * header inspection inside {@code WORequest}. Logic is reproduced rather than
 * delegated so {@code sjip-core} stays free of WO appserver internals.
 *
 * <h2>Local-address determination</h2>
 *
 * <p>{@link #isLocalInetAddress} respects the operator's stated host policy: if
 * {@code WOHost} is set and resolves to a valid address, only that single address
 * is treated as local. If {@code WOHost} is unset or unresolvable, falls back to
 * the union of all addresses bound to up network interfaces, plus the loopback
 * aliases ({@code 127.0.0.1}, {@code localhost}, {@code ::1}), plus any addresses
 * specified by the {@code er.extensions.WOHostUtilities.localhostips} property,
 * plus DNS aliases of all of the above.
 *
 * <p>{@link #isAnyLocalInetAddress} always checks the union — every local-interface
 * address <em>and</em> the configured {@code WOHost} — regardless of the policy.
 *
 * <p>Use the strict variant for security-shaped checks (lifebeat reception, model
 * host matching). Use the union variant for permissive routing decisions ("does
 * this request look like it came from the same machine?").
 *
 * <p>The cached local-address set is built once at class load and rebuilt on a miss
 * when {@code refreshOnMiss} is true — that handles network interfaces being
 * added/removed during runtime (DHCP renewals, VPN changes). Pass {@code false}
 * for hot calls where the regenerate cost matters.
 *
 * <h2>Web-server detection</h2>
 *
 * <p>{@link #isUsingWebServer(String)} mirrors WO's {@code WORequest.isUsingWebServer}:
 * a request is "via web server" iff the {@code x-webobjects-adaptor-version} header
 * is present with a non-empty value, or the {@code WORequestIsUsingWebServerOverride}
 * system property is set true (an emergency operator escape hatch).
 *
 * <p>Header lookup at the wire level is case-insensitive; callers should obtain the
 * value via whatever case-folding lookup their request abstraction provides
 * ({@code WORequest.headerForKey(FHosts.ADAPTOR_VERSION_HEADER)} does this).
 */
public final class FHosts {

	private static final Logger logger = LoggerFactory.getLogger( FHosts.class );

	/** HTTP header set by mod_WebObjects / modulo when proxying a request. Presence => web-server-routed. */
	public static final String ADAPTOR_VERSION_HEADER = "x-webobjects-adaptor-version";

	private static final String WO_HOST_PROPERTY = "WOHost";
	private static final String IS_USING_WEB_SERVER_OVERRIDE_PROPERTY = "WORequestIsUsingWebServerOverride";
	private static final String LOCALHOST_IPS_PROPERTY = "er.extensions.WOHostUtilities.localhostips";

	private static final AtomicReference<Set<InetAddress>> localAddresses = new AtomicReference<>( computeLocalAddresses() );

	private FHosts() {}

	/**
	 * @return true if {@code address} should be considered local under the operator's
	 *         stated host policy. When {@code WOHost} is set and resolvable, only that
	 *         exact address qualifies; otherwise the union of all known local addresses
	 *         applies. Returns false on null input.
	 */
	public static boolean isLocalInetAddress( final InetAddress address, final boolean refreshOnMiss ) {
		if( address == null ) {
			return false;
		}

		final InetAddress configuredHost = configuredWOHost();
		if( configuredHost != null ) {
			return configuredHost.equals( address );
		}

		return isInLocalSet( address, refreshOnMiss );
	}

	/**
	 * @return true if {@code address} matches any locally-bound interface address
	 *         <em>or</em> the configured {@code WOHost}. Permissive variant — used for
	 *         "did this come from the same machine?" rather than for honoring the
	 *         operator's stated host policy.
	 */
	public static boolean isAnyLocalInetAddress( final InetAddress address, final boolean refreshOnMiss ) {
		if( address == null ) {
			return false;
		}

		if( isInLocalSet( address, refreshOnMiss ) ) {
			return true;
		}

		final InetAddress configuredHost = configuredWOHost();
		return configuredHost != null && configuredHost.equals( address );
	}

	/**
	 * Mirrors {@code WORequest.isUsingWebServer()}: returns true iff the
	 * {@code x-webobjects-adaptor-version} header is present with a non-empty value,
	 * or the {@code WORequestIsUsingWebServerOverride} system property is set.
	 *
	 * @param adaptorVersionHeaderValue value of the {@code x-webobjects-adaptor-version}
	 *                                  HTTP header, or {@code null} if absent
	 */
	public static boolean isUsingWebServer( final String adaptorVersionHeaderValue ) {
		if( adaptorVersionHeaderValue != null && !adaptorVersionHeaderValue.isEmpty() ) {
			return true;
		}

		final String override = System.getProperty( IS_USING_WEB_SERVER_OVERRIDE_PROPERTY );
		return override != null && Boolean.parseBoolean( override.trim() );
	}

	private static boolean isInLocalSet( final InetAddress address, final boolean refreshOnMiss ) {
		Set<InetAddress> set = localAddresses.get();
		if( set.contains( address ) ) {
			return true;
		}

		if( refreshOnMiss ) {
			set = computeLocalAddresses();
			localAddresses.set( set );
			return set.contains( address );
		}

		return false;
	}

	/**
	 * @return the configured {@code WOHost} resolved to an {@link InetAddress},
	 *         or {@code null} if the property is unset or doesn't resolve. Mirrors
	 *         WO's {@code _unsetHost} semantics — {@code null} here is exactly the
	 *         case where WO would set {@code _unsetHost = true}.
	 */
	private static InetAddress configuredWOHost() {
		final String hostProperty = System.getProperty( WO_HOST_PROPERTY );
		if( hostProperty == null || hostProperty.isEmpty() ) {
			return null;
		}

		try {
			return InetAddress.getByName( hostProperty );
		}
		catch( UnknownHostException e ) {
			return null;
		}
	}

	/**
	 * Builds the complete local-address set: {@code InetAddress.getLocalHost()},
	 * the {@code localhost} aliases, the explicit loopback aliases, every address
	 * bound to any up network interface (including IPv6, including loopback interface
	 * addresses), the operator-supplied {@code er.extensions.WOHostUtilities.localhostips}
	 * list, and finally DNS aliases of all of the above.
	 *
	 * <p>Each step is wrapped in its own try/catch so a partial failure (DNS misery,
	 * a single broken interface) doesn't drop the rest of the discovery.
	 */
	private static Set<InetAddress> computeLocalAddresses() {
		final Set<InetAddress> seeds = new LinkedHashSet<>();

		try {
			seeds.add( InetAddress.getLocalHost() );
		}
		catch( UnknownHostException e ) {
			logger.warn( "InetAddress.getLocalHost() failed: {}", e.toString() );
		}

		addByName( "localhost", seeds );
		addByName( "127.0.0.1", seeds );
		addByName( "::1", seeds );

		try {
			final var interfaces = NetworkInterface.getNetworkInterfaces();
			while( interfaces.hasMoreElements() ) {
				final NetworkInterface iface = interfaces.nextElement();
				try {
					if( !iface.isUp() ) {
						continue;
					}
					final var addresses = iface.getInetAddresses();
					while( addresses.hasMoreElements() ) {
						seeds.add( addresses.nextElement() );
					}
				}
				catch( SocketException e ) {
					logger.warn( "Couldn't read addresses from interface {}: {}", iface.getName(), e.toString() );
				}
			}
		}
		catch( SocketException e ) {
			logger.warn( "NetworkInterface.getNetworkInterfaces() failed: {}", e.toString() );
		}

		final String explicitIps = System.getProperty( LOCALHOST_IPS_PROPERTY );
		if( explicitIps != null && !explicitIps.isEmpty() ) {
			for( final String ip : explicitIps.split( "[\\s,()]+" ) ) {
				if( !ip.isEmpty() ) {
					addByName( ip, seeds );
				}
			}
		}

		// Final pass: pull in DNS aliases for every address we've found so far. Picks up
		// multi-homed hosts where the same machine has more reachable names than its
		// interface addresses suggest.
		final Set<InetAddress> withAliases = new LinkedHashSet<>( seeds );
		for( final InetAddress seed : seeds ) {
			try {
				for( final InetAddress alias : InetAddress.getAllByName( seed.getHostName() ) ) {
					withAliases.add( alias );
				}
			}
			catch( UnknownHostException e ) {
				// One DNS failure shouldn't poison the rest.
			}
		}

		return Set.copyOf( withAliases );
	}

	private static void addByName( final String hostOrIp, final Set<InetAddress> set ) {
		try {
			for( final InetAddress address : InetAddress.getAllByName( hostOrIp ) ) {
				set.add( address );
			}
		}
		catch( UnknownHostException e ) {
			logger.warn( "Couldn't resolve '{}': {}", hostOrIp, e.toString() );
		}
	}
}
