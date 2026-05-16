package sjip.core.x;

import com.webobjects.appserver.WOApplication;

import sjip.core.IInstanceController;


/**
 * Temporary holder class for ambient process-level data that {@code sjip-core} code
 * needs to read — the surrounding application's host name, role, registered
 * {@code InstanceController}, and so on. Today these are reached as static accessors;
 * eventually each piece of data should be passed to its use sites explicitly, at which
 * point this class goes away.
 *
 * <p>Until then, this class serves as a single point of contact between {@code sjip-core}
 * and the surrounding {@link WOApplication} singleton: every read from
 * {@code WOApplication.application()} that originates in {@code sjip-core} routes through
 * here, and bits of data that aren't on {@code WOApplication} at all (process role,
 * the {@code InstanceController}) are registered into here at boot. Localizing the
 * coupling rather than smearing it across {@code MInstance}, {@code MSiteConfig},
 * {@code AdaptorConfigSerialization}, etc.
 *
 * <p>Similar deployment-context-shaped concepts already exist in {@code AppTaskd} and
 * {@code WOTaskdHandler} but live on the producing side, not the consumer side. Those
 * are candidates for consolidation when the seam is designed for real.
 */
public final class FApplication {

	private FApplication() {}

	/**
	 * The local host name as the surrounding application sees it
	 * ({@code WOApplication.application().host()}).
	 */
	public static String host() {
		return WOApplication.application().host();
	}

	/**
	 * Whether the surrounding application was started without an explicit
	 * {@code -WOHost} argument ({@code WOApplication.application()._unsetHost}).
	 * When this is true, callers should omit the {@code -WOHost} flag from launched
	 * subprocess command lines.
	 */
	public static boolean hostIsUnset() {
		return WOApplication.application()._unsetHost;
	}

	/**
	 * The port instances should send lifebeats to
	 * ({@code WOApplication.application().lifebeatDestinationPort()}).
	 */
	public static int lifebeatDestinationPort() {
		return WOApplication.application().lifebeatDestinationPort();
	}

	/**
	 * The role this process plays in the deployment system — {@code "wotaskd"} (the
	 * host-local daemon that manages instances and persists config) or {@code "monitor"}
	 * (the operator UI that edits config and ships it to wotaskds). Used for
	 * error-message prefixing so an operator reading a globalErrorDictionary entry can
	 * tell which kind of process emitted it.
	 *
	 * <p>"Role" rather than "name" because the actual WO application name has drifted
	 * historically (the monitor was renamed Monitor → JavaMonitor when ported to Java);
	 * the role this process plays in the deployment is more stable than the executable
	 * name it happens to ship under.
	 */
	public static String role() {
		return isWotaskd() ? "wotaskd" : "monitor";
	}

	/**
	 * Whether the surrounding application is wotaskd (rather than JavaMonitor or
	 * something else). Used by {@code MSiteConfig} to gate config-dir write-permission
	 * checks — only wotaskd is responsible for writing config back to disk.
	 *
	 * <p>Service-locator pattern: wotaskd's bootstrap calls {@link #setIsWotaskd(boolean)}
	 * once during startup. Defaults to {@code false} (the JavaMonitor case).
	 *
	 * <p>FIXME: Intermediate measure. The honest shape is a responsibility declared by
	 * the registering application (e.g. {@code ownsConfigPersistence()}, "this process is
	 * the one that writes SiteConfig back to disk") rather than an identity question.
	 * Wotaskd writes config; JavaMonitor doesn't. The location and naming are wrong; the
	 * functionality — removing sjip-core's dependency on sniffing the application's name —
	 * is what matters today. Revisit alongside #50.
	 */
	public static boolean isWotaskd() {
		return _isWotaskd;
	}

	public static void setIsWotaskd( boolean isWotaskd ) {
		_isWotaskd = isWotaskd;
	}

	private static volatile boolean _isWotaskd;

	/**
	 * The {@link IInstanceController} the surrounding application has registered, or
	 * {@code null} if none has been registered (JavaMonitor doesn't; only wotaskd does).
	 *
	 * <p>Service-locator pattern: wotaskd's bootstrap calls
	 * {@link #setInstanceController(IInstanceController)} once during startup, after
	 * which the two sjip-core consumers ({@code AdaptorConfigSerialization}, {@code
	 * MSiteConfig.removeInstance_W}) can reach it. Still a global, but typed and
	 * grep-able — was a KVC {@code valueForKey("instanceController")} lookup until
	 * the framework-touchpoint sweep made that lookup brittle.
	 */
	public static IInstanceController instanceController() {
		return _instanceController;
	}

	public static void setInstanceController( IInstanceController instanceController ) {
		_instanceController = instanceController;
	}

	private static volatile IInstanceController _instanceController;
}
