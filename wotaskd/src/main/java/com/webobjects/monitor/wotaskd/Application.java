package com.webobjects.monitor.wotaskd;

/*
© Copyright 2006 - 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
SUCH DAMAGE.
 */
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WORequestHandler;
import com.webobjects.appserver.WOResponse;
import com.webobjects.appserver._private.WODirectActionRequestHandler;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.monitor._private.StringExtensions;
import com.webobjects.monitor._private.model.MSiteConfig;

import er.extensions.appserver.ERXApplication;
import er.extensions.routes.RouteTable;
import x.FLog;

public class Application extends ERXApplication {
	
	private static final String _HTTP1 = "HTTP/1.0";

	private InstanceController _localMonitor;
	private MSiteConfig _siteConfig;
	private MulticastListener listenThread;
	private LifebeatRequestHandler _lifebeatRequestHandler;
	private Number _port;
	private int _intPort;
	private String _multicastAddress;
	private boolean _shouldWriteAdaptorConfig;
	private boolean _shouldRespondToMulticast;
	public ReentrantReadWriteLock _lock;

	static public void main( String argv[] ) {
		ERXApplication.main( argv, Application.class );
	}

	public Application() {
		super();
		
		// FIXME: I know.
		if( "hugi".equals( System.getProperty( "user.name" ) ) ) {
			System.setProperty( "WODeploymentConfigurationDirectory", "/Users/hugi/Desktop/woconfig" );
		}

		_lock = new ReentrantReadWriteLock();

		com.webobjects.appserver._private.WOHttpIO._alwaysAppendContentLength = false;

		// Setting the ports
		_setLifebeatDestinationPort( intPort() );

		// Setting the multicast Port
		_multicastAddress = System.getProperties().getProperty( "WOMulticastAddress" );
		if( _multicastAddress == null ) {
			_multicastAddress = "239.128.14.2";
		}

		// registering the lifebeat request handler
		_lifebeatRequestHandler = new LifebeatRequestHandler();
		registerRequestHandler( _lifebeatRequestHandler, "wlb" );

		// unregistering the WOComponent / WOResource request handlers
		removeRequestHandlerForKey( "wo" );
		removeRequestHandlerForKey( "wr" );
		removeRequestHandlerForKey( "womp" );

		// getting the siteConfig (+ all Hosts, Apps, Instances) from disk
		_siteConfig = MSiteConfig.unarchiveSiteConfig( true );
		_siteConfig.archiveSiteConfig();

		// creating the localMonitor (used to control and query instances)
		_localMonitor = new InstanceController();

		// checking to see if we should save WOConfig.xml to disk for the adaptors.
		String WOSavesAdaptorConfig = System.getProperties().getProperty( "WOSavesAdaptorConfiguration" );
		if( WOSavesAdaptorConfig != null ) {
			_shouldWriteAdaptorConfig = StringExtensions.boolValue( WOSavesAdaptorConfig );
			if( _shouldWriteAdaptorConfig ) {
				_siteConfig.archiveAdaptorConfig();
			}
		}
		else {
			_shouldWriteAdaptorConfig = false;
		}

		// checking to see if we should respond to adaptor multicast queries
		// we will always respond to non-multicast UDP packets
		String shouldMC = System.getProperties().getProperty( "WORespondsToMulticastQuery" );
		if( shouldMC != null ) {
			if( !StringExtensions.boolValue( shouldMC ) ) {
				_shouldRespondToMulticast = false;
				FLog.debug.appendln( "Multicast Response Disabled" );
			}
			else {
				_shouldRespondToMulticast = true;
				FLog.debug.appendln( "Multicast Response Enabled" );
			}
		}

		// Set up multicast listen thread
		createRequestListenerThread();

		// Requests to the root URL "/" were handled using the default request handler, which returned DirectAction.defaultAction()
		// Since wonder-slim uses routing for handling the root request, we register the root URL manually
		final WODirectActionRequestHandler rootRequestHandler = new WODirectActionRequestHandler( DirectAction.class.getName(), "default", false );
		RouteTable.defaultRouteTable().map( "/", routeInvocation -> rootRequestHandler.handleRequest( routeInvocation.request() ));
	}

	@Override
	public String name() {
		return "wotaskd";
	}

	@Override
	public Number port() {
		if( _port == null ) {
			if( super.port().intValue() > 0 ) {
				_port = super.port();
			}
			else {
				_port = Integer.valueOf( 1085 );
			}
			_intPort = _port.intValue();
		}
		return _port;
	}

	private int intPort() {
		return _intPort;
	}

	public String multicastAddress() {
		return _multicastAddress;
	}

	@Override
	public boolean allowsConcurrentRequestHandling() {
		return true;
	}

	public MSiteConfig siteConfig() {
		return _siteConfig;
	}

	public void setSiteConfig( MSiteConfig aConfig ) {
		// Don't need to call dataHasChanged, since a new MSiteConfig is already dirty
		_siteConfig = aConfig;
	}

	public InstanceController localMonitor() {
		return _localMonitor;
	}

	public boolean shouldWriteAdaptorConfig() {
		return _shouldWriteAdaptorConfig;
	}

	public boolean shouldRespondToMulticast() {
		return _shouldRespondToMulticast;
	}

