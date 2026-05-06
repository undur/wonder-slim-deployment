package sjip.monitor.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOResponse;

import er.extensions.appserver.ERXApplication;
import er.extensions.components.ERXComponent;

/**
 * A component for viewing log files.
 */

public class JMLogViewerPage extends ERXComponent {

	/**
	 * Let's ensure the user won't just kill Monitor by viewing a few million lines
	 */
	private static final int MAX_NUMBER_OF_LINES = 10000;

	/**
	 * Path of the file being viewed
	 */
	private File _file;

	/**
	 * Lines currently being displayed in the UI.
	 */
	public List<Line> lines;

	/**
	 * Current iteration in the repetition
	 */
	public int currentIndex;

	/**
	 * Line currently being iterated over in the UI.
	 */
	public Line currentLine;

	/**
	 * UI input variables
	 */
	public Integer startLine;
	public Integer endLine;
	public String filter;
	public boolean reverseLines = false;
	public boolean showLineNumbers = true;

	// Keep track of the number of added lines in the current update, so we can highlight new lines
	private int previousLineCount = 0;
	private int numberOfNewLines = 0;

	public JMLogViewerPage( WOContext context ) {
		super( context );
	}

	public static JMLogViewerPage create( final WOContext context, final File file ) {
		final JMLogViewerPage page = ERXApplication.erxApplication().pageWithName( JMLogViewerPage.class, context );
		page.setFile( file );
		return page;
	}

	public File file() {
		return _file;
	}

	private void setFile( File value ) {
		_file = value;

		int numberOfLines = numberOfLines();

		if( numberOfLines > MAX_NUMBER_OF_LINES ) {
			startLine = numberOfLines - (MAX_NUMBER_OF_LINES - 1);
		}

		read();
	}

	/**
	 * Perform the actual reading of the files, and calculate how many new lines we've got.
	 */
	public WOActionResults read() {
		lines = readLines( file(), filter, startLine, endLine, reverseLines );
		numberOfNewLines = lines.size() - previousLineCount;
		previousLineCount = lines.size();
		return null;
	}

	public WOActionResults download() {
		try {
			return responseWithStreamAndMimeType( file().getName(), new FileInputStream( file() ), file().length(), "text/plain", true );
		}
		catch( IOException e ) {
			throw new RuntimeException( e );
		}
	}

	/**
	 * Creates a WOResponse containing the given data.
	 */
	private static WOResponse responseWithStreamAndMimeType( String filename, InputStream stream, long length, String mimeType, boolean forceDownload ) {
		Objects.requireNonNull( filename );
		Objects.requireNonNull( stream );
		Objects.requireNonNull( mimeType );

		String disposition = forceDownload ? "attachment" : "inline";

		final WOResponse response = new WOResponse();
		response.setHeader( mimeType, "content-type" );
		response.setHeader( length + "", "content-length" );
		response.setHeader( disposition + ";filename=\"" + filename + "\"", "content-disposition" );
		response.setContentStream( stream, 32000, length );
		return response;
	}

	public String currentLineClass() {
		final List<String> cssClasses = new ArrayList<>();

		if( reverseLines ) {
			if( currentIndex < numberOfNewLines ) {
				cssClasses.add( "nl" );
			}
		}
		else {
			if( currentIndex + 1 > (lines.size() - numberOfNewLines) ) {
				cssClasses.add( "nl" );
			}
		}

		// FIXME: Mostly just to try out line highlighting. Highlighted text should be configurable // Hugi 2024-10-30
		if( currentLine.text.contains( "Exception occurred" ) ) {
			cssClasses.add( " ex" );
		}

		if( !cssClasses.isEmpty() ) {
			return String.join( " ", cssClasses );
		}

		return null;
	}

	/**
	 * the number of lines in the given document.
	 */
	public int numberOfLines() {
		return countLines( file() );
	}

	/**
	 * Return the specified line numbers from the given file.
	 */
	private static List<Line> readLines( File file, String filter, Integer firstLineNumber, Integer lastLineNumber, boolean reverse ) {

		if( firstLineNumber == null ) {
			firstLineNumber = 1;
		}

		if( lastLineNumber == null ) {
			lastLineNumber = Integer.MAX_VALUE;
		}

		final List<Line> lines = new ArrayList<>();

		try( BufferedReader in = new BufferedReader( new InputStreamReader( new FileInputStream( file ) ) )) {
			String lineString;

			int currentIndex = 1;

			while( (lineString = in.readLine()) != null && currentIndex <= lastLineNumber ) {
				final boolean isInScope = currentIndex >= firstLineNumber && currentIndex <= lastLineNumber;
				final boolean matchesFilter = filter == null || lineString.toLowerCase().contains( filter.toLowerCase() );

				if( isInScope && matchesFilter ) {
					lines.add( new Line( currentIndex, lineString ) );
				}

				currentIndex++;
			}
		}
		catch( IOException e ) {
			throw new UncheckedIOException( e );
		}

		if( reverse ) {
			Collections.reverse( lines );
		}

		return lines;
	}

	/**
	 * Count the number of lines in the given file.
	 */
	private static int countLines( File file ) {
		int lines = 0;

		try( BufferedReader reader = new BufferedReader(new FileReader( file )) ) {
			while (reader.readLine() != null) {
				lines++;
			}
		}
		catch( IOException e ) {
			throw new UncheckedIOException( e );
		}

		return lines;
	}

	public static class Line {
		public int number;
		public String text;

		public Line( int newNumber, String newText ) {
			number = newNumber;
			text = newText;
		}
	}
}