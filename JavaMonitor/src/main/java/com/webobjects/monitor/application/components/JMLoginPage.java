package com.webobjects.monitor.application.components;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.monitor.application.Session;
import com.webobjects.monitor.util.WOTaskdHandler;

import er.extensions.components.ERXComponent;

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