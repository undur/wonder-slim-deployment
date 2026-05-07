package sjip.wotaskd;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;

import sjip.core.model.MSiteConfig;

/**
 * Listens to Multicast Queries and UDP queries
 */

public class MulticastListener extends Thread {

	private static final Logger logger = LoggerFactory.getLogger( MulticastListener.class );

	private final int _port;
	private final boolean _shouldRespondToMulticast;
	private final String _multicastAddress;
	private final MSiteConfig _siteConfig;

	private MulticastSocket _socket;
	private InetAddress _address;

	public MulticastListener( final boolean shouldRespondToMulticast, final int port, final String multicastAddress, final MSiteConfig siteConfig ) {
		_shouldRespondToMulticast = shouldRespondToMulticast;
		_multicastAddress = multicastAddress;
		_port = port;
		
		// FIXME: The siteconfig is only for reporting errors. It's a bad mechanism and hopefully this parameter will get nuked soon // Hugi 2026-05-02
		_siteConfig = siteConfig;
	}

	private void createRequestSocket() {
		// Create a new MulticastSocket, even if we're not listening for Multicast
		// MulticastSocket acts just like a DatagramSocket
		try {
			_socket = new MulticastSocket( _port );
			if( !WOApplication.application()._unsetHost ) {
				_socket.setInterface( WOApplication.application().hostAddress() );
			}
		}
		catch( IOException exception ) {
			logger.error( "Unable to create multicast listener socket: " + exception );
			logger.error( "Port " + _port + " may be in use by another application." );
			logger.error( "Exiting..." );
			System.exit( 1 );
		}

		if( _shouldRespondToMulticast ) {
			try {
				_address = InetAddress.getByName( _multicastAddress );
			}
			catch( UnknownHostException exception ) {
				logger.error( "Error resolving address: " + _multicastAddress + " - " + exception );
				logger.error( "Exiting..." );
				System.exit( 1 );
			}

			if( !_address.isMulticastAddress() ) {
				logger.error( _address + " is not a valid multicast address" );
				logger.error( "Exiting..." );
				System.exit( 1 );
			}

			try {
				_socket.joinGroup( _address );
			}
			catch( IOException exception ) {
				logger.error( "Error joining multicast group: " + exception );
				logger.error( "Exiting..." );
				System.exit( 1 );
			}
		}
	}

	private void sendReplyWithLengthTo( byte[] aReplyBytes, int aReplyBytesLength, DatagramPacket incomingPacket ) {
		DatagramPacket outgoingPacket = new DatagramPacket( aReplyBytes, aReplyBytesLength, incomingPacket.getAddress(), incomingPacket.getPort() );

		try {
			_socket.send( outgoingPacket );
		}
		catch( IOException localException ) {
			logger.error( "Error sending reply: " + localException + " (ignored)" );
		}
	}

	private boolean byteArrayStartsWith( byte[] anArray, byte[] anotherArray, int aLength ) {
		for( int i = 0; i < aLength; i++ ) {
			if( anArray[i] != anotherArray[i] ) {
				return false;
			}
		}
		return true;
	}

	// This is the main thread - we just look for a UDP packet that matches a known signature.
	private void listenForRequests() {
		try {
			String myName = WOApplication.application().host().toLowerCase() + ":" + _port;

			byte[] multicastRequest;
			byte[] multicastReply;
			byte[] versionRequest;
			byte[] versionReply;

			multicastRequest = ("GET CONFIG-URL").getBytes( StandardCharsets.UTF_8 );
			multicastReply = ("http://" + myName + '\0').getBytes( StandardCharsets.UTF_8 );
			versionRequest = ("womp://queryVersion").getBytes( StandardCharsets.UTF_8 );
			versionReply = ("womp://replyVersion/" + myName + ":webObjects5.0" + '\0').getBytes( StandardCharsets.UTF_8 );

			int multicastRequestLength = multicastRequest.length;
			int multicast_reply_len = multicastReply.length;
			int versionRequestLength = versionRequest.length;
			int version_reply_len = versionReply.length;

			byte[] mbuffer = new byte[1000];
			DatagramPacket incomingPacket = new DatagramPacket( mbuffer, mbuffer.length );

			while( _socket != null ) {
				try {
					incomingPacket.setLength( mbuffer.length );
					_socket.receive( incomingPacket );
					if( byteArrayStartsWith( incomingPacket.getData(), multicastRequest, multicastRequestLength ) ) {
						// this responds with the DirectAction URL for getting our adaptor Config XML
						sendReplyWithLengthTo( multicastReply, multicast_reply_len, incomingPacket );
					}
					else if( byteArrayStartsWith( incomingPacket.getData(), versionRequest, versionRequestLength ) ) {
						// This is if someone asks us what version we are
						sendReplyWithLengthTo( versionReply, version_reply_len, incomingPacket );
					}
					else {
						// This is if we get an unrecognized packet.
						String key = incomingPacket.getAddress() + ":" + incomingPacket.getPort();

						_siteConfig.globalErrorDictionary.put( key, (myName + ": Unrecognized UDP packet: " + new String( incomingPacket.getData() ) + " from " + key + ". This may be an Application that conforms to an older protocol.") );
						logger.debug( myName + ": Unrecognized UDP packet: " + new String( incomingPacket.getData() ) + " from " + key + ". This may be an Application that conforms to an older protocol." );
					}
				}
				catch( IOException localException ) {
					logger.error( "Error receiving packet: " + localException + " (ignored)" );
				}

			}

			// Hari-kiri - but should never happen, of course.
			logger.error( "wotaskd listen thread exiting because of bad socket" );
		}
		catch( Throwable t ) {
			logger.error( "Listen thread exiting with exception: " + t );
			logger.debug( String.valueOf( t ) );
		}
		System.exit( 1 );
	}

	@Override
	public void run() {
		createRequestSocket();
		logger.debug( "Created UDP socket; listening for requests..." );
		listenForRequests();
	}
}