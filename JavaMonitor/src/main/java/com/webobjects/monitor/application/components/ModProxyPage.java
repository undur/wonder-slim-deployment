package com.webobjects.monitor.application.components;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor.application.MonitorComponent;

public class ModProxyPage extends MonitorComponent {

	public List<String> loadBalencers = new ArrayList<>( List.of( "byrequests", "bytraffic", "bybusyness" ) );
	public String loadBalancerItem;
	public String loadBalancer = "byrequests";
	public Integer timeout = Integer.valueOf( 0 );

	public ModProxyPage( WOContext aWocontext ) {
		super( aWocontext );
	}

	public WOActionResults reload() {
		return null;
	}

	public String modProxyContent() {
		return _generateModProxyConfig();
	}

	public String modRewriteContent() {
		return _generateModRewriteConfig();
	}

	/**
	 * Addded to allow for a null/no configured WO adaptor URL
	 * 
	 * FIXME: I'm not sure we should allow this, we should at least show a warning to the user if no adaptor URL is configured // Hugi 2024-11-06
	 */
	private String adaptorURL() {
		String adaptorURL = siteConfig().woAdaptor();

		if( adaptorURL == null ) {
			adaptorURL = application().cgiAdaptorURL();
		}
		
		return adaptorURL;
	}

	private String _generateModProxyConfig() {
		StringBuilder result = new StringBuilder();

		result.append( "#\n" );
		result.append( "# Common configuration (if not already set)\n" );
		result.append( "#\n" );
		result.append( "ProxyRequests Off\nProxyVia Full\n" );
		result.append( "#\n" );
		result.append( "# Give us a name\n" );
		result.append( "#\n" );
		result.append( "RequestHeader append x-webobjects-adaptor-version \"mod_proxy\"\n\n\n" );

		result.append( "#\n" );
		result.append( "# Balancer routes\n" );
		result.append( "#\n" );

		for( MApplication anApp : siteConfig().applicationArray() ) {
			String tmpAdaptor = adaptorURL();
			tmpAdaptor = removeEnd( tmpAdaptor, "/" );

			final List<String> tmpPath = Arrays.asList( tmpAdaptor.split( "/" ) );

			int count = tmpPath.size();
			String adaptorPath = "/" + tmpPath.get( count - 2 ) + "/" + tmpPath.get( count - 1 ) + "/";

			result.append( "<Proxy balancer://" + anApp.name() + ".woa>\n" );

			final List<String> reversePathes = new ArrayList<>();

			for( MInstance anInst : anApp.instanceArray() ) {
				String host = anInst.hostName();
				String port = anInst.port().toString();

				String url = "http://" + host + ":" + port + adaptorPath + anApp.name() + ".woa";

				result.append( "\tBalancerMember " );
				result.append( url );
				result.append( " route=" );
				result.append( _proxyBalancerRoute( anApp.name(), host, port ) );
				result.append( '\n' );

				reversePathes.add( url );
			}

			result.append( "</Proxy>\n" );
			result.append( "ProxyPass " );
			result.append( adaptorPath );
			result.append( anApp.name() );
			result.append( ".woa balancer://" );
			result.append( anApp.name() );
			result.append( ".woa stickysession=" );
			result.append( _proxyBalancerCookieName( anApp.name() ) );
			result.append( " nofailover=On\n" );

			for( int i = 0; i < reversePathes.size(); i++ ) {
				String url = reversePathes.get( i );
				result.append( "ProxyPassReverse / " );
				result.append( url );
				result.append( '\n' );
			}
			result.append( '\n' );

		}

		result.append( "#\n" );
		result.append( "# Balancer configuration\n" );
		result.append( "#\n" );
		for( MApplication anApp : siteConfig().applicationArray() ) {
			String name = anApp.name();
			result.append( "ProxySet balancer://" + name + ".woa" );
			if( timeout != null && timeout.intValue() > 0 ) {
				result.append( " timeout=" );
				result.append( timeout );
			}
			if( loadBalancer != null ) {
				result.append( " lbmethod=" );
				result.append( loadBalancer );
			}
			else {
				result.append( " lbmethod=byrequests" );
			}
			result.append( '\n' );
		}

		result.append( "#\n" );
		result.append( "#\n" );
		result.append( "#\n" );

		result.append( '\n' );
		return result.toString();
	}

	private static String _proxyBalancerRoute( String name, String host, String port ) {
		String proxyBalancerRoute = null;

		proxyBalancerRoute = (name + "_" + port).toLowerCase();
		proxyBalancerRoute = proxyBalancerRoute.replace( '.', '_' );

		return proxyBalancerRoute;
	}

	private static String _proxyBalancerCookieName( String name ) {
		String proxyBalancerCookieName = null;

		proxyBalancerCookieName = ("routeid_" + name).toLowerCase();
		proxyBalancerCookieName = proxyBalancerCookieName.replace( '.', '_' );

		return proxyBalancerCookieName;
	}

	private String _generateModRewriteConfig() {
		StringBuilder result = new StringBuilder();
		result.append( "This is the content of the apache conf file\n\n\n" );
		result.append( "#\n" );
		result.append( "# Rewrite Engine\n" );
		result.append( "#\n" );
		result.append( "RewriteEngine On\n\n" );
		result.append( "# Rewrite rules\n" );

		final List<String> rewriteRules = new ArrayList<>();
		final List<String> properitesRules = new ArrayList<>();

		for( MApplication anApp : siteConfig().applicationArray() ) {
			String tmpAdaptor = adaptorURL();
			tmpAdaptor = removeEnd( tmpAdaptor, "/" );

			List<String> tmpPath = Arrays.asList( tmpAdaptor.split( "/" ) );

			int count = tmpPath.size();
			String adaptorPath = "/" + tmpPath.get( count - 2 ) + "/" + tmpPath.get( count - 1 ) + "/";

			rewriteRules.add( "RewriteRule ^/" + anApp.name().toLowerCase() + "(.*)$ " + adaptorPath + anApp.name() + ".woa" );

			properitesRules.add( "er.extensions.ERXApplication.replaceApplicationPath.pattern=" + adaptorPath + anApp.name() + ".woa" );
			properitesRules.add( "er.extensions.ERXApplication.replaceApplicationPath.replace=/" + anApp.name().toLowerCase() );
		}

		result.append( String.join( "\n", rewriteRules ) );
		result.append( "\n" );
		result.append( "\n" );
		result.append( "\n" );
		result.append( "This is the content of the application properties file\n\n\n" );
		result.append( String.join( "\n", properitesRules ) );

		result.append( "\n" );

		result.append( '\n' );
		return result.toString();
	}

	/**
	 * Removes a substring only if it is at the end of a source string,
     * otherwise returns the source string.
     * 
	 * CHECKME: Shamelessly ripped from Apache commons StringUtils as a temporary measure.
	 */
	private static String removeEnd(final String str, final String remove) {
        if (isEmpty(str) || isEmpty(remove)) {
            return str;
        }
        if (str.endsWith(remove)) {
            return str.substring(0, str.length() - remove.length());
        }
        return str;
    }
	
	/**
	 * CHECKME: Shamelessly ripped from Apache commons StringUtils as a temporary measure.
	 */
	private static boolean isEmpty(final CharSequence cs) {
        return cs == null || cs.length() == 0;
    }
}