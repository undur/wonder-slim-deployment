package com.webobjects.monitor.wotaskd;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver._private.WODirectActionRequestHandler;
import com.webobjects.monitor._private.StringExtensions;
import com.webobjects.monitor._private.model.MSiteConfig;

import er.extensions.appserver.ERXApplication;
import er.extensions.routes.RouteTable;

public class Application extends ERXApplication {

	private static final Logger logger = LoggerFactory.getLogger( Application.class );

	private final InstanceController _localMonitor;
	private final String _multicastAddress;
	private final boolean _shouldWriteAdaptorConfig;
	private final boolean _shouldRespondToMulticast;
	public final ReentrantReadWriteLock _lock;
	private final MulticastListener listenThread; // CHECKME: We probably don't need this variable, probably just an old precautionary "Don't GC me bro" // Hugi 2026-05-05
	private MSiteConfig _siteConfig;
	private Number _port;
	private int _intPort;

	static public void main( String argv[] ) {
		ERXApplication.main( argv, Application.class );
	}

	public Application() {
		
		// FIXME: I know.
		if( "hugi".equals( System.getProperty( "user.name" ) ) ) {
			System.setProperty( "WODeploymentConfigurationDirectory", "/Users/hugi/Desktop/woconfig" );
		}

		_lock = new ReentrantReadWriteLock();

		// Setting the ports
		_setLifebeatDestinationPort( intPort() );

		// Setting the multicast address
		_multicastAddress = System.getProperty( "WOMulticastAddress", "239.128.14.2" );

		registerRequestHandler( new LifebeatRequestHandler(), "wlb" );

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
		_shouldWriteAdaptorConfig = StringExtensions.boolValue( System.getProperty( "WOSavesAdaptorConfiguration" ) );

		if( _shouldWriteAdaptorConfig ) {
			_siteConfig.archiveAdaptorConfig();
		}

		// checking to see if we should respond to adaptor multicast queries
		// we will always respond to non-multicast UDP packets
		_shouldRespondToMulticast = StringExtensions.boolValue( System.getProperty( "WORespondsToMulticastQuery" ) );

		if( _shouldRespondToMulticast ) {
			logger.info( "Multicast Response Enabled" );
		}
		else {
			logger.info( "Multicast Response Disabled" );
		}

		// Set up multicast listen thread
		listenThread = new MulticastListener( shouldRespondToMulticast(), intPort(), multicastAddress(), siteConfig() );
		listenThread.start();

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
			if( (_siteConfig != null) && _siteConfig.hasChanges() ) {
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
}