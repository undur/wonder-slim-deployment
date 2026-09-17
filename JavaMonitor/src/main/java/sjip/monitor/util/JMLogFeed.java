package sjip.monitor.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;

import com.webobjects.appserver.WOResponse;
import com.webobjects.appserver.sse.SSEHub;

/**
 * A log4j appender that broadcasts every log event to SSE subscribers - the feed behind the "Live log" page.
 *
 * Registered on the root logger at startup, so it sees what the console sees. Each event goes out as a small
 * JSON object (time, level, logger, message, and a stack trace when the event carries a throwable) on the
 * SSE event name "log"; the page renders and colors it client side.
 *
 * {@link SSEHub#broadcast} only queues bytes on the open streams, so appending never blocks on a socket and a
 * feed with no subscribers costs a formatting pass and nothing else.
 */
public class JMLogFeed extends AppenderSkeleton {

	private static final SSEHub HUB = new SSEHub();

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern( "HH:mm:ss.SSS" ).withZone( ZoneId.systemDefault() );

	/**
	 * @return The open-ended SSE response subscribing the caller to the feed
	 */
	public static WOResponse subscribe() {
		return HUB.open().response();
	}

	@Override
	protected void append( final LoggingEvent event ) {
		final StringBuilder json = new StringBuilder( 256 );
		json.append( "{\"time\":\"" ).append( TIME.format( Instant.ofEpochMilli( event.getTimeStamp() ) ) );
		json.append( "\",\"level\":\"" ).append( event.getLevel() );
		json.append( "\",\"logger\":\"" ).append( escape( shortLoggerName( event.getLoggerName() ) ) );
		json.append( "\",\"message\":\"" ).append( escape( String.valueOf( event.getRenderedMessage() ) ) );

		final String[] trace = event.getThrowableStrRep();

		if( trace != null && trace.length > 0 ) {
			json.append( "\",\"trace\":\"" ).append( escape( String.join( "\n", trace ) ) );
		}

		json.append( "\"}" );

		HUB.broadcast( "log", json.toString() );
	}

	@Override
	public boolean requiresLayout() {
		return false;
	}

	@Override
	public void close() {
		HUB.closeAll();
	}

	private static String shortLoggerName( final String loggerName ) {
		if( loggerName == null ) {
			return "";
		}

		final int lastDot = loggerName.lastIndexOf( '.' );
		return lastDot == -1 ? loggerName : loggerName.substring( lastDot + 1 );
	}

	private static String escape( final String string ) {
		final StringBuilder sb = new StringBuilder( string.length() + 16 );

		for( int i = 0; i < string.length(); i++ ) {
			final char c = string.charAt( i );

			switch( c ) {
				case '"' -> sb.append( "\\\"" );
				case '\\' -> sb.append( "\\\\" );
				case '\n' -> sb.append( "\\n" );
				case '\r' -> sb.append( "\\r" );
				case '\t' -> sb.append( "\\t" );
				default -> {
					if( c < 0x20 ) {
						sb.append( String.format( "\\u%04x", (int)c ) );
					}
					else {
						sb.append( c );
					}
				}
			}
		}

		return sb.toString();
	}
}
