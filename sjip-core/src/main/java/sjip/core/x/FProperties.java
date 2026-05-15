package sjip.core.x;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

/**
 * Single facade for every configuration property the platform consumes.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>Inventory — every platform-defined property name lives here as a constant in the
 *       inner {@link K} class, with its default value, type, and a Javadoc comment
 *       documenting what it does. {@code FProperties.K} <em>is</em> the canonical "what
 *       knobs exist?" reference.</li>
 *   <li>Reading — proxy methods ({@link #intValue}, {@link #booleanValue},
 *       {@link #stringValue}) read directly from {@link System#getProperty}.</li>
 * </ol>
 *
 * <p>JVM-built-in properties (e.g. {@code user.name}, {@code os.arch}) are read via
 * {@link #sysProp} for raw passthrough — they aren't <em>our</em> knobs, so they don't
 * get constants in {@link K}.
 *
 * <h2>Load-order assumption</h2>
 *
 * <p>{@code FProperties} reads from {@link System#getProperty}. WO/Wonder loads
 * application properties (from each framework's bundled {@code Properties} file, the
 * user's {@code WebObjects.properties}, and the command line) into
 * {@code System.getProperties()} during {@link com.webobjects.appserver.WOApplication}'s
 * constructor — specifically inside {@code _initWOApp}, called from the {@code super()}
 * chain. So any read after that constructor has run sees the loaded values.
 *
 * <p>The implicit invariant: <strong>no {@code static final} initializer anywhere in
 * the codebase that runs before the application constructor's {@code super()} chain
 * completes may call {@link #intValue}, {@link #booleanValue}, or {@link #stringValue}
 * on a property that depends on Properties-file or command-line values.</strong>
 * Today every such read happens in classes that are class-loaded after the application
 * is instantiated ({@code InstanceController}, {@code MSiteConfig}, etc.), so the
 * invariant holds. Adding a property read to a class loaded earlier in the bootstrap
 * (e.g. anything touched by {@code main} before {@code WOApplication}'s constructor
 * runs) would break it silently — the read would return the default value instead of
 * the configured one.
 *
 * <p>If this becomes a problem, the fix is to defer the read until after bootstrap
 * (e.g. read-on-first-use with a memoized supplier). Today it's a non-issue.
 *
 * <p>See issue #41 for the migration rationale.
 */
public final class FProperties {

	private FProperties() {}

	/**
	 * Local host name as MSiteConfig should record it. Assigned by the hosting
	 * Application (wotaskd or JavaMonitor) at startup, before any MSiteConfig is
	 * constructed; read by MSiteConfig's constructor.
	 *
	 * <p>FIXME: Oh my god. This field exists purely as a temporary seam so MSiteConfig
	 * can be instantiated in system tests without booting a WOApplication. The "right"
	 * shape is for MSiteConfig to receive its local-host identity through a constructor
	 * argument (or be split apart so its model layer doesn't carry this state at all).
	 * Will go away with the MSiteConfig clusterfudge cleanup. // Hugi 2026-05-12
	 */
	@Deprecated
	public static String siteConfigLocalHostName;

	/**
	 * Local host address as MSiteConfig should record it. Same lifecycle as
	 * {@link #siteConfigLocalHostName}.
	 *
	 * <p>FIXME: Oh my god. Same story as {@link #siteConfigLocalHostName} — temporary
	 * seam for testability, will go away with the MSiteConfig cleanup. // Hugi 2026-05-12
	 */
	@Deprecated
	public static InetAddress siteConfigLocalHostAddress;

	/**
	 * The canonical registry of platform-defined properties. Add a constant here when
	 * introducing a new knob; that's the single place the documentation lives.
	 */
	public static final class K {

		private K() {}

		// === wotaskd InstanceController ===

		/** How long to wait, in milliseconds, before a stuck instance gets force-quit after a TERMINATE/REFUSE. Minimum enforced 60000. */
		public static final IntProperty FORCE_QUIT_DELAY = new IntProperty( "WOTaskd.killTimeout", 120000 );

		/** HTTP receive timeout, in milliseconds, for wotaskd's instanceRequest calls into managed apps. */
		public static final IntProperty RECEIVE_TIMEOUT = new IntProperty( "WOTaskd.receiveTimeout", 5000 );

		/** When true, schedule a force-quit watcher after TERMINATE/REFUSE that takes more aggressive action if the instance doesn't exit. */
		public static final BooleanProperty FORCE_QUIT_TASK_ENABLED = new BooleanProperty( "WOTaskd.forceQuitTaskEnabled", false );

		/** Number of REFUSE-watcher retry cycles before force-quitting a draining instance. Used in conjunction with {@link #FORCE_QUIT_DELAY}. */
		public static final IntProperty REFUSE_NUM_RETRIES = new IntProperty( "WOTaskd.refuseNumRetries", 3 );

		/** When true, instances launch detached from wotaskd's process group (POSIX-only). See issue #2 for the long-term direction. */
		public static final BooleanProperty DETACH_LAUNCH = new BooleanProperty( "WOTaskd.detachLaunch", false );

