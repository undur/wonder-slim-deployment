package sjip.monitor.test;

import java.util.ArrayList;
import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WODirectAction;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

import sjip.core.model.MApplication;
import sjip.core.model.MHost;
import sjip.core.model.MInstance;
import sjip.core.model.MSiteConfig;
import sjip.monitor.util.WOTaskdHandler;

/**
 * Programmatic direct-action entry points that drive JavaMonitor's
 * {@link WOTaskdHandler} send methods over HTTP. Intended for the system-test
 * harness ({@code sjip-system-tests}) to exercise JavaMonitor's wire-construction
 * code paths end-to-end against a real wotaskd subprocess.
 *
 * <p>This is <strong>not</strong> a public API. It sits in
 * {@code sjip.monitor.test} (not {@code sjip.monitor.admin}) to keep test
 * affordances visibly separate from the operator-facing
 * {@code AdminAction} surface, which carries its own documented contract.
 *
 * <p>Every action here is gated behind the {@code sjip.testDirectActions.enabled}
 * system property. When the property is absent or any value other than
 * {@code true}, every action returns HTTP 403 without doing anything. The
 * test harness sets the property at JavaMonitor startup; production
 * deployments do not, so the endpoints refuse to run there.
 *
 * <p>The class-level property check in {@link #performActionNamed} is the
 * gate that matters: WO's default direct-action handler resolves
 * {@code WODirectAction} subclasses by classname, so the class is
 * reachable at {@code /wa/TestDirectAction/<action>} regardless of whether
 * the {@code /test/} convenience prefix is registered.
 *
 * <h2>URL surface</h2>
 *
 * <ul>
 *   <li>{@code POST /cgi-bin/WebObjects/JavaMonitor.woa/test/addHost} — form values: {@code name}, {@code hostType}</li>
 *   <li>{@code POST .../test/removeHost} — form value: {@code name}</li>
 *   <li>{@code POST .../test/addApplication} — form value: {@code name}</li>
 *   <li>{@code POST .../test/removeApplication} — form value: {@code name}</li>
 *   <li>{@code POST .../test/configureApplication} — form values: {@code name} plus any application fields to set; optional {@code oldname} for rename</li>
 *   <li>{@code POST .../test/addInstance} — form values: {@code applicationName}, {@code hostName}, {@code id}, {@code port}</li>
 *   <li>{@code POST .../test/removeInstance} — form values: {@code hostName}, {@code port}</li>
 *   <li>{@code POST .../test/configureInstance} — form values: {@code applicationName}, {@code hostName}, {@code port} plus any instance fields to set; optional {@code oldport} for port change</li>
 * </ul>
 *
 * <p>The wotaskd-side wire vocabulary that each of these triggers is
 * documented in {@code sjip-system-tests/PROTOCOL.md}.
 */
public class TestDirectAction extends WODirectAction {

	private static final String ENABLE_PROPERTY = "sjip.testDirectActions.enabled";

	private final WOTaskdHandler _handler;

	public TestDirectAction( final WORequest request ) {
		super( request );
		_handler = new WOTaskdHandler();
	}

	@Override
	public WOActionResults performActionNamed( final String name ) {
		if( !"true".equalsIgnoreCase( System.getProperty( ENABLE_PROPERTY ) ) ) {
			final WOResponse response = new WOResponse();
			response.setStatus( 403 );
			response.setContent( "test direct actions disabled; set -D" + ENABLE_PROPERTY + "=true to enable" );
			return response;
		}
		try {
			return super.performActionNamed( name );
		}
		catch( final IllegalArgumentException e ) {
			final WOResponse response = new WOResponse();
			response.setStatus( 406 );
			response.setContent( "bad request: " + e.getMessage() );
			return response;
		}
		catch( final RuntimeException e ) {
			final WOResponse response = new WOResponse();
			response.setStatus( 500 );
			response.setContent( "internal error: " + e.getMessage() );
			e.printStackTrace();
			return response;
		}
	}

	public WOActionResults addHostAction() {
		final String name = required( "name" );
		final String hostType = required( "hostType" );

		_handler.whileWriting( () -> {
			final MHost host = new MHost( siteConfig(), name, hostType.toUpperCase() );

			final List<MHost> peerWotaskds = new ArrayList<>( siteConfig().hostArray() );
			siteConfig().addHost_M( host );

			_handler.sendOverwriteToWotaskd( host );

			if( !peerWotaskds.isEmpty() ) {
				_handler.sendAddHostToWotaskds( host, peerWotaskds );
			}
		} );

		return ok();
	}

	public WOActionResults removeHostAction() {
		final String name = required( "name" );

		_handler.whileWriting( () -> {
			final MHost host = siteConfig().hostWithName( name );
			if( host == null ) {
				throw new IllegalArgumentException( "no host named '" + name + "'" );
			}

			final List<MHost> remainingPeers = new ArrayList<>( siteConfig().hostArray() );
			remainingPeers.remove( host );
			siteConfig().removeHost_M( host );

			// Only notify peers if any remain — sendRemoveHostToWotaskds dereferences
			// hosts[0] without a length check.
			if( !remainingPeers.isEmpty() ) {
				_handler.sendRemoveHostToWotaskds( host, remainingPeers );
			}
		} );

		return ok();
	}

