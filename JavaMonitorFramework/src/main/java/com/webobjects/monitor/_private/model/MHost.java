/*
© Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */
package com.webobjects.monitor._private.model;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.monitor._private.MUtil;

import x.ResponseWrapper;
import x.XUtil;

public class MHost extends MObject {

	// Old common code
	private NSMutableDictionary<String, Object> values;

	public NSMutableDictionary<String, Object> values() {
		return values;
	}

	public void updateValues( NSDictionary<String, Object> aDict ) {
		values = new NSMutableDictionary<>( aDict );
		dataChanged();
	}

	public NSMutableDictionary<String, Object> dictionaryForArchive() {
		return values;
	}

	private static final Logger logger = LoggerFactory.getLogger( MHost.class );

	/**
	 * FIXME: Should be configurable. Used to be the property "JavaMonitor.receiveTimeout" // Hugi 2024-11-04
	 */
	private static final int WOTASKD_RECEIVE_TIMEOUT = 10000;

	private NSMutableArray<MInstance> _instanceArray;

	private InetAddress _address = null;

	public String runningInstances = "?";
	public String operatingSystem = "?";
	public String processorType = "?";
	public boolean isAvailable = false;

	// From the UI
	public MHost( final MSiteConfig siteConfig, final String name, final String type ) {
		this( new NSDictionary<>( new Object[] { name, type }, new Object[] { "name", "type" } ), siteConfig );
	}

	// Unarchiving or Monitor Update
	public MHost( NSDictionary valuesDict, MSiteConfig siteConfig ) {
		values = new NSMutableDictionary( valuesDict );
		_siteConfig = siteConfig;
		_instanceArray = new NSMutableArray<>();

		int tries = 0;
		while( tries++ < 5 ) {
			try {
				_address = InetAddress.getByName( name() );
				break;
			}
			catch( UnknownHostException anException ) {
				// AK: From *my* POV, we should check if this is the localhost and exit if it is,
				// as I had this happen when you set -WOHost something and DNS isn't available.
				// As it stands now, wotaskd will launch, but not really register and app (or get weirdo exceptions)
				logger.error( "Error getting address for Host: {}", name() );
				try {
					Thread.sleep( 2000 );
				}
				catch( InterruptedException e ) {
					logger.error( "Interrupted" );
				}
			}
		}
	}

	public String name() {
		return (String)values.valueForKey( "name" );
	}

	public void setName( String value ) {
		values.takeValueForKey( value, "name" );
		dataChanged();
	}

	public String osType() {
		return (String)values.valueForKey( "type" );
	}

	public void setOsType( String value ) {
		values.takeValueForKey( MUtil.validatedHostType( value ), "type" );
		dataChanged();
	}

	public NSMutableArray<MInstance> instanceArray() {
		return _instanceArray;
	}

	public void _addInstancePrimitive( MInstance anInstance ) {
		_instanceArray.addObject( anInstance );
	}

	public void _removeInstancePrimitive( MInstance anInstance ) {
		_instanceArray.removeObject( anInstance );
	}

	public InetAddress address() {
		return _address;
	}

	public String addressAsString() {
		if( _address != null ) {
			return _address.getHostAddress();
		}

		return "Unknown";
	}

	public Integer runningInstancesCount_W() {
		int runningInstances = 0;

		for( MInstance instance : _instanceArray ) {
			if( instance.isRunning_W() ) {
				runningInstances++;
			}
		}

		return runningInstances;
	}

	public boolean isPortInUse( Integer port ) {
		return instanceWithPort( port ) != null;
	}

	public Integer nextAvailablePort( Integer startingPort ) {
		int port = startingPort;
		while( isPortInUse( port ) ) {
			port++;
		}
		return port;
	}

	public MInstance instanceWithPort( Integer port ) {

		for( MInstance anInst : _instanceArray ) {
			if( anInst.port().equals( port ) ) {
				return anInst;
			}
		}

		return null;
	}

	/**
	 * Machine Information and Availability Check (Used by MONITOR)
	 */
	public void _setHostInfo( Map map ) {
		Object value = null;

		value = map.get( "runningInstances" );
		if( value != null ) {
			runningInstances = value.toString();
		}

		value = map.get( "operatingSystem" );
		if( value != null ) {
			operatingSystem = value.toString();
		}

		value = map.get( "processorType" );
		if( value != null ) {
			processorType = value.toString();
		}
	}

	/**
	 * Sends the given request to this host's wotaskd, returning wotaskd's response
	 *
	 * FIXME: We need to go over this, especially WRT error handling, reporting and management // Hugi 2024-11-06
	 */
	public ResponseWrapper sendRequestToWotaskd( final String contentString, final String password, final boolean willChange, final boolean isSync ) {

		ResponseWrapper responseWrapper = new ResponseWrapper();

		final Builder requestBuilder = HttpRequest
				.newBuilder()
				.uri( URI.create( "http://%s:%s%s".formatted( name(), WOApplication.application().lifebeatDestinationPort(), MUtil.WOTASKD_DIRECT_ACTION_URL ) ) )
				.timeout( Duration.ofMillis( WOTASKD_RECEIVE_TIMEOUT ) )
				.POST( BodyPublishers.ofString( contentString ) );

		// FIXME: We're going to have to check the semantics of resetting the password (used to be handled when constructing the password header map in MSiteConfig.passwordDictionary()) // Hugi 2024-11-06
		if( password != null ) {
			requestBuilder.setHeader( "password", password );
		}

		final HttpRequest request = requestBuilder.build();

		logger.info( "--> Sending request: =======" );
		logger.info( "{}", request );
		logger.info( contentString );

		try {
			final HttpResponse<String> response = XUtil.sendRequest( request );
			logger.info( "--> Response received =======" );
			responseWrapper.setContentString( response.body() );
		}
		catch( IOException e ) {
			e.printStackTrace();
			isAvailable = false;
		}
		catch( InterruptedException e ) {
			Thread.currentThread().interrupt();
			isAvailable = false;
		}

		logger.info( "--> End request phase =======" );

		// For error handling
		if( responseWrapper.contentString() == null ) {

			if( willChange ) {
				_siteConfig.hostErrorArray.add( this );
			}

			final String errorResponseString = XUtil.errorResponseXML( "instanceResponse", "Failed to contact " + this.name() + "-" + WOApplication.application().lifebeatDestinationPort() );
			responseWrapper.setContentString( errorResponseString );
		}
		else {
			// if we successfully synced, clear the error dictionary
			if( isSync && isAvailable ) {
				_siteConfig.hostErrorArray.remove( this );
			}
		}

		return responseWrapper;
	}

	@Override
	public String toString() {
		return "MHost@" + _address;
	}

	@Override
	public boolean equals( Object other ) {
		return (other instanceof MHost) && (((MHost)other)._address.equals( _address ));
	}

	@Override
	public int hashCode() {
		return _address.hashCode();
	}
}