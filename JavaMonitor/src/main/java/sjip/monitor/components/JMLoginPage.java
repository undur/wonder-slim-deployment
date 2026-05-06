package sjip.monitor.components;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;

import er.extensions.components.ERXComponent;
import sjip.monitor.Session;
import sjip.monitor.util.WOTaskdHandler;

public class JMLoginPage extends ERXComponent {

	public String password;
	public String message;

	public JMLoginPage( WOContext aWocontext ) {
		super( aWocontext );
	}

	public WOActionResults login() {

		boolean correctPassword = WOTaskdHandler.siteConfig().checkPasswordPlaintext( password );

		if( correctPassword ) {
			((Session)session()).setIsLoggedIn( true );
			return pageWithName( ApplicationsPage.class.getName() );
		}

		message = "Incorrect Password";
		password = null;

		return null;
	}
}