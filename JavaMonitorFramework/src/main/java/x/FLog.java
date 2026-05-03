package x;

import com.webobjects.foundation.NSLog;

/**
 * Drop-in stand-in for {@code com.webobjects.foundation.NSLog}, exposing two static
 * methods ({@link #debug(String)} and {@link #error(String)}) the deployment code uses.
 *
 * <p>Currently a thin pass-through to {@link NSLog}. The point isn't to remove the
 * Foundation dependency yet — that comes in a follow-up pass that inlines each call
 * site to a per-class slf4j {@link org.slf4j.Logger}. The point is to gather every
 * call site under a single name we own, with a method shape that matches slf4j's,
 * so the follow-up replacement is a mechanical find-and-inline.
 *
 * <p>While this class is in place, log output is controlled by whatever configuration
 * applies to the underlying {@code NSLog.debug}/{@code .err} loggers — by default
 * they print to stdout/stderr unconditionally. Once the slf4j inline pass is done,
 * output will be controlled by the project's standard {@code log4j}/{@code logback}
 * configuration like everything else.
 *
 * @deprecated migration aid. Replace each call site with a per-class slf4j
 *             {@link org.slf4j.Logger} and remove this class.
 */
@Deprecated
public final class FLog {

	private FLog() {
		// not instantiable
	}

	public static void debug( final String message ) {
		NSLog.debug.appendln( message );
	}

	public static void error( final String message ) {
		NSLog.err.appendln( message );
	}
}
