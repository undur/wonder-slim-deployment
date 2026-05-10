package sjip.wotaskd;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver._private.WODirectActionRequestHandler;

import er.extensions.appserver.ERXApplication;
import er.extensions.routes.RouteTable;
import sjip.core.model.MSiteConfig;
import sjip.x.FProperties;

public class Application extends ERXApplication {

	private static final Logger logger = LoggerFactory.getLogger( Application.class );

	private final InstanceController _instanceController;
	private final String _multicastAddress;
	private final boolean _shouldWriteAdaptorConfig;
	private final boolean _shouldRespondToMulticast;
	public final ReentrantReadWriteLock _lock;
	private MSiteConfig _siteConfig;

	static public void main( String argv[] ) {
		ERXApplication.main( argv, Application.class );
	}

	public Application() {
		
		// FIXME: I know.
		if( "hugi".equals( FProperties.sysProp( "user.name" ) ) ) {
			System.setProperty( "WODeploymentConfigurationDirectory", "/Users/hugi/Desktop/woconfig" );
		}

		_lock = new ReentrantReadWriteLock();

		// Required: keeps zero-length lifebeat responses small enough for the WOApp's
		// fixed-width parser in WOApplication._LifebeatThread.sendMessage() to read without
		// desyncing. See issue #21.
		com.webobjects.appserver._private.WOHttpIO._alwaysAppendContentLength = false;

		// Setting the ports
		_setLifebeatDestinationPort( port().intValue() );

		// Setting the multicast address
		_multicastAddress = FProperties.stringValue( FProperties.K.MULTICAST_ADDRESS );

		registerRequestHandler( new LifebeatRequestHandler( host() ), "wlb" );

		// unregistering the WOComponent / WOResource request handlers
		removeRequestHandlerForKey( "wo" );
		removeRequestHandlerForKey( "wr" );
		removeRequestHandlerForKey( "womp" );

		// getting the siteConfig (+ all Hosts, Apps, Instances) from disk
		_siteConfig = MSiteConfig.unarchiveSiteConfig( true );
		_siteConfig.archiveSiteConfig();

		// creating an InstanceController to control and query instances
		_instanceController = new InstanceController( host() );

		// checking to see if we should save WOConfig.xml to disk for the adaptors.
		_shouldWriteAdaptorConfig = FProperties.booleanValue( FProperties.K.SAVES_ADAPTOR_CONFIGURATION );

		if( _shouldWriteAdaptorConfig ) {
			_siteConfig.archiveAdaptorConfig();
		}

		// checking to see if we should respond to adaptor multicast queries
		// we will always respond to non-multicast UDP packets
		_shouldRespondToMulticast = FProperties.booleanValue( FProperties.K.RESPONDS_TO_MULTICAST_QUERY );

		if( _shouldRespondToMulticast ) {
			logger.info( "Multicast Response Enabled" );
		}
		else {
			logger.info( "Multicast Response Disabled" );
		}

		// Set up multicast listen thread
		new MulticastListener( shouldRespondToMulticast(), port().intValue(), multicastAddress(), siteConfig() ).start();

		// Requests to the root URL "/" were handled using the default request handler, which returned DirectAction.defaultAction()
		// Since wonder-slim uses routing for handling the root request, we register the root URL manually
		final WODirectActionRequestHandler rootRequestHandler = new WODirectActionRequestHandler( DirectAction.class.getName(), "default", false );
		RouteTable.defaultRouteTable().map( "/", routeInvocation -> rootRequestHandler.handleRequest( routeInvocation.request() ));

		FProperties.logCurrentValues( logger );
	}

	@Override
	public String name() {
		return "wotaskd";
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

	public InstanceController instanceController() {
		return _instanceController;
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
	// FIXME: This is horrid. Serialization should be triggered by changes in the model. See https://github.com/undur/wonder-slim-deployment/issues/18 // Hugi 2026-05-05
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