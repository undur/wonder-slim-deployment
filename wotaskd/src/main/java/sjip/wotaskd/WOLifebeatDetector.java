package sjip.wotaskd;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;

/**
 * Claims connections carrying WO instances' lifebeats before Jetty's HTTP parser ever sees them.
 *
 * WO's lifebeats arrive as {@code GET /cgi-bin/WebObjects/wotaskd.woa/wlb?... HTTP/1.1} followed directly by the empty
 * line ending the header block - no Host header, no headers at all - which Jetty's parser rejects unconditionally for
 * lack of a Host header. This detector recognizes exactly that shape: the lifebeat request line with an empty header
 * block. Anything else, including well-formed lifebeats (ng-objects' lifebeat thread sends a
 * Host header), is left to the HTTP connection that follows it on the connector, and so reaches
 * {@link LifebeatRequestHandler} the ordinary way.
 *
 * Detection runs once per connection. That covers a WO instance's whole lifebeat connection, since it never carries
 * anything but beats. See {@link WOLifebeatConnection} for how recognized connections are served.
 */
public class WOLifebeatDetector extends AbstractConnectionFactory implements ConnectionFactory.Detecting {

	public static final String PROTOCOL = "wo-lifebeat";

	private static final byte[] PREFIX = "GET /cgi-bin/WebObjects/wotaskd.woa/wlb?".getBytes( StandardCharsets.US_ASCII );

	/**
	 * A lifebeat request line is short; one that runs past this without ending is not a lifebeat. Also bounds how long
	 * detection waits for bytes.
	 */
	private static final int MAX_REQUEST_LINE = 1024;

	public WOLifebeatDetector() {
		super( PROTOCOL );
	}

	@Override
	public Detection detect( final ByteBuffer buffer ) {
		final int start = buffer.position();
		final int limit = buffer.limit();
		final int available = limit - start;

		// The request line must open with the lifebeat prefix - reject at the first mismatching byte
		for( int i = 0; i < Math.min( available, PREFIX.length ); i++ ) {
			if( buffer.get( start + i ) != PREFIX[i] ) {
				return Detection.NOT_RECOGNIZED;
			}
		}

		if( available < PREFIX.length ) {
			return Detection.NEED_MORE_BYTES;
		}

		// Find the end of the request line. WO's beats are header-less: the request line's CRLF is directly followed
		// by the CRLF that ends the (empty) header block. A request with any header at all is ordinary HTTP.
		for( int i = start + PREFIX.length; i < limit - 1; i++ ) {
			if( buffer.get( i ) == '\r' && buffer.get( i + 1 ) == '\n' ) {

				if( i + 3 >= limit ) {
					return Detection.NEED_MORE_BYTES;
				}

				final boolean headerless = buffer.get( i + 2 ) == '\r' && buffer.get( i + 3 ) == '\n';
				return headerless ? Detection.RECOGNIZED : Detection.NOT_RECOGNIZED;
			}
		}

		return available > MAX_REQUEST_LINE ? Detection.NOT_RECOGNIZED : Detection.NEED_MORE_BYTES;
	}

	@Override
	public Connection newConnection( final Connector connector, final EndPoint endPoint ) {
		return configure( new WOLifebeatConnection( endPoint, connector.getExecutor() ), connector, endPoint );
	}
}
