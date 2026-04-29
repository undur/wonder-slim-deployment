package com.webobjects.monitor.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.xml.WOXMLException;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSLog;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSPropertyListSerialization;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MHost;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor._private.model.MObject;
import com.webobjects.monitor._private.model.MSiteConfig;
import com.webobjects.monitor.application.components.AppDetailPage;
import com.webobjects.monitor.application.components.ApplicationsPage;
import com.webobjects.monitor.application.components.HostsPage;

import x.FoundationCoder;
import x.ResponseWrapper;

public class WOTaskdHandler {

	private static final Logger logger = LoggerFactory.getLogger( WOTaskdHandler.class );

	public interface ErrorCollector {
		public void addObjectsFromArrayIfAbsentToErrorMessageArray( List<String> errors );
	}

	private static ReentrantReadWriteLock _lock = new ReentrantReadWriteLock();

	private static MSiteConfig _siteConfig;

	private final ErrorCollector _errorCollector;

	public static MSiteConfig siteConfig() {
		return _siteConfig;
	}

	public static void createSiteConfig() {

		_siteConfig = MSiteConfig.unarchiveSiteConfig( false );

		if( _siteConfig == null ) {
			logger.error( "The Site Configuration could not be loaded from the local filesystem" );
			System.exit( 1 );
		}

		// FIXME: This is *probably* so that the hosts in question get marked a requiring synchronization before first use. In which case "hostErrorArray" isn't really a nice variable name // Hugi 2024-11-06
		for( MHost nextElement : _siteConfig.hostArray() ) {
			_siteConfig.hostErrorArray.add( nextElement );
		}

		// FIXME: OK, this whole localhost thing needs to be resolved... // Hugi 2024-11-06
		if( _siteConfig.localHost() != null ) {
			_siteConfig.hostErrorArray.remove( _siteConfig.localHost() );
		}
	}

	/**
	 * Creates a WOTaskdHandler that just logs errors (not sending them anywhere else for handling or display)
	 */
	public WOTaskdHandler() {
		this( errors -> {
			errors.forEach( error -> {
				logger.error( error );
			});
		} );
	}

	public WOTaskdHandler( ErrorCollector errorCollector ) {
		_errorCollector = errorCollector;
	}

	private ErrorCollector errorCollector() {
		return _errorCollector;
	}

	public void startReading() {
		_lock.readLock().lock();
	}

	public void endReading() {
		_lock.readLock().unlock();
	}

	/**
	 * Performs the given stuff while holding a read lock  
	 */
	public void whileReading( final Runnable stuffToDoWhileReading ) {
		startReading();
		
		try {
			stuffToDoWhileReading.run();
		}
		finally {
			endReading();
		}
	}

