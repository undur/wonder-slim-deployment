package x;

import com.webobjects.foundation.NSLog;

/**
 * Drop-in stand-in for {@code com.webobjects.foundation.NSLog}, replicating only the
 * surface that the deployment code actually uses — {@code .debug}, {@code .err},
 * {@code .out}, {@code appendln(...)}, {@code debugLoggingAllowedForLevelAndGroups(...)}
 * and the {@code DebugLevel*} / {@code DebugGroupDeployment} constants.
 *
 * <p>Currently a thin pass-through to {@link NSLog}. The point isn't to remove the
 * Foundation dependency yet — that comes in a follow-up pass that inlines each call
 * site to a per-class slf4j {@link org.slf4j.Logger}. The point is to gather every
 * call site under a single name we own, so the follow-up replacement is a mechanical
 * find-and-inline driven by the IDE rather than a project-wide grep against
 * {@code NSLog}.
 *
 * @deprecated migration aid. Replace each call site with a per-class slf4j
 *             {@link org.slf4j.Logger} and remove this class.
 */
@Deprecated
public final class FLog {

	public static final int DebugLevelOff = NSLog.DebugLevelOff;
	public static final int DebugLevelCritical = NSLog.DebugLevelCritical;
	public static final int DebugLevelInformational = NSLog.DebugLevelInformational;
	public static final int DebugLevelDetailed = NSLog.DebugLevelDetailed;

	public static final long DebugGroupDeployment = NSLog.DebugGroupDeployment;

	// Note: NSLog.debug/err/out are declared volatile non-final and Apple has
	// setDebug/setErr/setOut methods that can swap them at runtime. We snapshot
	// them as final here because nothing in this codebase calls those setters.
	// If that changes, switch these to getter methods that read NSLog.* on each
	// access.
	public static final NSLog.Logger debug = NSLog.debug;
	public static final NSLog.Logger err = NSLog.err;
	public static final NSLog.Logger out = NSLog.out;

	public static boolean debugLoggingAllowedForLevelAndGroups( final int level, final long groups ) {
		return NSLog.debugLoggingAllowedForLevelAndGroups( level, groups );
	}

	public static boolean debugLoggingAllowedForLevel( final int level ) {
		return NSLog.debugLoggingAllowedForLevel( level );
	}

	public static void allowDebugLoggingForGroups( final long groups ) {
		NSLog.allowDebugLoggingForGroups( groups );
	}

	public static void _conditionallyLogPrivateException( final Throwable t ) {
		NSLog._conditionallyLogPrivateException( t );
	}

	private FLog() {
		// not instantiable
	}
}
