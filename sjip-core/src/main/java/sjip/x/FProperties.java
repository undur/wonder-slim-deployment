package sjip.x;

import er.extensions.foundation.ERXProperties;

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
 *       {@link #stringValue}) wrap the actual property-reading mechanism. Today they
 *       delegate to {@link ERXProperties}; the shim shape means swapping that backing
 *       mechanism is a one-file change with no consumer impact.</li>
 * </ol>
 *
 * <p>JVM-built-in properties (e.g. {@code user.name}, {@code os.arch}) are read via
 * {@link #sysProp} for raw passthrough — they aren't <em>our</em> knobs, so they don't
 * get constants in {@link K}.
 *
 * <p>See issue #41 for the migration rationale.
 */
public final class FProperties {

	private FProperties() {}

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

		// === sjip-core Emailer ===

		/** SMTP server hostname for outgoing notification mail. */
		public static final StringProperty MAILER_SMTP_HOST = new StringProperty( "mailer.smtpHost", null );

		/** SMTP authentication username. */
		public static final StringProperty MAILER_SMTP_USERNAME = new StringProperty( "mailer.smtpUsername", null );

		/** SMTP authentication password. */
		public static final StringProperty MAILER_SMTP_PASSWORD = new StringProperty( "mailer.smtpPassword", null );
	}

	/** Tag interface for the typed property records. Not generic over T because Java records can't quite express what's wanted across a sealed hierarchy. */
	public sealed interface FProperty permits IntProperty, BooleanProperty, StringProperty {
		String name();
	}

	public record IntProperty( String name, int defaultValue ) implements FProperty {}

	public record BooleanProperty( String name, boolean defaultValue ) implements FProperty {}

	public record StringProperty( String name, String defaultValue ) implements FProperty {}

	/**
	 * Reads the property as an int, falling back to its declared default when unset or
	 * unparseable. Delegates to {@link ERXProperties#intForKeyWithDefault}.
	 */
	public static int intValue( IntProperty property ) {
		return ERXProperties.intForKeyWithDefault( property.name(), property.defaultValue() );
	}

	/**
	 * Reads the property as a boolean, falling back to its declared default when unset
	 * or unparseable. Delegates to {@link ERXProperties#booleanForKeyWithDefault}.
	 */
	public static boolean booleanValue( BooleanProperty property ) {
		return ERXProperties.booleanForKeyWithDefault( property.name(), property.defaultValue() );
	}

	/**
	 * Reads the property as a String, falling back to its declared default when unset.
	 * Delegates to {@link ERXProperties#stringForKeyWithDefault}.
	 */
	public static String stringValue( StringProperty property ) {
		return ERXProperties.stringForKeyWithDefault( property.name(), property.defaultValue() );
	}

	/**
	 * Raw passthrough for JVM-defined properties ({@code user.name}, {@code os.arch}, etc.)
	 * that aren't part of the platform's own configuration surface. Returns {@code null}
	 * if the property isn't set.
	 */
	public static String sysProp( String name ) {
		return System.getProperty( name );
	}
}