	/**
	 * Performs the given stuff while holding a write lock  
	 */
	public void whileWriting( final Runnable stuffToDoWhileWriting ) {
		_lock.writeLock().lock();
		
		try {
			stuffToDoWhileWriting.run();
		}
		finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * FIXME: OK, this is not nice. It's checking which page is invoking this method and then updating status accordingly // Hugi 2024-10-27
	 */
	@Deprecated
	public void updateForPage( Class<? extends WOComponent> pageClass ) {

		// KH - we should probably set the instance information as we get the responses, to avoid waiting, then doing it in serial! (not that it's _that_ slow)
		final MSiteConfig siteConfig = WOTaskdHandler.siteConfig();

		startReading();

		try {
			if( siteConfig.hostArray().size() != 0 ) {
				if( ApplicationsPage.class.equals( pageClass ) ) {
					if( siteConfig.applicationArray().size() != 0 ) {
						for( final MApplication anApp : siteConfig.applicationArray() ) {
							anApp.setRunningInstancesCount( 0 );
						}
						
						getApplicationStatusForHosts( siteConfig.hostArray() );
					}
				}
				else if( AppDetailPage.class.equals( pageClass ) ) {
					getInstanceStatusForHosts( siteConfig.hostArray() );
				}
				else if( HostsPage.class.equals( pageClass ) ) {
					getHostStatusForHosts( siteConfig.hostArray() );
				}
			}
		}
		finally {
			endReading();
		}
	}

	/* ******** Common Functionality ********* */
	private static NSMutableDictionary createUpdateRequestDictionary( MSiteConfig _Config, MHost _Host, MApplication _Application, List<MInstance> _InstanceArray, String requestType ) {

		final NSMutableDictionary monitorRequest = new NSMutableDictionary( 1 );
		final NSMutableDictionary updateWotaskd = new NSMutableDictionary( 1 );
		final NSMutableDictionary requestTypeDict = new NSMutableDictionary();

		if( _Config != null ) {
			final NSDictionary site = new NSDictionary( _Config.values() );
			requestTypeDict.takeValueForKey( site, "site" );
		}

		if( _Host != null ) {
			final List<MHost> hostArray = new NSArray( _Host.values() );
			requestTypeDict.takeValueForKey( hostArray, "hostArray" );
		}

		if( _Application != null ) {
			final List<MApplication> applicationArray = new NSArray( _Application.values() );
			requestTypeDict.takeValueForKey( applicationArray, "applicationArray" );
		}

		if( _InstanceArray != null ) {
			final int instanceCount = _InstanceArray.size();
			final NSMutableArray instanceArray = new NSMutableArray( instanceCount );

			for( int i = 0; i < instanceCount; i++ ) {
				MInstance anInst = _InstanceArray.get( i );
				instanceArray.addObject( anInst.values() );
			}

			requestTypeDict.takeValueForKey( instanceArray, "instanceArray" );
		}

		updateWotaskd.takeValueForKey( requestTypeDict, requestType );
		monitorRequest.takeValueForKey( updateWotaskd, "updateWotaskd" );

		return monitorRequest;
	}

	private ResponseWrapper[] sendRequest( NSDictionary monitorRequest, List<MHost> wotaskdArray, boolean willChange ) {
		final String encodedString = new FoundationCoder().encodeRootObjectForKey( monitorRequest, "monitorRequest" );
		return WOTaskdComms.sendRequestToWotaskdArray( encodedString, wotaskdArray, willChange );
	}

	/* ******** ADDING (UPDATE) ********* */
	public void sendAddInstancesToWotaskds( List<MInstance> newInstancesArray, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, null, newInstancesArray, "add" ), wotaskdArray, true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "add", false, false, true, false );
	}

	public void sendAddApplicationToWotaskds( MApplication newApplication, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, newApplication, null, "add" ), wotaskdArray, true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "add", false, true, false, false );
	}

	public void sendAddHostToWotaskds( MHost newHost, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, newHost, null, null, "add" ), wotaskdArray, true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "add", true, false, false, false );
	}

	/* ******** REMOVING (UPDATE) ********* */
	public void sendRemoveInstancesToWotaskds( List<MInstance> exInstanceArray, List<MHost> wotaskdArray ) {
		ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, null, exInstanceArray, "remove" ), wotaskdArray, true );
		NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "remove", false, false, true, false );
	}

	public void sendRemoveApplicationToWotaskds( MApplication exApplication, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, exApplication, null, "remove" ), wotaskdArray, true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "remove", false, true, false, false );
	}

	public void sendRemoveHostToWotaskds( MHost exHost, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, exHost, null, null, "remove" ), wotaskdArray, true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "remove", true, false, false, false );
	}

	/* ******** CONFIGURE (UPDATE) ********* */
	public void sendUpdateInstancesToWotaskds( List<MInstance> changedInstanceArray, List<MHost> wotaskdArray ) {
		if( wotaskdArray.size() != 0 && changedInstanceArray.size() != 0 ) {
			final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, null, changedInstanceArray, "configure" ), wotaskdArray, true );
			final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
			getUpdateErrors( responseDicts, "configure", false, false, true, false );
		}
	}

	public void sendUpdateApplicationToWotaskds( MApplication changedApplication, List<MHost> wotaskdArray ) {
		if( wotaskdArray.size() != 0 ) {
			final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, changedApplication, null, "configure" ), wotaskdArray, true );
			final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
			getUpdateErrors( responseDicts, "configure", false, true, false, false );
		}
	}

	public void sendUpdateApplicationAndInstancesToWotaskds( MApplication changedApplication, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, changedApplication, changedApplication.instanceArray(), "configure" ), wotaskdArray, true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "configure", false, true, true, false );
	}

	public void sendUpdateHostToWotaskds( MHost changedHost, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, changedHost, null, null, "configure" ), wotaskdArray, true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "configure", true, false, false, false );
	}

	public void sendUpdateSiteToWotaskds() {
		startReading();
		try {
			final NSMutableArray hostArray = siteConfig().hostArray();

			if( hostArray.size() != 0 ) {
				final NSMutableDictionary updateRequestDictionary = createUpdateRequestDictionary( siteConfig(), null, null, null, "configure" );
				final ResponseWrapper[] responses = sendRequest( updateRequestDictionary, hostArray, true );
				final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
				getUpdateErrors( responseDicts, "configure", false, false, false, true );
			}
		}
		finally {
			endReading();
		}
	}

	/* ******** OVERWRITE / CLEAR (UPDATE) ********* */
	public void sendOverwriteToWotaskd( MHost aHost ) {
		final NSDictionary SiteConfig = siteConfig().dictionaryForArchive();
		final NSMutableDictionary data = new NSMutableDictionary( SiteConfig, "SiteConfig" );
		_sendOverwriteClearToWotaskd( aHost, "overwrite", data );
	}

	private void sendClearToWotaskd( MHost aHost ) {
		final String data = new String( "SITE" );
		_sendOverwriteClearToWotaskd( aHost, "clear", data );
	}

	private void _sendOverwriteClearToWotaskd( MHost aHost, String type, Object data ) {
		final NSMutableDictionary updateWotaskd = new NSMutableDictionary( data, type );
		final NSMutableDictionary monitorRequest = new NSMutableDictionary( updateWotaskd, "updateWotaskd" );

		final ResponseWrapper[] responses = sendRequest( monitorRequest, List.of( aHost ), true );
		final NSDictionary[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, type, false, false, false, false );
	}

	/* ******** COMMANDING ********* */
	private static Object[] commandInstanceKeys = new Object[] { "applicationName", "id", "hostName", "port" };

	private static void sendCommandInstancesToWotaskds( String command, List<MInstance> instanceArray, List<MHost> wotaskdArray, WOTaskdHandler collector ) {

		if( instanceArray.size() > 0 && wotaskdArray.size() > 0 ) {
			final int instanceCount = instanceArray.size();

			final NSMutableDictionary monitorRequest = new NSMutableDictionary( 1 );
			final NSMutableArray commandWotaskd = new NSMutableArray( instanceArray.size() + 1 );

			commandWotaskd.addObject( command );

			for( int i = 0; i < instanceCount; i++ ) {
				MInstance anInst = instanceArray.get( i );
				commandWotaskd.addObject( new NSDictionary( new Object[] { anInst.applicationName(), anInst.id(), anInst.hostName(), anInst.port() }, commandInstanceKeys ) );
			}

			monitorRequest.takeValueForKey( commandWotaskd, "commandWotaskd" );

			final ResponseWrapper[] responses = collector.sendRequest( monitorRequest, wotaskdArray, false );
			final NSDictionary[] responseDicts = generateResponseDictionaries( responses );

			if( NSLog.debugLoggingAllowedForLevelAndGroups( NSLog.DebugLevelDetailed, NSLog.DebugGroupDeployment ) ) {
				NSLog.debug.appendln( "OUT: " + NSPropertyListSerialization.stringFromPropertyList( monitorRequest ) + "\n\nIN: " + NSPropertyListSerialization.stringFromPropertyList( new NSArray( responseDicts ) ) );
			}

			collector.getCommandErrors( responseDicts );
		}
	}

	public void sendCommandInstancesToWotaskds( String command, List<MInstance> instanceArray, List<MHost> wotaskdArray ) {
		sendCommandInstancesToWotaskds( command, instanceArray, wotaskdArray, this );
	}

	public void sendQuitInstancesToWotaskds( List<MInstance> instanceArray, List<MHost> wotaskdArray ) {
		sendCommandInstancesToWotaskds( "QUIT", instanceArray, wotaskdArray, this );
	}

	public void sendStartInstancesToWotaskds( List<MInstance> instanceArray, List<MHost> wotaskdArray ) {
		sendCommandInstancesToWotaskds( "START", instanceArray, wotaskdArray, this );
	}

	public void sendClearDeathsToWotaskds( List<MInstance> instanceArray, List<MHost> wotaskdArray ) {
		sendCommandInstancesToWotaskds( "CLEAR", instanceArray, wotaskdArray, this );
	}

	public void sendStopInstancesToWotaskds( List<MInstance> instanceArray, List<MHost> wotaskdArray ) {
		sendCommandInstancesToWotaskds( "STOP", instanceArray, wotaskdArray, this );
	}

	public void sendRefuseSessionToWotaskds( List<MInstance> instanceArray, List<MHost> wotaskdArray, boolean doRefuse ) {

		for( MInstance instance : instanceArray ) {
			instance.setRefusingNewSessions( doRefuse );
		}

		sendCommandInstancesToWotaskds( (doRefuse ? "REFUSE" : "ACCEPT"), instanceArray, wotaskdArray );
	}

	/* ******** QUERIES ********* */
	private NSMutableDictionary createQuery( String queryString ) {
		final NSMutableDictionary monitorRequest = new NSMutableDictionary( queryString, "queryWotaskd" );
		return monitorRequest;
	}

	private ResponseWrapper[] sendQueryToWotaskds( String queryString, List<MHost> wotaskdArray ) {
		return sendRequest( createQuery( queryString ), wotaskdArray, false );
	}

	/* ******** Response Handling ********* */
	private static NSDictionary responseParsingFailed = new NSDictionary( new NSDictionary( new NSArray( "INTERNAL ERROR: Failed to parse response XML" ), "errorResponse" ), "monitorResponse" );

	private static NSDictionary emptyResponse = new NSDictionary( new NSDictionary( new NSArray( "INTERNAL ERROR: Response returned was null or empty" ), "errorResponse" ), "monitorResponse" );

	private static NSDictionary[] generateResponseDictionaries( ResponseWrapper[] responses ) {

		final NSDictionary[] responseDicts = new NSDictionary[responses.length];

		for( int i = 0; i < responses.length; i++ ) {
			final ResponseWrapper currentResponse = responses[i];

			if( currentResponse != null && currentResponse.content() != null ) {
				try {
					responseDicts[i] = (NSDictionary)new FoundationCoder().decodeRootObject( currentResponse.content() );
				}
				catch( WOXMLException wxe ) {
					responseDicts[i] = responseParsingFailed;
				}
			}
			else {
				responseDicts[i] = emptyResponse;
			}
		}

		return responseDicts;
	}

	/* ******** Error Handling ********* */
	private void getUpdateErrors( NSDictionary[] responseDicts, String updateType, boolean hasHosts, boolean hasApplications, boolean hasInstances, boolean hasSite ) {

		final List<String> errorArray = new ArrayList();

		boolean clearOverwrite = false;

		if( (updateType.equals( "overwrite" )) || (updateType.equals( "clear" )) ) {
			clearOverwrite = true;
		}

		for( int i = 0; i < responseDicts.length; i++ ) {
			if( responseDicts[i] != null ) {
				final NSDictionary responseDict = responseDicts[i];
				getGlobalErrorFromResponse( responseDict, errorArray );

				final NSDictionary updateWotaskdResponseDict = (NSDictionary)responseDict.valueForKey( "updateWotaskdResponse" );

				if( updateWotaskdResponseDict != null ) {
					final NSDictionary updateTypeResponse = (NSDictionary)updateWotaskdResponseDict.valueForKey( updateType );

					if( updateTypeResponse != null ) {
						if( clearOverwrite ) {
							final String errorMessage = (String)updateTypeResponse.valueForKey( "errorMessage" );

							if( errorMessage != null ) {
								errorArray.add( errorMessage );
							}
						}
						else {
							if( hasSite ) {
								final NSDictionary aDict = (NSDictionary)updateTypeResponse.valueForKey( "site" );
								final String errorMessage = (String)aDict.valueForKey( "errorMessage" );

								if( errorMessage != null ) {
									errorArray.add( errorMessage );
								}
							}
							if( hasHosts ) {
								_addUpdateResponseToErrorArray( updateTypeResponse, "hostArray", errorArray );
							}
							if( hasApplications ) {
								_addUpdateResponseToErrorArray( updateTypeResponse, "applicationArray", errorArray );
							}
							if( hasInstances ) {
								_addUpdateResponseToErrorArray( updateTypeResponse, "instanceArray", errorArray );
							}
						}
					}
				}
			}
		}

		if( NSLog.debugLoggingAllowedForLevelAndGroups( NSLog.DebugLevelDetailed, NSLog.DebugGroupDeployment ) ) {
			NSLog.debug.appendln( "##### getUpdateErrors: " + errorArray );
		}

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
	}

	private void _addUpdateResponseToErrorArray( NSDictionary updateTypeResponse, String responseKey, List<String> errorArray ) {

		final List<NSDictionary> aResponse = (List<NSDictionary>)updateTypeResponse.valueForKey( responseKey );

		if( aResponse != null ) {
			for( NSDictionary aDict : aResponse ) {
				final String errorMessage = (String)aDict.valueForKey( "errorMessage" );

				if( errorMessage != null ) {
					errorArray.add( errorMessage );
				}
			}
		}
	}

	private NSMutableArray getCommandErrors( NSDictionary[] responseDicts ) {
		final NSMutableArray errorArray = new NSMutableArray();

		for( int i = 0; i < responseDicts.length; i++ ) {
			if( responseDicts[i] != null ) {
				final NSDictionary responseDict = responseDicts[i];
				getGlobalErrorFromResponse( responseDict, errorArray );

				final List<Map<String,String>> commandWotaskdResponse = (List<Map<String, String>>)responseDict.valueForKey( "commandWotaskdResponse" );

				if( (commandWotaskdResponse != null) && (commandWotaskdResponse.size() > 0) ) {
					int count = commandWotaskdResponse.size();

					for( int j = 1; j < count; j++ ) {
						final Map<String,String> aDict = commandWotaskdResponse.get( j );
						final String errorMessage = aDict.get( "errorMessage" );

						if( errorMessage != null ) {
							errorArray.addObject( errorMessage );
							if( j == 0 ) {
								break; // the command produced an error,
								// parsing didn't finish
							}
						}
					}
				}
			}
		}

		if( NSLog.debugLoggingAllowedForLevelAndGroups( NSLog.DebugLevelDetailed, NSLog.DebugGroupDeployment ) ) {
			NSLog.debug.appendln( "##### getCommandErrors: " + errorArray );
		}

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
		return errorArray;
	}

