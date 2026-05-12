package sjip.systests.support;

import sjip.systests.support.TestReport.Step;

/**
 * Renders a {@link TestReport} as Markdown. Each step kind has its own visual treatment;
 * XML/code bodies are emitted inside fenced code blocks so editors syntax-highlight them.
 */
public final class TestReportRenderer {

	private TestReportRenderer() {}

	public static String render( final TestReport report ) {
		final StringBuilder sb = new StringBuilder();
		sb.append( "# " ).append( report.testDisplayName() ).append( "\n\n" );

		for( final Step step : report.steps() ) {
			switch( step.kind() ) {
				case HEADING -> sb.append( "## " ).append( step.title() ).append( "\n\n" );
				case ACTION -> sb.append( "### Action — " ).append( step.title() ).append( "\n\n" );
				case STATE -> appendCodeBlock( sb, "State — " + step.title(), step.body() );
				case WIRE_SEND -> appendCodeBlock( sb, "Wire send — " + step.title(), step.body() );
				case WIRE_RECEIVE -> appendCodeBlock( sb, "Wire receive — " + step.title(), step.body() );
				case NOTE -> sb.append( "> " ).append( step.body().replace( "\n", "\n> " ) ).append( "\n\n" );
			}
		}

		return sb.toString();
	}

	private static void appendCodeBlock( final StringBuilder sb, final String title, final String body ) {
		sb.append( "### " ).append( title ).append( "\n\n" );
		final String fenceLang = looksLikeXml( body ) ? "xml" : "";
		sb.append( "```" ).append( fenceLang ).append( '\n' );
		sb.append( body );
		if( !body.endsWith( "\n" ) ) {
			sb.append( '\n' );
		}
		sb.append( "```\n\n" );
	}

	private static boolean looksLikeXml( final String body ) {
		final String trimmed = body.stripLeading();
		return trimmed.startsWith( "<" );
	}
}