	// sleep will check if there have been changes to the siteConfig.
	// if so, it will write the new siteConfig to disk as SiteConfig.xml
	// if requested, it will also write the new adaptorConfig to disk as WOConfig.xml
	@Override
	public void sleep() {
		_lock.readLock().lock();
		try {
			if( (_siteConfig != null) && (_siteConfig.hasChanges()) ) {
				// archiving the siteConfig
				_siteConfig.archiveSiteConfig();
				if( _shouldWriteAdaptorConfig ) {
					_siteConfig.archiveAdaptorConfig();
				}
				_siteConfig.resetChanges();
			}
		}
		finally {
			_lock.readLock().unlock();
		}
	}

	// creates and starts the ListenerThread inner class
	public void createRequestListenerThread() {
		FLog.debug.appendln( "Detaching request listen thread" );
		listenThread = new Application.MulticastListener( shouldRespondToMulticast(), intPort(), multicastAddress(), siteConfig() );
		listenThread.start();
	}

	// Overridden createRequest because WO ObjC apps send 'GET /... HTTP/1.0 ' (note extra space) which doesn't parse very well.
	public WORequest createRequest( String aMethod, String aURL, String anHTTPVersion, NSDictionary someHeaders, NSData aContent, NSDictionary someInfo ) {
		if( (anHTTPVersion == null) && (aURL != null) && (aURL.endsWith( " HTTP/1.0" )) ) {
			anHTTPVersion = _HTTP1;
			aURL = aURL.substring( 0, (aURL.length() - _HTTP1.length() - 1) );
		}
		return super.createRequest( aMethod, aURL, anHTTPVersion, someHeaders, aContent, someInfo );
	}

	// overridden dispatch of requests, for faster lifebeat checking
	// if it's a lifebeat, we return a null response, and that should close the socket immediately
	@Override
	public WOResponse dispatchRequest( WORequest request ) {

//		logger.info( " ======= >> REQUEST ========" );
//		logger.info( "{}", request );

		final WORequestHandler handler = handlerForRequest( request );

		if( (handler != null) && (handler == _lifebeatRequestHandler) ) {
			_TheLastApplicationAccessTime = System.currentTimeMillis();
			final WOResponse response = handler.handleRequest( request );

//			logger.info( " ======= >> RESPONSE (lifebeat) ========" );
//			logger.info( "{}", response );
			return response;
		}

		final WOResponse response = super.dispatchRequest( request );
//		logger.info( " ======= >> RESPONSE ========" );
//		logger.info( "{}", response );
//		logger.info( "{}", response.contentString() );
		
		return response;
	}

	// Inner class used to listen to Multicast Queries and UDP queries
	public static class MulticastListener extends Thread {

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
				FLog.err.appendln( "Unable to create multicast listener socket: " + exception );
				FLog.err.appendln( "Port " + _port + " may be in use by another application." );
				FLog.err.appendln( "Exiting..." );
				System.exit( 1 );
			}

			if( _shouldRespondToMulticast ) {
				try {
					_address = InetAddress.getByName( _multicastAddress );
				}
				catch( UnknownHostException exception ) {
					FLog.err.appendln( "Error resolving address: " + _multicastAddress + " - " + exception );
					FLog.err.appendln( "Exiting..." );
					System.exit( 1 );
				}

				if( !_address.isMulticastAddress() ) {
					FLog.err.appendln( _address + " is not a valid multicast address" );
					FLog.err.appendln( "Exiting..." );
					System.exit( 1 );
				}

				try {
					_socket.joinGroup( _address );
				}
				catch( IOException exception ) {
					FLog.err.appendln( "Error joining multicast group: " + exception );
					FLog.err.appendln( "Exiting..." );
					System.exit( 1 );
				}
			}
		}

		public void closeRequestSocket() {
			try {
				_socket.leaveGroup( _address );
				FLog.debug.appendln( "Leaving multicast group" );
			}
			catch( IOException exception ) {
				FLog.debug.appendln( "Error leaving multicast group " + exception );
				return;
			}
			FLog.debug.appendln( "Closing request listen socket" );
			_socket.close();
		}

		public void sendReplyWithLengthTo( byte[] aReplyBytes, int aReplyBytesLength, DatagramPacket incomingPacket ) {
			DatagramPacket outgoingPacket = new DatagramPacket( aReplyBytes, aReplyBytesLength, incomingPacket.getAddress(), incomingPacket.getPort() );

			try {
				_socket.send( outgoingPacket );
			}
			catch( IOException localException ) {
				FLog.err.appendln( "Error sending reply: " + localException + " (ignored)" );
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
		public void listenForRequests() {
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
							FLog.debug.appendln( myName + ": Unrecognized UDP packet: " + new String( incomingPacket.getData() ) + " from " + key + ". This may be an Application that conforms to an older protocol." );
						}
					}
					catch( IOException localException ) {
						FLog.err.appendln( "Error receiving packet: " + localException + " (ignored)" );
					}

				}

				// Hari-kiri - but should never happen, of course.
				FLog.err.appendln( "wotaskd listen thread exiting because of bad socket" );
			}
			catch( Throwable t ) {
				FLog.err.appendln( "Listen thread exiting with exception: " + t );
				FLog.debug.appendln( t );
			}
			System.exit( 1 );
		}

		@Override
		public void run() {
			createRequestSocket();
			FLog.debug.appendln( "Created UDP socket; listening for requests..." );
			listenForRequests();
		}
	}
}
