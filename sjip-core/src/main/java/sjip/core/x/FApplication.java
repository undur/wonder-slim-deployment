package sjip.core.x;

import com.webobjects.appserver.WOApplication;

import sjip.core.IInstanceController;

/**
 * Single point of contact between {@code sjip-core} model code and the surrounding
 * {@link WOApplication} singleton.
 *
 * <p>Every read from {@code WOApplication.application()} that originates in
 * {@code sjip-core} routes through here. The goal isn't yet to break the dependency
 * on {@code WOApplication} — it's to <strong>localize</strong> it. Once every
 * sjip-core call site is consolidated here, the eventual move to a framework-neutral
 * {@code DeploymentContext} (or to a different framework altogether) has one place
 * to change instead of being scattered across {@code MInstance}, {@code MSiteConfig},
 * {@code AdaptorConfigSerialization}, etc.
 *
 * <p>Similar deployment-context-shaped concepts already exist in {@code AppTaskd} and
 * {@code WOTaskdHandler} but live on the producing side, not the consumer side. Those
 * will be candidates for consolidation when the seam is designed for real.
 *
 * <p>FIXME: This is a step on the way to a proper {@code DeploymentContext} abstraction —
 * a value/interface threaded through the model rather than fetched from a global. // Hugi
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
	 * Process identity — {@code "wotaskd"}, {@code "JavaMonitor"}, etc.
	 * ({@code WOApplication.application().name()}). Used for error-message prefixing.
	 * For "am I wotaskd?" discrimination, use {@link #isWotaskd()} — that's a capability
	 * check, not an identity check.
	 */
	public static String name() {
		return WOApplication.application().name();
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