//	FIXME: Doesn't look like this is used at all // Hugi 2024-11-06
//	private NSMutableArray getQueryErrors( NSDictionary[] responseDicts ) {
//
//		final NSMutableArray errorArray = new NSMutableArray();
//
//		for( int i = 0; i < responseDicts.length; i++ ) {
//			if( responseDicts[i] != null ) {
//				final NSDictionary responseDict = responseDicts[i];
//				getGlobalErrorFromResponse( responseDict, errorArray );
//
//				final NSArray commandWotaskdResponse = (NSArray)responseDict.valueForKey( "commandWotaskdResponse" );
//
//				if( (commandWotaskdResponse != null) && (commandWotaskdResponse.size() > 0) ) {
//					int count = commandWotaskdResponse.size();
//
//					for( int j = 1; j < count; j++ ) {
//						final NSDictionary aDict = (NSDictionary)commandWotaskdResponse.get( j );
//						final String errorMessage = (String)aDict.valueForKey( "errorMessage" );
//
//						if( errorMessage != null ) {
//							errorArray.addObject( errorMessage );
//							if( j == 0 ) {
//								break; // the command produced an error,
//								// parsing didn't finish
//							}
//						}
//					}
//				}
//			}
//		}
//
//		if( NSLog.debugLoggingAllowedForLevelAndGroups( NSLog.DebugLevelDetailed, NSLog.DebugGroupDeployment ) ) {
//			NSLog.debug.appendln( "##### getQueryErrors: " + errorArray );
//		}
//
//		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
//		return errorArray;
//	}

	private void getGlobalErrorFromResponse( NSDictionary responseDict, List<String> errorArray ) {
		final NSArray errorResponse = (NSArray)responseDict.valueForKey( "errorResponse" );

		if( errorResponse != null ) {
			errorArray.addAll( errorResponse );
		}
	}

	public void getInstanceStatusForHosts( List<MHost> hostArray ) {
		if( hostArray.size() != 0 ) {

			final ResponseWrapper[] responses = sendQueryToWotaskds( "INSTANCE", hostArray );

			final NSMutableArray errorArray = new NSMutableArray();
			NSArray responseArray = null;
			NSDictionary responseDictionary = null;
			NSDictionary queryResponseDictionary = null;

			for( int i = 0; i < responses.length; i++ ) {
				if( (responses[i] == null) || (responses[i].content() == null) ) {
					responseDictionary = emptyResponse;
				}
				else {
					try {
						responseDictionary = (NSDictionary)new FoundationCoder().decodeRootObject( responses[i].content() );
					}
					catch( WOXMLException wxe ) {
						NSLog.err.appendln( "MonitorComponent pageWithName(AppDetailPage) Error decoding response: " + responses[i].contentString() );
						responseDictionary = responseParsingFailed;
					}
				}
				getGlobalErrorFromResponse( responseDictionary, errorArray );

				queryResponseDictionary = (NSDictionary)responseDictionary.valueForKey( "queryWotaskdResponse" );

				if( queryResponseDictionary != null ) {
					responseArray = (NSArray)queryResponseDictionary.valueForKey( "instanceResponse" );

					if( responseArray != null ) {
						for( int j = 0; j < responseArray.size(); j++ ) {
							responseDictionary = (NSDictionary)responseArray.get( j );

							final String host = (String)responseDictionary.valueForKey( "host" );
							final Integer port = (Integer)responseDictionary.valueForKey( "port" );
							final String runningState = (String)responseDictionary.valueForKey( "runningState" );
							final Boolean refusingNewSessions = (Boolean)responseDictionary.valueForKey( "refusingNewSessions" );
							final NSDictionary statistics = (NSDictionary)responseDictionary.valueForKey( "statistics" );
							final NSArray deaths = (NSArray)responseDictionary.valueForKey( "deaths" );
							final String nextShutdown = (String)responseDictionary.valueForKey( "nextShutdown" );

							final MInstance anInstance = siteConfig().instanceWithHostnameAndPort( host, port );

							if( anInstance != null ) {
								for( int k = 0; k < MObject.INSTANCE_STATES.length; k++ ) {
									if( MObject.INSTANCE_STATES[k].equals( runningState ) ) {
										anInstance.state = k;
										break;
									}
								}
								
								// FIXME: Null check added as a precaution, we can probably throw this check away.
								// That Boolean used to be converted to a boolean in a null-safe way, which I believe is redundant.
								// Hugi 2024-10-30
								if( refusingNewSessions == null ) {
									throw new IllegalStateException( "RefusingNewSessions is null" );
								}

								anInstance.setRefusingNewSessions( refusingNewSessions );
								anInstance.setStatistics( statistics );
								anInstance.setDeaths( new NSMutableArray( deaths ) );
								anInstance.setNextScheduledShutdownString_M( nextShutdown );
							}
						}
					}
				}
			}

			if( NSLog.debugLoggingAllowedForLevelAndGroups( NSLog.DebugLevelDetailed, NSLog.DebugGroupDeployment ) ) {
				NSLog.debug.appendln( "##### pageWithName(AppDetailPage) errors: " + errorArray );
			}

			errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
		}
	}

	private void getHostStatusForHosts( List<MHost> hostArray ) {
		final ResponseWrapper[] responses = sendQueryToWotaskds( "HOST", hostArray );

		final NSMutableArray errorArray = new NSMutableArray();
		NSDictionary responseDict = null;

		for( int i = 0; i < responses.length; i++ ) {
			final MHost aHost = siteConfig().hostArray().get( i );

			if( (responses[i] == null) || (responses[i].content() == null) ) {
				responseDict = emptyResponse;
			}
			else {
				try {
					responseDict = (NSDictionary)new FoundationCoder().decodeRootObject( responses[i].content() );
				}
				catch( WOXMLException wxe ) {
					NSLog.err.appendln( "MonitorComponent pageWithName(HostsPage) Error decoding response: " + responses[i].contentString() );
					responseDict = responseParsingFailed;
				}
			}

			getGlobalErrorFromResponse( responseDict, errorArray );

			final NSDictionary queryResponse = (NSDictionary)responseDict.valueForKey( "queryWotaskdResponse" );

			if( queryResponse != null ) {
				final NSDictionary hostResponse = (NSDictionary)queryResponse.valueForKey( "hostResponse" );
				aHost._setHostInfo( hostResponse );
				aHost.isAvailable = true;
			}
			else {
				aHost.isAvailable = false;
			}
		}

		if( NSLog.debugLoggingAllowedForLevelAndGroups( NSLog.DebugLevelDetailed, NSLog.DebugGroupDeployment ) ) {
			NSLog.debug.appendln( "##### pageWithName(HostsPage) errors: " + errorArray );
		}

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
	}

	private void getApplicationStatusForHosts( List<MHost> hostArray ) {

		final ResponseWrapper[] responses = sendQueryToWotaskds( "APPLICATION", hostArray );

		final NSMutableArray errorArray = new NSMutableArray();
		NSDictionary applicationResponseDictionary;
		NSDictionary queryResponseDictionary;
		NSArray responseArray = null;
		NSDictionary responseDictionary = null;

		for( int i = 0; i < responses.length; i++ ) {
			if( (responses[i] == null) || (responses[i].content() == null) ) {
				queryResponseDictionary = emptyResponse;
			}
			else {
				try {
					queryResponseDictionary = (NSDictionary)new FoundationCoder().decodeRootObject( responses[i].content() );
				}
				catch( WOXMLException wxe ) {
					NSLog.err.appendln( "MonitorComponent pageWithName(ApplicationsPage) Error decoding response: " + responses[i].contentString() );
					queryResponseDictionary = responseParsingFailed;
				}
			}

			getGlobalErrorFromResponse( queryResponseDictionary, errorArray );

			applicationResponseDictionary = (NSDictionary)queryResponseDictionary.valueForKey( "queryWotaskdResponse" );

			if( applicationResponseDictionary != null ) {
				responseArray = (NSArray)applicationResponseDictionary.valueForKey( "applicationResponse" );

				if( responseArray != null ) {
					for( int j = 0; j < responseArray.size(); j++ ) {
						responseDictionary = (NSDictionary)responseArray.get( j );
						String appName = (String)responseDictionary.valueForKey( "name" );
						Integer runningInstances = (Integer)responseDictionary.valueForKey( "runningInstances" );
						MApplication anApplication = siteConfig().applicationWithName( appName );
						if( anApplication != null ) {
							anApplication.setRunningInstancesCount( anApplication.runningInstancesCount() + runningInstances.intValue() );
						}
					}
				}
			}
		}

		if( NSLog.debugLoggingAllowedForLevelAndGroups( NSLog.DebugLevelDetailed, NSLog.DebugGroupDeployment ) ) {
			NSLog.debug.appendln( "##### pageWithName(ApplicationsPage) errors: " + errorArray );
		}

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
	}
}