	public WOActionResults addApplicationAction() {
		final String name = required( "name" );

		_handler.whileWriting( () -> {
			final MApplication app = new MApplication( name, siteConfig() );
			siteConfig().addApplication_M( app );

			_handler.sendAddApplicationToWotaskds( app, new ArrayList<>( siteConfig().hostArray() ) );
		} );

		return ok();
	}

	public WOActionResults removeApplicationAction() {
		final String name = required( "name" );

		_handler.whileWriting( () -> {
			final MApplication app = siteConfig().applicationWithName( name );
			if( app == null ) {
				throw new IllegalArgumentException( "no application named '" + name + "'" );
			}

			siteConfig().removeApplication_M( app );

			_handler.sendRemoveApplicationToWotaskds( app, new ArrayList<>( siteConfig().hostArray() ) );
		} );

		return ok();
	}

	public WOActionResults configureApplicationAction() {
		final String name = required( "name" );

		_handler.whileWriting( () -> {
			final MApplication app = siteConfig().applicationWithName( name );
			if( app == null ) {
				throw new IllegalArgumentException( "no application named '" + name + "'" );
			}

			final String newName = optional( "newName" );
			if( newName != null ) {
				// MApplication.setName automatically records the previous name as "oldname"
				// in the values dict, which is what wotaskd's configure path looks up.
				app.setName( newName );
			}

			final String autoRecover = optional( "autoRecover" );
			if( autoRecover != null ) {
				app.setAutoRecover( Boolean.valueOf( autoRecover ) );
			}

			_handler.sendUpdateApplicationToWotaskds( app, new ArrayList<>( siteConfig().hostArray() ) );
		} );

		return ok();
	}

	public WOActionResults addInstanceAction() {
		final String applicationName = required( "applicationName" );
		final String hostName = required( "hostName" );
		final Integer id = Integer.valueOf( required( "id" ) );
		final Integer port = Integer.valueOf( required( "port" ) );

		_handler.whileWriting( () -> {
			final MHost host = siteConfig().hostWithName( hostName );
			if( host == null ) {
				throw new IllegalArgumentException( "no host named '" + hostName + "'" );
			}
			final MApplication app = siteConfig().applicationWithName( applicationName );
			if( app == null ) {
				throw new IllegalArgumentException( "no application named '" + applicationName + "'" );
			}

			final MInstance instance = new MInstance( host, app, id, siteConfig() );
			instance.setPort( port );
			// addInstance_W (not _M) since we already hold the write lock via whileWriting,
			// and _M's auto-allocation flow (addInstances_M with count) is for the UI path.
			siteConfig().addInstance_W( instance );

			_handler.sendAddInstancesToWotaskds( List.of( instance ), new ArrayList<>( siteConfig().hostArray() ) );
		} );

		return ok();
	}

	public WOActionResults removeInstanceAction() {
		final String hostName = required( "hostName" );
		final Integer port = Integer.valueOf( required( "port" ) );

		_handler.whileWriting( () -> {
			final MInstance instance = siteConfig().instanceWithHostnameAndPort( hostName, port );
			if( instance == null ) {
				throw new IllegalArgumentException( "no instance at " + hostName + ":" + port );
			}

			siteConfig().removeInstance_M( instance );

			_handler.sendRemoveInstancesToWotaskds( List.of( instance ), new ArrayList<>( siteConfig().hostArray() ) );
		} );

		return ok();
	}

	public WOActionResults configureInstanceAction() {
		final String hostName = required( "hostName" );
		final Integer port = Integer.valueOf( required( "port" ) );

		_handler.whileWriting( () -> {
			final MInstance instance = siteConfig().instanceWithHostnameAndPort( hostName, port );
			if( instance == null ) {
				throw new IllegalArgumentException( "no instance at " + hostName + ":" + port );
			}

			final String newPort = optional( "newPort" );
			if( newPort != null ) {
				instance.setPort( Integer.valueOf( newPort ) );
			}

			_handler.sendUpdateInstancesToWotaskds( List.of( instance ), new ArrayList<>( siteConfig().hostArray() ) );
		} );

		return ok();
	}

	private String required( final String formKey ) {
		final String value = (String)context().request().formValueForKey( formKey );
		if( value == null || value.isEmpty() ) {
			throw new IllegalArgumentException( formKey + " form value is required" );
		}
		return value;
	}

	private String optional( final String formKey ) {
		final String value = (String)context().request().formValueForKey( formKey );
		return (value == null || value.isEmpty()) ? null : value;
	}

	private static MSiteConfig siteConfig() {
		return WOTaskdHandler.siteConfig();
	}

	private static WOResponse ok() {
		final WOResponse response = new WOResponse();
		response.setContent( "OK" );
		return response;
	}
}