		/** When true, instance launches go through the per-platform spawn helper ({@code SpawnOfWotaskd.sh}/{@code .exe}) if it exists. */
		public static final BooleanProperty SHOULD_USE_SPAWN = new BooleanProperty( "WOShouldUseSpawn", false );

		// === wotaskd Application ===

		/** Multicast group address for the legacy adaptor discovery channel. See issue #20 about retiring this. */
		public static final StringProperty MULTICAST_ADDRESS = new StringProperty( "WOMulticastAddress", "239.128.14.2" );

		/** When true, wotaskd writes the live adaptor config to {@code WOConfig.xml} on disk. */
		public static final BooleanProperty SAVES_ADAPTOR_CONFIGURATION = new BooleanProperty( "WOSavesAdaptorConfiguration", false );

		/** When true, wotaskd answers multicast adaptor-discovery probes. UDP probes are always answered regardless. */
		public static final BooleanProperty RESPONDS_TO_MULTICAST_QUERY = new BooleanProperty( "WORespondsToMulticastQuery", false );

		// === sjip-core MSiteConfig ===

		/** Multiplier applied to lifebeat interval when deciding an instance has missed too many beats and is dead. */
		public static final StringProperty ASSUME_APPLICATION_IS_DEAD_MULTIPLIER = new StringProperty( "WOAssumeApplicationIsDeadMultiplier", null );

		/** Filesystem path where wotaskd/JavaMonitor look for {@code SiteConfig.xml}. */
		public static final StringProperty DEPLOYMENT_CONFIGURATION_DIRECTORY = new StringProperty( "WODeploymentConfigurationDirectory", null );

		// === sjip-core FHosts ===

		/** When set, the platform binds to this single host address and treats only it as "local" for admin-action gating. When unset, every locally-bound interface address counts as local. */
		public static final StringProperty WO_HOST = new StringProperty( "WOHost", null );

		/**
		 * Comma- or whitespace-separated list of IP addresses to treat as local for admin-action
		 * gating. When set, replaces the auto-detected list of locally-bound interface addresses,
		 * letting an operator restrict admin access to a curated set (e.g. only the management-plane
		 * interface on a multi-NIC host) or declare an allow-list in pre-configured environments.
		 * Loopback aliases and DNS aliases are still included regardless. Format mirrors
		 * ERXProperties array syntax: {@code (1.2.3.4, 5.6.7.8)} or just {@code 1.2.3.4,5.6.7.8}.
		 *
		 * <p>FIXME: This property exists for backwards compatibility with the override semantic
		 * established in the wonder-slim {@code WOHostUtilities} patch. The future direction —
		 * first-class admin access policy supporting both restriction and expansion as legitimate
		 * cases, plus future auth-shaped access — is captured in deployment issue #45.
		 */
		public static final StringProperty LOCALHOST_IPS = new StringProperty( "er.extensions.WOHostUtilities.localhostips", null );

		// === sjip-core Emailer ===

		/** SMTP server hostname for outgoing notification mail. */
		public static final StringProperty MAILER_SMTP_HOST = new StringProperty( "mailer.smtpHost", null );

		/** SMTP authentication username. */
		public static final StringProperty MAILER_SMTP_USERNAME = new StringProperty( "mailer.smtpUsername", null );

		/** SMTP authentication password. */
		public static final StringProperty MAILER_SMTP_PASSWORD = new StringProperty( "mailer.smtpPassword", null, true );
	}

	/** Tag interface for the typed property records. Not generic over T because Java records can't quite express what's wanted across a sealed hierarchy. */
	public sealed interface FProperty permits IntProperty, BooleanProperty, StringProperty {
		String name();

		/** True when the property's value should be elided from logs. Only meaningful for secrets. */
		boolean redacted();
	}

	public record IntProperty( String name, int defaultValue, boolean redacted ) implements FProperty {
		public IntProperty( String name, int defaultValue ) { this( name, defaultValue, false ); }

		/**
		 * Reads the property as an int. Falls back to the declared default when unset or
		 * unparseable.
		 */
		public int value() {
			final String raw = System.getProperty( name );
			if( raw == null ) {
				return defaultValue;
			}
			try {
				return Integer.parseInt( raw.trim() );
			}
			catch( NumberFormatException e ) {
				return defaultValue;
			}
		}
	}

	public record BooleanProperty( String name, boolean defaultValue, boolean redacted ) implements FProperty {
		public BooleanProperty( String name, boolean defaultValue ) { this( name, defaultValue, false ); }

		/**
		 * Reads the property as a boolean. Recognises {@code "true"}, {@code "yes"},
		 * {@code "y"}, {@code "on"}, {@code "1"} (case-insensitive) as true and
		 * {@code "false"}, {@code "no"}, {@code "n"}, {@code "off"}, {@code "0"} as false.
		 * Anything else falls back to the declared default — matches the permissive
		 * parsing the previous {@code ERXProperties} backing layer used.
		 */
		public boolean value() {
			final String raw = System.getProperty( name );
			if( raw == null ) {
				return defaultValue;
			}
			return switch( raw.trim().toLowerCase() ) {
				case "true", "yes", "y", "on", "1" -> true;
				case "false", "no", "n", "off", "0" -> false;
				default -> defaultValue;
			};
		}
	}

