package sjip.monitor.components;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOResponse;

import sjip.core.MUtil;
import sjip.core.model.MHost;
import sjip.core.x.XUtil;
import sjip.monitor.MonitorComponent;
import sjip.monitor.components.ConfirmationPage.ConfirmationDelegate;
import sjip.monitor.util.JMUtil;

public class HostsPage extends MonitorComponent {

	public MHost currentHost;
	public String newHostName;
	public String hostTypeSelection = "Unix";
	public List<String> hostTypeList = MUtil.HOST_TYPES;

	public HostsPage( WOContext aWocontext ) {
		super( aWocontext );
		handler().updateForPage( getClass() );
	}

	public WOComponent addHostClicked() {

		if( newHostName != null && (newHostName.length() > 0) && (XUtil.isValidXMLString( newHostName )) ) {
			try {
				InetAddress anAddress = InetAddress.getByName( newHostName );

				handler().whileWriting( () -> {
					String nullOrError = null;

					if( newHostName.equalsIgnoreCase( "localhost" ) || newHostName.equals( "127.0.0.1" ) ) {
						// only allow this to happen if we have no other hosts!
						if( !((siteConfig().hostArray() != null) && (siteConfig().hostArray().size() == 0)) ) {
							// we're OK to add localhost.
							nullOrError = "Hosts named localhost or 127.0.0.1 may not be added while other hosts are configured.";
						}
					}
					else {
						// this is for non-localhost hosts
						// only allow this to happen if localhost/127.0.0.1 doesn't already exist!
						if( siteConfig().localhostOrLoopbackHostExists() ) {
							nullOrError = "Additional hosts may not be added while a host named localhost or 127.0.0.1 is configured.";
						}
					}

					if( (nullOrError == null) && (siteConfig().hostWithAddress( anAddress ) == null) ) {
						if( JMUtil.hostMeetsMinimumVersion( anAddress ) ) {

							MHost host = new MHost( siteConfig(), newHostName, hostTypeSelection.toUpperCase() );

							// To avoid overwriting hosts
							final List<MHost> tempHostArray = new ArrayList( siteConfig().hostArray() );
							siteConfig().addHost_M( host );

							handler().sendOverwriteToWotaskd( host );

							if( tempHostArray.size() != 0 ) {
								handler().sendAddHostToWotaskds( host, tempHostArray );
							}

						}
						else {
							session().addErrorIfAbsent( "The wotaskd on " + newHostName + " is an older version, please upgrade before adding..." );
						}
					}
					else {
						if( nullOrError != null ) {
							session().addErrorIfAbsent( nullOrError );
						}
						else {
							session().addErrorIfAbsent( "The host " + newHostName + " has already been added" );
						}
					}
				});
			}
			catch( UnknownHostException ex ) {
				session().addErrorIfAbsent( "ERROR: Cannot find IP address for hostname: " + newHostName );
			}
		}
		else {
			session().addErrorIfAbsent( newHostName + " is not a valid hostname" );
		}
		newHostName = null;

		return HostsPage.create( context() );
	}

	public WOComponent removeHostClicked() {

		final MHost host = currentHost;

		return ConfirmationPage.create( context(), new ConfirmationDelegate(
				HOST_PAGE,
				"Are you sure you want to delete the host <I>" + host.name() + "</I>?",
				"Selecting 'Yes' will shutdown any running instances of this host, and remove those instance configurations.",
				() -> {
					handler().whileWriting( () -> {
						siteConfig().removeHost_M( host );
						final List<MHost> tempHostArray = new ArrayList( siteConfig().hostArray() );
						tempHostArray.add( host );

						handler().sendRemoveHostToWotaskds( host, tempHostArray );
					});

					return HostsPage.create( context() );
				},
				() -> HostsPage.create( context() ) ) );
	}

	public WOComponent configureHostClicked() {
		return HostConfigurePage.create( context(), currentHost );
	}

	public WOActionResults displayWotaskdInfoClicked() {
		final String hostName = currentHost.name();
		final int port = application().lifebeatDestinationPort();

		final WOResponse response = new WOResponse();
		response.setHeader( "text/html", "content-type" );
		response.setContent( JMUtil.fetchWotaskdConfigurationString( hostName, port, siteConfig().passwordForRequest() ) );
		return response;
	}

	public static HostsPage create( WOContext context ) {
		return (HostsPage)context.page().pageWithName( HostsPage.class.getName() );
	}
}