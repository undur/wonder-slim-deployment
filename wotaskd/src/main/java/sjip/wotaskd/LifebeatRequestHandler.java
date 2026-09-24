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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WORequestHandler;
import com.webobjects.appserver.WOResponse;

import sjip.core.model.MInstance;
import sjip.core.model.MSiteConfig;
import sjip.core.x.FHosts;

/**
 * Receives the lifebeats instances send to register themselves and report liveness.
 *
 * Beats arrive by two routes, sharing {@link #process}:
 *
 * - Well-formed HTTP (ng-objects' lifebeat thread, or anything else that sends a Host header) is dispatched
 *   to this request handler the ordinary way.
 * - WO instances' lifebeats arrive as HTTP/1.1 *without* a Host header, which Jetty 12 rejects in its parser
 *   (400 "No Host", unconditionally). Those connections are claimed before HTTP parsing by
 *   {@link WOLifebeatDetector} and served by {@link WOLifebeatConnection}, which answers them exactly as the
 *   classic adaptor does. Under the classic adaptor they simply land here.
 */
public class LifebeatRequestHandler extends WORequestHandler {

	private static final Logger log = LoggerFactory.getLogger( LifebeatRequestHandler.class );

	private static final WOResponse BAD_LIFEBEAT_RESPONSE = constantResponse( 400, "HTTP/1.0" ); // Bad Request
	private static final WOResponse GOOD_RESPONSE = constantResponse( 200, "HTTP/1.1" ); // OK
	private static final WOResponse DIE_RESPONSE = constantResponse( 500, "HTTP/1.0" ); // InternalServerError -> Die Immediately

	private final String _hostName;

	public LifebeatRequestHandler( final String hostName ) {
		_hostName = hostName;
	}

	private static WOResponse constantResponse( int status, String httpVersion ) {
		final WOResponse r = new WOResponse();
		r.setStatus( status );
		r.setHTTPVersion( httpVersion );
		return r;
	}

	/**
	 * What a lifebeat amounts to, independent of the route it arrived by.
	 */
	public enum Verdict {

		/** Registered - carry on */
		OK,

		/** Acknowledged, nothing to report back (willStop, willCrash) */
		ACKNOWLEDGED,

		/** Malformed beat - the sender should drop the connection and start over */
		BAD,

		/** The instance should die (force quit) */
		DIE,

		/** Not from a configured host - ignored, though still answered (as the classic adaptor did) with an empty acknowledgement */
		REJECTED
	}

	/** Addresses whose beats have been rejected and warned about - once each, since a rejected instance keeps beating */
	private final Set<InetAddress> _warnedAddresses = ConcurrentHashMap.newKeySet();

	@Override
	public WOResponse handleRequest( WORequest aRequest ) {

		// Didn't pull this out so that we can rely on isUsingWebServer to catch some bad requests
		if( FHosts.isUsingWebServer( aRequest.headers() ) ) {
			return null;
		}

		final WOResponse response = switch( process( aRequest.queryString(), aRequest._originatingAddress() ) ) {
			case OK -> GOOD_RESPONSE;
			case DIE -> DIE_RESPONSE;
			case BAD -> BAD_LIFEBEAT_RESPONSE;
			case ACKNOWLEDGED, REJECTED -> null;
		};

		// Returning null here used to bypass response generation entirely, back when wotaskd's Application overrode dispatchRequest()
		// to fast-path lifebeats (see commit 51c4677, issue #19). With the override gone, super.dispatchRequest upgrades null to
		// an empty WOResponse, so this branch no longer expresses a meaningful behavior. Pending verification — issue #32.
		if( "HTTP/1.0".equals( aRequest.httpVersion() ) ) {
			return null;
		}

		return response;
	}

	/**
	 * Processes one lifebeat, whichever route it arrived by.
	 *
	 * @param queryString {@code <notification name>&<instance name>&<hostname>&<port>}, notification name being one of
	 *                    "hasStarted", "lifebeat", "willStop", "willCrash"
	 * @param originatingAddress The address the beat came from; only configured hosts are heard
	 */
	public Verdict process( final String queryString, final InetAddress originatingAddress ) {

		// Sadly, we do regenerate in the case of random lifebeats. Hopefully this won't be too often.
		if( !FHosts.isConfiguredHostAddress( originatingAddress, true ) ) {
			if( originatingAddress != null && _warnedAddresses.add( originatingAddress ) ) {
				log.warn( "{}: Ignoring lifebeats from {} - not this host's configured address. With WOHost set, only that exact address is accepted", _hostName, originatingAddress );
			}

			return Verdict.REJECTED;
		}

		final Object lock = WOApplication.application().requestHandlingLock();

		if( lock != null ) {
			synchronized( lock ) {
				return _process( queryString );
			}
		}

		return _process( queryString );
	}

