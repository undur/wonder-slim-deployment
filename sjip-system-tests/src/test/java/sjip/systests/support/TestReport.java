package sjip.systests.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only step log for a single test method. Each step is a short labelled section
 * the test author records as the scenario plays out — "current state", "action taken",
 * "wire message sent", etc. {@link TestReportExtension} creates one per test and writes
 * it out as Markdown after the test completes.
 *
 * <p>The point is not assertion coverage — that's what snapshots do. The point is
 * <em>readability</em>: a reviewer can read the generated report top-to-bottom and follow
 * the test's logical narrative without context-switching between Java code, wire XML, and
 * file contents.
 */
public final class TestReport {

	public enum Kind {
		HEADING,
		STATE,
		ACTION,
		WIRE_SEND,
		WIRE_RECEIVE,
		NOTE,
	}

	public record Step( Kind kind, String title, String body ) {}

	private final String _testDisplayName;
	private final List<Step> _steps = new CopyOnWriteArrayList<>();

	public TestReport( final String testDisplayName ) {
		_testDisplayName = testDisplayName;
	}

	public String testDisplayName() {
		return _testDisplayName;
	}

	public List<Step> steps() {
		return List.copyOf( _steps );
	}

	/** A free-form heading, useful for separating logical phases of a test. */
	public TestReport heading( final String text ) {
		_steps.add( new Step( Kind.HEADING, text, null ) );
		return this;
	}

	/** A short, free-form annotation. */
	public TestReport note( final String text ) {
		_steps.add( new Step( Kind.NOTE, null, text ) );
		return this;
	}

	/**
	 * A snapshot of system state — typically a piece of XML (a {@code SiteConfig.xml} or a
	 * response body). Rendered as a fenced code block.
	 */
	public TestReport state( final String title, final String body ) {
		_steps.add( new Step( Kind.STATE, title, body ) );
		return this;
	}

	/** Records an operation the test triggered (an HTTP call, a wire message, a file write...). */
	public TestReport action( final String description ) {
		_steps.add( new Step( Kind.ACTION, description, null ) );
		return this;
	}

	/**
	 * Captures the body of a request the system-under-test sent over the wire. Pair with
	 * {@link #wireReceive(String, String)} for the matching response.
	 */
	public TestReport wireSend( final String title, final String body ) {
		_steps.add( new Step( Kind.WIRE_SEND, title, body ) );
		return this;
	}

	/** Captures the body of a response the system-under-test received over the wire. */
	public TestReport wireReceive( final String title, final String body ) {
		_steps.add( new Step( Kind.WIRE_RECEIVE, title, body ) );
		return this;
	}
}
