package sjip.wotaskd;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;

import sjip.wotaskd.LifebeatRequestHandler.Verdict;

/**
 * Serves a connection {@link WOLifebeatDetector} claimed: a WO instance's lifebeat connection, which the instance
 * keeps open across its beats.
 *
 * The replies reproduce the classic adaptor's byte for byte, as captured from a classic wotaskd, so WO instances
 * get exactly the answers they always have:
 *
 * {@snippet :
 * HTTP/1.1 200 Apple WebObjects\r\n\r\n\r\n   // hasStarted, lifebeat
 * HTTP/1.0 200 Apple WebObjects\r\n\r\n\r\n   // willStop, willCrash, and beats from an unaccepted address
 * HTTP/1.0 400 Apple WebObjects\r\n\r\n\r\n   // malformed beat
 * HTTP/1.0 500 Apple WebObjects\r\n\r\n\r\n   // force quit
 * }
 *
 * The beats themselves are handed to {@link LifebeatRequestHandler#process}, the same logic that serves well-formed
 * beats arriving over ordinary HTTP.
 */
public class WOLifebeatConnection extends AbstractConnection implements Connection.UpgradeTo {

	private static final Logger logger = LoggerFactory.getLogger( WOLifebeatConnection.class );

	private static final byte[] OK = reply( "HTTP/1.1", 200 );
	private static final byte[] ACKNOWLEDGED = reply( "HTTP/1.0", 200 );
	private static final byte[] BAD = reply( "HTTP/1.0", 400 );
	private static final byte[] DIE = reply( "HTTP/1.0", 500 );

	/**
	 * The lifebeat socket idles between beats for the instance's lifebeat interval (30 seconds by default), so the
	 * connector's idle timeout doesn't apply. This one only reaps sockets whose peer vanished without closing them.
	 */
	private static final Duration IDLE_TIMEOUT = Duration.ofMinutes( 10 );

	/** Bytes received but not yet forming a complete beat. A beat is short; more than this pending is not a lifebeat. */
	private static final int MAX_PENDING = 8192;

	private final StringBuilder _pending = new StringBuilder();

	public WOLifebeatConnection( final EndPoint endPoint, final Executor executor ) {
		super( endPoint, executor );
	}

	private static byte[] reply( final String httpVersion, final int status ) {
		return (httpVersion + " " + status + " Apple WebObjects\r\n\r\n\r\n").getBytes( StandardCharsets.US_ASCII );
	}

	/**
	 * Receives the bytes the detector already read off the socket - the first beat - before {@link #onOpen}.
	 */
	@Override
	public void onUpgradeTo( final ByteBuffer buffer ) {
		if( buffer != null ) {
			append( buffer );
		}
	}

	@Override
	public void onOpen() {
		super.onOpen();
		getEndPoint().setIdleTimeout( IDLE_TIMEOUT.toMillis() );

		if( processPending() ) {
			fillInterested();
		}
	}

	@Override
	public void onFillable() {
		try {
			while( true ) {
				final ByteBuffer buffer = BufferUtil.allocate( 1024 );
				final int filled = getEndPoint().fill( buffer );

				if( filled > 0 ) {
					append( buffer );

					if( !processPending() ) {
						return;
					}
				}
				else if( filled == 0 ) {
					fillInterested();
					return;
				}
				else {
					// The instance closed its end - it stopped, died, or is reconnecting
					getEndPoint().close();
					return;
				}
			}
		}
		catch( final Throwable e ) {
			logger.debug( "Lifebeat connection from {} failed: {}", getEndPoint().getRemoteSocketAddress(), e.toString() );
			getEndPoint().close( e );
		}
	}

	private void append( final ByteBuffer buffer ) {
		// Lifebeats are ASCII, so decoding byte for char is exact
		_pending.append( StandardCharsets.ISO_8859_1.decode( buffer ) );
	}

	/**
	 * Answers every complete beat pending.
	 *
	 * @return false if the connection was closed
	 */
	private boolean processPending() {
		int end;

		while( (end = _pending.indexOf( "\r\n\r\n" )) >= 0 ) {
			final String requestLine = _pending.substring( 0, _pending.indexOf( "\r\n" ) );
			_pending.delete( 0, end + 4 );

			final byte[] reply = replyTo( requestLine );

			if( reply == null ) {
				getEndPoint().close();
				return false;
			}

			try( Blocker.Callback callback = Blocker.callback() ) {
				getEndPoint().write( callback, ByteBuffer.wrap( reply ) );
				callback.block();
			}
			catch( final Exception e ) {
				logger.debug( "Writing lifebeat reply to {} failed: {}", getEndPoint().getRemoteSocketAddress(), e.toString() );
				getEndPoint().close( e );
				return false;
			}
		}

		if( _pending.length() > MAX_PENDING ) {
			logger.warn( "Closing lifebeat connection from {}: {} bytes without a complete beat", getEndPoint().getRemoteSocketAddress(), _pending.length() );
			getEndPoint().close();
			return false;
		}

		return true;
	}

	/**
	 * @return The reply to the beat with the given request line, or null if the connection should be closed unanswered
	 */
	private byte[] replyTo( final String requestLine ) {

		// "GET /cgi-bin/WebObjects/wotaskd.woa/wlb?<query> HTTP/1.1"
		final String[] parts = requestLine.split( " " );

		if( parts.length != 3 || !parts[0].equals( "GET" ) || parts[1].indexOf( '?' ) < 0 ) {
			return BAD;
		}

		final String queryString = parts[1].substring( parts[1].indexOf( '?' ) + 1 );

		if( !(WOApplication.application().requestHandlerForKey( "wlb" ) instanceof LifebeatRequestHandler handler) ) {
			logger.error( "No lifebeat request handler registered - closing lifebeat connection" );
			return null;
		}

		final Verdict verdict = handler.process( queryString, remoteAddress() );

		return switch( verdict ) {
			case OK -> OK;
			case ACKNOWLEDGED -> ACKNOWLEDGED;
			case BAD -> BAD;
			case DIE -> DIE;
			// The classic adaptor answered these too: the handler's null response went out as an empty acknowledgement
			case REJECTED -> ACKNOWLEDGED;
		};
	}

	private InetAddress remoteAddress() {
		final SocketAddress address = getEndPoint().getRemoteSocketAddress();
		return address instanceof InetSocketAddress inetAddress ? inetAddress.getAddress() : null;
	}
}