	public record StringProperty( String name, String defaultValue, boolean redacted ) implements FProperty {
		public StringProperty( String name, String defaultValue ) { this( name, defaultValue, false ); }

		/**
		 * Reads the property as a String. Returns the declared default when unset.
		 */
		public String value() {
			final String raw = System.getProperty( name );
			return raw != null ? raw : defaultValue;
		}
	}

	/**
	 * Raw passthrough for JVM-defined properties ({@code user.name}, {@code os.arch}, etc.)
	 * that aren't part of the platform's own configuration surface. Returns {@code null}
	 * if the property isn't set.
	 */
	public static String sysProp( String name ) {
		return System.getProperty( name );
	}

	/**
	 * Logs the value currently in effect for every property declared in {@link K}. One
	 * line per property, aligned, with each entry tagged {@code [set]} or {@code [default]}
	 * to show whether the operator has overridden it. Redacted properties show
	 * {@code *** (N chars)} instead of their value.
	 *
	 * <p>Intended to be called once per component at the end of the application's
	 * constructor — gives operators a clean snapshot of the configuration the platform
	 * actually started with, and surfaces any load-order surprises (a property the
	 * operator set but that the platform doesn't see) as a {@code [default]} marker
	 * where {@code [set]} was expected.
	 *
	 * <p>Output is shaped to match Wonder's own startup banner format (a section heading
	 * framed by {@code =} separators, a header row, then content rows).
	 */
	public static void logCurrentValues( final Logger logger ) {
		final List<FProperty> all = registry();

		final String headerName = "-- Property --";
		final String headerValue = "-- Value --";
		final String headerSource = "-- Source --";

		final int nameWidth = Math.max( headerName.length(), all.stream().mapToInt( p -> p.name().length() ).max().orElse( 0 ) );
		final int valueWidth = Math.max( headerValue.length(), all.stream().mapToInt( p -> displayValue( p ).length() ).max().orElse( 0 ) );
		final int totalWidth = nameWidth + 3 + valueWidth + 3 + headerSource.length();

		final String title = " CONFIGURATION ";
		final int sideBars = Math.max( 4, (totalWidth - title.length()) / 2 );
		final String topBar = repeat( '=', sideBars ) + title + repeat( '=', totalWidth - sideBars - title.length() );
		final String bottomBar = repeat( '=', topBar.length() );

		final StringBuilder sb = new StringBuilder();
		sb.append( '\n' ).append( topBar );
		sb.append( '\n' ).append( padRight( headerName, nameWidth ) ).append( "   " ).append( padRight( headerValue, valueWidth ) ).append( "   " ).append( headerSource );
		for( final FProperty property : all ) {
			final boolean isSet = System.getProperty( property.name() ) != null;
			sb.append( '\n' );
			sb.append( padRight( property.name(), nameWidth ) );
			sb.append( "   " );
			sb.append( padRight( displayValue( property ), valueWidth ) );
			sb.append( "   [" );
			sb.append( isSet ? "set" : "default" );
			sb.append( ']' );
		}
		sb.append( '\n' ).append( bottomBar );

		logger.info( sb.toString() );
	}

	/**
	 * Reflectively gathers every {@link FProperty} constant declared in {@link K}, in
	 * declaration order. Cached on first call.
	 */
	private static List<FProperty> registry() {
		final List<FProperty> list = new ArrayList<>();
		for( final Field field : K.class.getDeclaredFields() ) {
			if( FProperty.class.isAssignableFrom( field.getType() ) ) {
				try {
					list.add( (FProperty)field.get( null ) );
				}
				catch( IllegalAccessException e ) {
					throw new AssertionError( "FProperties.K field not accessible: " + field.getName(), e );
				}
			}
		}
		return list;
	}

	private static String displayValue( final FProperty property ) {
		final String raw = System.getProperty( property.name() );
		final String effective = switch( property ) {
			case IntProperty p -> Integer.toString( p.value() );
			case BooleanProperty p -> Boolean.toString( p.value() );
			case StringProperty p -> {
				final String v = p.value();
				yield v != null ? v : "(unset)";
			}
		};

		if( property.redacted() && raw != null ) {
			return "*** (" + raw.length() + " chars)";
		}

		return effective;
	}

	private static String padRight( final String s, final int width ) {
		if( s.length() >= width ) {
			return s;
		}
		final StringBuilder sb = new StringBuilder( width );
		sb.append( s );
		while( sb.length() < width ) {
			sb.append( ' ' );
		}
		return sb.toString();
	}

	private static String repeat( final char c, final int count ) {
		final char[] buf = new char[count];
		java.util.Arrays.fill( buf, c );
		return new String( buf );
	}
}