	private Verdict _process( final String queryString ) {

		Verdict verdict = Verdict.BAD;

		// http://localhost:1085/cgi-bin/WebObjects/wotaskd.woa/wlb?<notification name>&<instance name>&<hostname>&<port>
		// <notification name> = "hasStarted", "lifebeat", "willStop", "willCrash"

		final List<String> values = queryString == null ? null : List.of( queryString.split( "&", -1 ) );

		if( (values == null) || (values.size() != 4) ) {
			appSiteConfig().globalErrorDictionary.put( queryString, (_hostName + ": Received bad lifebeat: " + queryString) );
			log.error( "{}: Received bad lifebeat: {}", _hostName, queryString );
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
					verdict = Verdict.DIE;
				}
				else {
					verdict = Verdict.OK;
				}
			}
			else if( notificationType.equals( "hasStarted" ) ) {
				// app has just started - register instance
				registerStart( instanceName, host, port );
				verdict = Verdict.OK;
			}
			else if( notificationType.equals( "willStop" ) ) {
				// app will stop - mark as dead
				registerStop( instanceName, host, port );
				verdict = Verdict.ACKNOWLEDGED;
			}
			else if( notificationType.equals( "willCrash" ) ) {
				// app will crash - mark as dead, email notification
				registerCrash( instanceName, host, port );
				verdict = Verdict.ACKNOWLEDGED;
			}
			else {
				appSiteConfig().globalErrorDictionary.put( queryString, (_hostName + ": Received bad lifebeat: " + queryString) );
				log.error( "{}: Received bad lifebeat: {}", _hostName, queryString );
			}
		}

		return verdict;
	}

	private void registerStart( String instanceName, String host, String port ) {

		// KH - can we cache this for better speed?
		final InetAddress hostAddress = addressForName( host );

		appLock().readLock().lock();

		try {
			final MInstance instance = appSiteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.startRegistration();
				instance.setShouldDie( false );
				// hasStarted means a fresh JVM - possibly a new build, so the type cache is discarded
				ApplicationTypeProber.reprobe( instance );
			}
			else {
				appInstanceController().registerUnknownInstance( instanceName, host, port );
			}
		}
		finally {
			appLock().readLock().unlock();
		}
	}

	private boolean registerLifebeat( String instanceName, String host, String port ) {

		// KH - can we cache this for better speed?
		final InetAddress hostAddress = addressForName( host );

		appLock().readLock().lock();

		try {
			final MInstance instance = appSiteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.updateRegistration();
				ApplicationTypeProber.probeIfNeeded( instance );
				// This call will reset shouldDie status!;
				return !instance.shouldDieAndReset();
			}
			appInstanceController().registerUnknownInstance( instanceName, host, port );
		}
		finally {
			appLock().readLock().unlock();
		}
		return true;
	}

	private void registerStop( String instanceName, String host, String port ) {

		// app will stop in a good way - we requested it.
		final InetAddress hostAddress = addressForName( host );

		appLock().readLock().lock();

		try {
			final MInstance instance = appSiteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.registerStop();
				instance.setShouldDie( false );
				instance.cancelForceQuitTask();
			}
		}
		finally {
			appLock().readLock().unlock();
		}
	}

	private void registerCrash( String instanceName, String host, String port ) {
		log.error( "App '" + instanceName + "' on " + host + ":" + port + " received 'willCrash' notification." );

		// app will stop in a bad way - notify if necessary
		final InetAddress hostAddress = addressForName( host );

		appLock().readLock().lock();

		try {
			final MInstance instance = appSiteConfig().instanceWithHostAndPort( instanceName, hostAddress, port );

			if( instance != null ) {
				instance.registerCrash();
				instance.setShouldDie( false );
				instance.cancelForceQuitTask();
			}
		}
		finally {
			appLock().readLock().unlock();
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

	private static AppTaskd appTaskd() {
		return ((Application)WOApplication.application()).appTaskd();
	}
	
	private static MSiteConfig appSiteConfig() {
		return appTaskd().siteConfig();
	}

	private static ReentrantReadWriteLock appLock() {
		return appTaskd().lock();
	}
	
	private static InstanceController appInstanceController() {
		return appTaskd().instanceController();
	}
}