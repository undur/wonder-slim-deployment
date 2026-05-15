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
	 * ({@code WOApplication.application().name()}). Used for error-message prefixing
	 * and for {@code MSiteConfig}'s "am I wotaskd?" discrimination on disk permissions.
	 */
	public static String name() {
		return WOApplication.application().name();
	}

	/**
	 * The {@link IInstanceController} the surrounding application exposes under the
	 * KVC key {@code "instanceController"}, or {@code null} if the application
	 * doesn't have one (JavaMonitor doesn't; only wotaskd does).
	 *
	 * <p>The KVC lookup is preserved as-is — it's a runtime service-locator hook and
	 * the alternative requires threading the controller through every model
	 * construction path.
	 */
	public static IInstanceController instanceController() {
		return (IInstanceController)WOApplication.application().valueForKey( "instanceController" );
	}
}
