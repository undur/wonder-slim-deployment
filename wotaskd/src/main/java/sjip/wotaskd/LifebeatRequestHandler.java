package sjip.wotaskd;
/*
� Copyright 2006 - 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. (�Apple�) in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple�s copyrights in this original Apple software (the �Apple Software�), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
SUCH DAMAGE.
 */

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WORequestHandler;
import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSArray;

import sjip.core.model.MInstance;
import sjip.x.FHosts;

public class LifebeatRequestHandler extends WORequestHandler {

	private static final Logger log = LoggerFactory.getLogger( LifebeatRequestHandler.class );

	private static final WOResponse BAD_LIFEBEAT_RESPONSE = constantResponse( 400, "HTTP/1.0" ); // Bad Request
	private static final WOResponse GOOD_RESPONSE = constantResponse( 200, "HTTP/1.1" ); // OK
	private static final WOResponse DIE_RESPONSE = constantResponse( 500, "HTTP/1.0" ); // InternalServerError -> Die Immediately

	private final Application theApplication;
	private final String myHostName;

	public LifebeatRequestHandler() {
		theApplication = ((Application)WOApplication.application());
		myHostName = theApplication.hostAddress().getHostName();
	}

	private static WOResponse constantResponse( int status, String httpVersion ) {
		final WOResponse r = new WOResponse();
		r.setStatus( status );
		r.setHTTPVersion( httpVersion );
		return r;
	}

	@Override
	public WOResponse handleRequest( WORequest aRequest ) {

		// Sadly, we do regenerate in the case of random lifebeats. Hopefully this won't be too often.
		// Didn't pull this out so that we can rely on isUsingWebServer to catch some bad requests
		if( !FHosts.isUsingWebServer( aRequest.headers() ) && FHosts.isConfiguredHostAddress( aRequest._originatingAddress(), true ) ) {
			final Object lock = WOApplication.application().requestHandlingLock();

			if( lock != null ) {
				synchronized( lock ) {
					return _handleRequest( aRequest );
				}
			}

			return _handleRequest( aRequest );
		}

		return null;
	}

	private WOResponse _handleRequest( WORequest aRequest ) {

		WOResponse aResponse = BAD_LIFEBEAT_RESPONSE;

		// http://localhost:1085/cgi-bin/WebObjects/wotaskd.woa/wlb?<notification name>&<instance name>&<hostname>&<port>
		// <notification name> = "hasStarted", "lifebeat", "willStop", "willCrash"

		final List<String> values = NSArray.componentsSeparatedByString( aRequest.queryString(), "&" );

		if( (values == null) || (values.size() != 4) ) {
			theApplication.siteConfig().globalErrorDictionary.put( aRequest.queryString(), (myHostName + ": Received bad lifebeat: " + aRequest.queryString()) );
			log.error( "{}: Received bad lifebeat: {}", myHostName, aRequest.queryString() );
		}
		else {
			final String notificationType = values.get( 0 );
			final String instanceName = values.get( 1 );
			final String host = values.get( 2 );
			final String port = values.get( 3 );

			if( log.isDebugEnabled() ) {
				log.debug( "Received app comms: %s %s %s %s".formatted( notificationType, instanceName, host, port) );
			}

			if( notificationType.equals( "lifebeat" ) ) {
				// app is still alive - update registration
				// if app is not yet registered, register
				// if the instance should die, return DieResponse
				if( registerLifebeat( instanceName, host, port ) == false ) {
					aResponse = DIE_RESPONSE;
				}
				else {
					aResponse = GOOD_RESPONSE;
				}
			}
			else if( notificationType.equals( "hasStarted" ) ) {
				// app has just started - register instance
				registerStart( instanceName, host, port );
				aResponse = GOOD_RESPONSE;
			}
			else if( notificationType.equals( "willStop" ) ) {
				// app will stop - mark as dead
				registerStop( instanceName, host, port );
				aResponse = null;
			}
			else if( notificationType.equals( "willCrash" ) ) {
				// app will crash - mark as dead, email notification
				registerCrash( instanceName, host, port );
				aResponse = null;
			}
			else {
				theApplication.siteConfig().globalErrorDictionary.put( aRequest.queryString(), (myHostName + ": Received bad lifebeat: " + aRequest.queryString()) );
				log.error( "{}: Received bad lifebeat: {}", myHostName, aRequest.queryString() );
			}
		}

		// Returning null here used to bypass response generation entirely, back when wotaskd's Application overrode dispatchRequest()
		// to fast-path lifebeats (see commit 51c4677, issue #19). With the override gone, super.dispatchRequest upgrades null to
		// an empty WOResponse, so this branch no longer expresses a meaningful behavior. Pending verification — issue #32.
		if( "HTTP/1.0".equals( aRequest.httpVersion() ) ) {
			aResponse = null;
		}

		return aResponse;
	}

	private void registerStart( String instanceName, String host, String port ) {

		// KH - can we cache this for better speed?
		final InetAddress hostAddress = addressForName( host );

		theApplication._lock.readLock().lock();

		try {
			final MInstance instance = ((Application)WOApplication.application()).siteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.startRegistration();
				instance.setShouldDie( false );
			}
			else {
				((Application)WOApplication.application()).instanceController().registerUnknownInstance( instanceName, host, port );
			}
		}
		finally {
			theApplication._lock.readLock().unlock();
		}
	}

	private boolean registerLifebeat( String instanceName, String host, String port ) {

		// KH - can we cache this for better speed?
		final InetAddress hostAddress = addressForName( host );

		theApplication._lock.readLock().lock();

		try {
			final MInstance instance = ((Application)WOApplication.application()).siteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.updateRegistration();
				// This call will reset shouldDie status!;
				return !instance.shouldDieAndReset();
			}
			((Application)WOApplication.application()).instanceController().registerUnknownInstance( instanceName, host, port );
		}
		finally {
			theApplication._lock.readLock().unlock();
		}
		return true;
	}

	private void registerStop( String instanceName, String host, String port ) {

		// app will stop in a good way - we requested it.
		final InetAddress hostAddress = addressForName( host );

		theApplication._lock.readLock().lock();

		try {
			final MInstance instance = ((Application)WOApplication.application()).siteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.registerStop();
				instance.setShouldDie( false );
				instance.cancelForceQuitTask();
			}
		}
		finally {
			theApplication._lock.readLock().unlock();
		}
	}

	private void registerCrash( String instanceName, String host, String port ) {
		log.error( "App '" + instanceName + "' on " + host + ":" + port + " received 'willCrash' notification." );

		// app will stop in a bad way - notify if necessary
		final InetAddress hostAddress = addressForName( host );

		theApplication._lock.readLock().lock();

		try {
			final MInstance instance = ((Application)WOApplication.application()).siteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.registerCrash();
				instance.setShouldDie( false );
				instance.cancelForceQuitTask();
			}
		}
		finally {
			theApplication._lock.readLock().unlock();
		}
	}

	private InetAddress addressForName( String name ) {
		try {
			return InetAddress.getByName( name );
		}
		catch( UnknownHostException uhe ) {
			log.error( "Unknown host: {}", name );
		}

		return null;
	}
}