package sjip.wotaskd;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sjip.core.model.ApplicationType;
import sjip.core.model.MInstance;

/**
 * Discovers what kinds of application a registered instance is, by running detection checks against the
 * instance's own HTTP interface (see wonder-slim-deployment#56). Plural: a hybrid instance can serve
 * multiple frameworks at once, so every check runs and each match contributes a type:
 *
 * - {@code /ng/describe} answers, or the dev-only {@code /ng/dev/type} answers "ng" → ng-objects
 * - {@code .../wa/describe} answers        → wonder-slim
 * - {@code /wa/ERXDirectAction/empty} answers 200/empty → Project Wonder
 * - nothing matched but the instance answered → something else
 *
 * Probing is triggered from the lifebeat path - both hasStarted and plain lifebeats, so instances already
 * running when wotaskd restarts get typed on their next beat - and runs on its own thread, since the
 * lifebeat handler must answer immediately. A connection-level failure on every check leaves the types
 * undiscovered (null), to be retried on the next beat; OTHER is only concluded from actual HTTP answers.
 */
public class ApplicationTypeProber {

	private static final Logger logger = LoggerFactory.getLogger( ApplicationTypeProber.class );

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds( 3 );

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout( PROBE_TIMEOUT ).build();

	/** Instances with a probe in flight, so a beat storm doesn't stack probes */
	private static final Set<MInstance> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	/**
	 * An instance announced hasStarted: a fresh JVM, possibly a new build with different answers.
	 * Discard what we knew and re-discover. (If a probe against the previous process is somehow still
	 * in flight, this round is skipped - the types stay null and the next lifebeat re-probes.)
	 */
	public static void reprobe( final MInstance instance ) {
		instance.setApplicationTypes( null );
		probeIfNeeded( instance );
	}

	/**
	 * Kick off a probe for the instance unless its types are already known or a probe is already running.
	 * Returns immediately; the result lands on the instance via setApplicationTypes.
	 */
	public static void probeIfNeeded( final MInstance instance ) {

		if( instance.applicationTypes() != null ) {
			return;
		}

		if( !IN_FLIGHT.add( instance ) ) {
			return;
		}

		Thread.startVirtualThread( () -> {
			try {
				final List<ApplicationType> types = probe( instance );

				if( types != null ) {
					instance.setApplicationTypes( types );
					logger.info( "Typed instance {} as {}", instance.displayName(), types );
				}
			}
			finally {
				IN_FLIGHT.remove( instance );
			}
		} );
	}

	/**
	 * @return The instance's types (enum-ordered, never empty), or null if the instance couldn't be
	 *         reached at all (leaving it to a later retry)
	 */
	private static List<ApplicationType> probe( final MInstance instance ) {
		final String base = "http://" + instance.hostName() + ":" + instance.port();
		final EnumSet<ApplicationType> types = EnumSet.noneOf( ApplicationType.class );

		// Answers from our own frameworks are authoritative. /ng/describe is always-on (a placeholder
		// for the common describe protocol planned as round two of #56, which adds version and
		// environment metadata); /ng/dev/type is the fallback for ng frameworks from before it -
		// dev-mode only, the identification ERXDevelopmentInstanceStopper uses.
		final ProbeResult ng = get( base + "/ng/describe" );
		final ProbeResult ngDev = ng.matched( "ng-objects" ) ? ProbeResult.SKIPPED : get( base + "/ng/dev/type" );

		if( ng.matched( "ng-objects" ) || (ngDev.status() == 200 && ngDev.body() != null && "ng".equals( ngDev.body().trim() )) ) {
			types.add( ApplicationType.NG_OBJECTS );
		}

		final ProbeResult wo = get( base + "/cgi-bin/WebObjects/" + instance.applicationName() + ".woa/wa/describe" );

		if( wo.matched( "wonder-slim" ) ) {
			types.add( ApplicationType.WONDER_SLIM );
		}

		// Project Wonder: ERXDirectAction.emptyAction() has been a no-op endpoint (200, empty body, no
		// session, no password check) on every Project Wonder app for years. wonder-slim never had it -
		// its ERXDirectAction answers the same URL with the "no such action" exception page - and on
		// plain WO the class doesn't resolve at all. Routed by classname, so the check doesn't depend
		// on what the application's own DirectAction class extends.
		final ProbeResult wonder = get( base + "/cgi-bin/WebObjects/" + instance.applicationName() + ".woa/wa/ERXDirectAction/empty" );

		if( wonder.status() == 200 && (wonder.body() == null || wonder.body().isBlank()) ) {
			types.add( ApplicationType.PROJECT_WONDER );
		}

		if( !types.isEmpty() ) {
			return List.copyOf( types );
		}

		// Nothing matched. If the instance answered at all, that's a conclusion; if we never got
		// through, it isn't - leave the types undiscovered and let a later beat retry.
		if( ng.answered() || ngDev.answered() || wo.answered() || wonder.answered() ) {
			return List.of( ApplicationType.OTHER );
		}

		return null;
	}

	private record ProbeResult( int status, String body ) {

		/** A check that wasn't performed because an earlier one already settled its question */
		static final ProbeResult SKIPPED = new ProbeResult( -1, null );

		/** Status < 0 means the request never produced an HTTP response */
		boolean answered() {
			return status > 0;
		}

		boolean matched( final String framework ) {
			return status == 200 && body != null && body.contains( "\"" + framework + "\"" );
		}
	}

	private static ProbeResult get( final String url ) {
		try {
			final HttpRequest request = HttpRequest.newBuilder()
					.uri( URI.create( url ) )
					.timeout( PROBE_TIMEOUT )
					.GET()
					.build();

			final HttpResponse<String> response = CLIENT.send( request, HttpResponse.BodyHandlers.ofString() );
			return new ProbeResult( response.statusCode(), response.body() );
		}
		catch( final InterruptedException e ) {
			Thread.currentThread().interrupt();
			return new ProbeResult( -1, null );
		}
		catch( final Exception e ) {
			logger.debug( "Type probe against {} failed: {}", url, e.toString() );
			return new ProbeResult( -1, null );
		}
	}
}
