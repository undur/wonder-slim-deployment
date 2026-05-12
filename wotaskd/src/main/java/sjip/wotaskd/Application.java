package sjip.wotaskd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver._private.WODirectActionRequestHandler;

import er.extensions.appserver.ERXApplication;
import er.extensions.routes.RouteTable;
import sjip.core.model.MSiteConfig;
import sjip.x.FProperties;

public class Application extends ERXApplication {

	private static final Logger logger = LoggerFactory.getLogger( Application.class );

	private final AppTaskd _appTaskd;

	static public void main( String argv[] ) {
		ERXApplication.main( argv, Application.class );
	}

	public Application() {
		
		// Required: keeps zero-length lifebeat responses small enough for the WOApp's
		// fixed-width parser in WOApplication._LifebeatThread.sendMessage() to read without
		// desyncing. See issue #21.
		com.webobjects.appserver._private.WOHttpIO._alwaysAppendContentLength = false;
		
		// Sets the WOLifebeatDestinationPort property to wotaskd's own listening port.
		// Wotaskd itself doesn't send lifebeats, so the setter's literal name is a misnomer
		// here — the value gets read later, on wotaskd's side, when building a launch
		// command for a managed instance: the {@code -WOLifebeatDestinationPort <port>}
		// argument added to that command tells the spawned WO app where to send its
		// lifebeats. Without this call, that argument would carry whatever default the
		// JVM's WOLifebeatDestinationPort property happened to have at startup, which
		// isn't guaranteed to match the port wotaskd is actually listening on.
		// FIXME: Declare our own port-publishing mechanism that doesn't piggyback on the
		// confusingly-named WO lifebeat property — the launch-command builder should read
		// wotaskd's port from a clearly-named platform value, not from a WO global.
		_setLifebeatDestinationPort( port().intValue() );

		registerRequestHandler( new LifebeatRequestHandler( host() ), "wlb" );
		
		// unregistering the WOComponent / WOResource request handlers
		removeRequestHandlerForKey( "wo" );
		removeRequestHandlerForKey( "wr" );
		removeRequestHandlerForKey( "womp" );

		_appTaskd = new AppTaskd( host(), port().intValue() );

		// Requests to the root URL "/" were handled using the default request handler, which returned DirectAction.defaultAction()
		// Since wonder-slim uses routing for handling the root request, we register the root URL manually
		final WODirectActionRequestHandler rootRequestHandler = new WODirectActionRequestHandler( DirectAction.class.getName(), "default", false );
		RouteTable.defaultRouteTable().map( "/", routeInvocation -> rootRequestHandler.handleRequest( routeInvocation.request() ));

		FProperties.logCurrentValues( logger );
	}

	public AppTaskd appTaskd() {
		return _appTaskd;
	}

	@Override
	public String name() {
		return "wotaskd";
	}

	@Override
	public boolean allowsConcurrentRequestHandling() {
		return true;
	}

	@Deprecated
	public String multicastAddress() {
		return appTaskd().multicastAddress();
	}

	@Deprecated
	public MSiteConfig siteConfig() {
		return appTaskd().siteConfig();
	}

	@Deprecated
	public void setSiteConfig( MSiteConfig aConfig ) {
		// Don't need to call dataHasChanged, since a new MSiteConfig is already dirty
		appTaskd().setSiteConfig( aConfig );
	}

	@Deprecated
	public InstanceController instanceController() {
		return appTaskd().instanceController();
	}

	@Deprecated
	public boolean shouldWriteAdaptorConfig() {
		return appTaskd().shouldWriteAdaptorConfig();
	}

	@Deprecated
	public boolean shouldRespondToMulticast() {
		return appTaskd().shouldRespondToMulticast();
	}

	// sleep will check if there have been changes to the siteConfig.
	// if so, it will write the new siteConfig to disk as SiteConfig.xml
	// if requested, it will also write the new adaptorConfig to disk as WOConfig.xml
	// FIXME: This is horrid. Serialization should be triggered by changes in the model. See https://github.com/undur/wonder-slim-deployment/issues/18 // Hugi 2026-05-05
	@Override
	public void sleep() {
		appTaskd().lock().readLock().lock();
		try {
			if( (siteConfig() != null) && siteConfig().hasChanges() ) {
				// archiving the siteConfig
				siteConfig().archiveSiteConfig();
				if( shouldWriteAdaptorConfig() ) {
					siteConfig().archiveAdaptorConfig();
				}
				siteConfig().resetChanges();
			}
		}
		finally {
			appTaskd().lock().readLock().unlock();
		}
	}
}