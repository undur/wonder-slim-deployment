package x;

import com.webobjects.foundation.NSLog;

/**
 * Drop-in stand-in for {@code com.webobjects.foundation.NSLog}, exposing only the
 * three logger fields ({@code .debug}, {@code .err}, {@code .out}) and the
 * private-exception helper actually used by the deployment code.
 *
 * <p>Currently a thin pass-through to {@link NSLog}. The point isn't to remove the
 * Foundation dependency yet — that comes in a follow-up pass that inlines each call
 * site to a per-class slf4j {@link org.slf4j.Logger}. The point is to gather every
 * call site under a single name we own, so the follow-up replacement is a mechanical
 * find-and-inline driven by the IDE rather than a project-wide grep against
 * {@code NSLog}.
 *
 * <p>While this class is in place, log output is controlled by whatever configuration
 * applies to the underlying {@code NSLog.debug}/{@code .err}/{@code .out} loggers
 * — by default they print to stdout/stderr unconditionally. Once the slf4j inline
 * pass is done, output will be controlled by the project's standard
 * {@code log4j}/{@code logback} configuration like everything else.
 *
 * @deprecated migration aid. Replace each call site with a per-class slf4j
 *             {@link org.slf4j.Logger} and remove this class.
 */
@Deprecated
public final class FLog {

	// Note: NSLog.debug/err/out are declared volatile non-final and Apple has
	// setDebug/setErr/setOut methods that can swap them at runtime. We snapshot
	// them as final here because nothing in this codebase calls those setters.
	// If that changes, switch these to getter methods that read NSLog.* on each
	// access.
	public static final NSLog.Logger debug = NSLog.debug;
	public static final NSLog.Logger err = NSLog.err;
	public static final NSLog.Logger out = NSLog.out;

	public static void _conditionallyLogPrivateException( final Throwable t ) {
		NSLog._conditionallyLogPrivateException( t );
	}

	private FLog() {
		// not instantiable
	}
}
