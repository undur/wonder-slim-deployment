package sjip.monitor;

/*
 © Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

 IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

 In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

 The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

 IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
 SUCH DAMAGE.
 */
import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

import er.extensions.appserver.ERXDirectAction;
import er.extensions.appserver.ERXWOContext;
import sjip.monitor.components.ApplicationsPage;
import sjip.monitor.components.JMLoginPage;
import sjip.monitor.util.JMLogFeed;
import sjip.monitor.util.StatsUtilitiesEvenMore;
import sjip.monitor.util.WOTaskdHandler;

public class DirectAction extends ERXDirectAction {

	public DirectAction( WORequest aRequest ) {
		super( aRequest );
	}

	@Override
	public WOActionResults defaultAction() {
		return frontPage( context() );
	}

	/**
	 * @return The front page, made in the given context: the applications, or the login page when a password is required
	 *         and the session isn't logged in (and the request carries no valid pw)
	 */
	public static WOActionResults frontPage( final WOContext context ) {
		final WOApplication application = WOApplication.application();
		final boolean loginRequired = WOTaskdHandler.siteConfig().isPasswordRequired();

		if( !loginRequired ) {
			return application.pageWithName( ApplicationsPage.class.getName(), context );
		}

		final Session session = (Session)((ERXWOContext)context).existingSession();

		if( session != null && session.isLoggedIn() ) {
			return application.pageWithName( ApplicationsPage.class.getName(), context );
		}

		final String password = context.request().stringFormValueForKey( "pw" );

		if( password != null && WOTaskdHandler.siteConfig().checkPasswordPlaintext( password )) {
			return application.pageWithName( ApplicationsPage.class.getName(), context );
		}

		return application.pageWithName( JMLoginPage.class.getName(), context );
	}

	public WOResponse statisticsAction() {
		final WOResponse response = new WOResponse();
		final String pw = context().request().stringFormValueForKey( "pw" );

		if( WOTaskdHandler.siteConfig().checkPasswordPlaintext( pw ) ) {
			response.appendContentString( StatsUtilitiesEvenMore.statisticsString() );
		}

		return response;
	}

	/**
	 * The SSE stream behind the "Live log" page: subscribes the caller to JMLogFeed's broadcast of the
	 * application's log. Gated like the regular UI - if a password is set, only a logged-in session may
	 * subscribe (EventSource sends the session cookie, so a session browsing the UI passes).
	 */
	public WOActionResults liveLogAction() {

		if( WOTaskdHandler.siteConfig().isPasswordRequired() ) {
			final Session session = (Session)existingSession();

			if( session == null || !session.isLoggedIn() ) {
				final WOResponse response = new WOResponse();
				response.setStatus( 403 );
				return response;
			}
		}

		return JMLogFeed.subscribe();
	}
}