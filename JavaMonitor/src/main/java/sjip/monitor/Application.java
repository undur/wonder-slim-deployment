package sjip.monitor;

/*
 © Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

 IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

 In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

 The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

 IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
 SUCH DAMAGE.
 */
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver._private.WODirectActionRequestHandler;
import com.webobjects.foundation.NSArray;

import er.extensions.appserver.ERXApplication;
import er.extensions.routes.RouteTable;
import sjip.monitor.admin.AdminAction;
import sjip.monitor.util.WOTaskdHandler;
import sjip.x.FProperties;

public class Application extends ERXApplication {

	public static void main( String argv[] ) {
		ERXApplication.main( argv, Application.class );
	}

	public Application() {

		// FIXME: I know. We need a better method to enter "test" mode // Hugi 2026-05-01
		if( "hugi".equals( FProperties.sysProp( "user.name" ) ) ) {
			System.setProperty( FProperties.K.DEPLOYMENT_CONFIGURATION_DIRECTORY.name(), "/Users/hugi/Desktop/woconfig" );
		}

		WOTaskdHandler.createSiteConfig();

		setAllowsConcurrentRequestHandling( true );

		registerRequestHandler( new WODirectActionRequestHandler() {
			@Override
			public NSArray getRequestHandlerPathForRequest( WORequest worequest ) {
				NSArray nsarray = new NSArray( AdminAction.class.getName() );
				return nsarray.arrayByAddingObject( worequest.requestHandlerPath() );
			}

		}, "admin" );

		
		// Requests to the root URL "/" were handled using the default request handler, which returned DirectAction.defaultAction()
		// Since wonder-slim uses routing for handling the root request, we register the root URL manually
		final WODirectActionRequestHandler rootRequestHandler = new WODirectActionRequestHandler( DirectAction.class.getName(), "default", false );
		RouteTable.defaultRouteTable().map( "/", routeInvocation -> rootRequestHandler.handleRequest( routeInvocation.request() ));
		
		// FIXME: This should be handled by ERExtensions // Hugi 2026-05-05
		final String defaultRoute = adaptorPath() + "/" + name() + ".woa";
		RouteTable.defaultRouteTable().map( defaultRoute, routeInvocation -> rootRequestHandler.handleRequest( routeInvocation.request() ));
	}
}