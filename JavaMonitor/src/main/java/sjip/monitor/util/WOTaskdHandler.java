package sjip.monitor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOComponent;

import sjip.core.MUtil;
import sjip.core.model.MApplication;
import sjip.core.model.MApplicationDto;
import sjip.core.model.MHost;
import sjip.core.model.MHostDto;
import sjip.core.model.MInstance;
import sjip.core.model.MInstanceDto;
import sjip.core.model.MSiteConfig;
import sjip.core.model.MSiteConfigDto;
import sjip.core.model.MSiteConfigSiteDto;
import sjip.monitor.components.AppDetailPage;
import sjip.monitor.components.ApplicationsPage;
import sjip.monitor.components.HostsPage;
import sjip.x.FoundationCoder;
import sjip.x.FoundationPropertyListSerialization;
import sjip.x.ResponseWrapper;
import sjip.x.XUtil;

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
	private static Map<String, Object> createUpdateRequestDictionary( MSiteConfig _Config, MHost _Host, MApplication _Application, List<MInstance> _InstanceArray, String requestType ) {

		final Map<String, Object> monitorRequest = new LinkedHashMap<>();
		final Map<String, Object> updateWotaskd = new LinkedHashMap<>();
		final Map<String, Object> requestTypeDict = new LinkedHashMap<>();

		if( _Config != null ) {
			final MSiteConfigSiteDto site = _Config.toSiteDto();
			requestTypeDict.put( "site", site );
		}

		if( _Host != null ) {
			final List<MHostDto> hostArray = List.of( _Host.toDto() );
			requestTypeDict.put( "hostArray", hostArray );
		}

		if( _Application != null ) {
			final List<MApplicationDto> applicationArray = List.of( _Application.toDto() );
			requestTypeDict.put( "applicationArray", applicationArray );
		}

		if( _InstanceArray != null ) {
			final List<MInstanceDto> instanceArray = new ArrayList<>( _InstanceArray.size() );

			for( MInstance anInst : _InstanceArray ) {
				instanceArray.add( anInst.toDto() );
			}

			requestTypeDict.put( "instanceArray", instanceArray );
		}

		updateWotaskd.put( requestType, requestTypeDict );
		monitorRequest.put( "updateWotaskd", updateWotaskd );

		return monitorRequest;
	}

	private ResponseWrapper[] sendRequest( Map<String, Object> monitorRequest, List<MHost> wotaskdArray, boolean willChange ) {
		final String encodedString = new FoundationCoder().encodeRootObjectForKey( monitorRequest, "monitorRequest" );
		// FIXME: Sourcing the port from WOApplication.application() here is the call site
		// pulling it out of MHost so MHost's wire path can be exercised without a WOApplication.
		// Needs a nice central declaration of the wotaskd port. // Hugi 2026-05-12
		final int port = WOApplication.application().lifebeatDestinationPort();
		return WOTaskdComms.sendRequestToWotaskdArray( encodedString, wotaskdArray, port, willChange );
	}

	/* ******** ADDING (UPDATE) ********* */
	public void sendAddInstancesToWotaskds( List<MInstance> newInstancesArray, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, null, newInstancesArray, "add" ), wotaskdArray, true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "add", false, false, true, false );
	}

	public void sendAddApplicationToWotaskds( MApplication newApplication, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, newApplication, null, "add" ), wotaskdArray, true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "add", false, true, false, false );
	}

	public void sendAddHostToWotaskds( MHost newHost, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, newHost, null, null, "add" ), wotaskdArray, true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "add", true, false, false, false );
	}

	/* ******** REMOVING (UPDATE) ********* */
	public void sendRemoveInstancesToWotaskds( List<MInstance> exInstanceArray, List<MHost> wotaskdArray ) {
		ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, null, exInstanceArray, "remove" ), wotaskdArray, true );
		Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "remove", false, false, true, false );
	}

	public void sendRemoveApplicationToWotaskds( MApplication exApplication, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, exApplication, null, "remove" ), wotaskdArray, true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "remove", false, true, false, false );
	}

	public void sendRemoveHostToWotaskds( MHost exHost, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, exHost, null, null, "remove" ), wotaskdArray, true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "remove", true, false, false, false );
	}

	/* ******** CONFIGURE (UPDATE) ********* */
	public void sendUpdateInstancesToWotaskds( List<MInstance> changedInstanceArray, List<MHost> wotaskdArray ) {
		if( wotaskdArray.size() != 0 && changedInstanceArray.size() != 0 ) {
			final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, null, changedInstanceArray, "configure" ), wotaskdArray, true );
			final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
			getUpdateErrors( responseDicts, "configure", false, false, true, false );
		}
	}

	public void sendUpdateApplicationToWotaskds( MApplication changedApplication, List<MHost> wotaskdArray ) {
		if( wotaskdArray.size() != 0 ) {
			final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, changedApplication, null, "configure" ), wotaskdArray, true );
			final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
			getUpdateErrors( responseDicts, "configure", false, true, false, false );
		}
	}

	public void sendUpdateApplicationAndInstancesToWotaskds( MApplication changedApplication, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, null, changedApplication, changedApplication.instanceArray(), "configure" ), wotaskdArray, true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "configure", false, true, true, false );
	}

	public void sendUpdateHostToWotaskds( MHost changedHost, List<MHost> wotaskdArray ) {
		final ResponseWrapper[] responses = sendRequest( createUpdateRequestDictionary( null, changedHost, null, null, "configure" ), wotaskdArray, true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, "configure", true, false, false, false );
	}

	public void sendUpdateSiteToWotaskds() {
		startReading();
		try {
			final List<MHost> hostArray = siteConfig().hostArray();

			if( hostArray.size() != 0 ) {
				final Map<String, Object> updateRequestDictionary = createUpdateRequestDictionary( siteConfig(), null, null, null, "configure" );
				final ResponseWrapper[] responses = sendRequest( updateRequestDictionary, hostArray, true );
				final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
				getUpdateErrors( responseDicts, "configure", false, false, false, true );
			}
		}
		finally {
			endReading();
		}
	}

	/* ******** OVERWRITE / CLEAR (UPDATE) ********* */
	public void sendOverwriteToWotaskd( MHost aHost ) {
		final MSiteConfigDto SiteConfig = siteConfig().toDto();
		final Map<String, Object> data = new LinkedHashMap<>();
		data.put( "SiteConfig", SiteConfig );
		_sendOverwriteClearToWotaskd( aHost, "overwrite", data );
	}

	// FIXME: Dead code — no caller in JavaMonitor or anywhere else in our repos. The wire-level
	// "clear" command still exists on the wotaskd receive side (DirectAction.monitorRequestAction)
	// but nothing here invokes it. Likely vestigial from a removed UI affordance. Candidate for
	// deletion alongside the receive-side handler. // Hugi 2026-05-11
	private void sendClearToWotaskd( MHost aHost ) {
		final String data = "SITE";
		_sendOverwriteClearToWotaskd( aHost, "clear", data );
	}

	private void _sendOverwriteClearToWotaskd( MHost aHost, String type, Object data ) {
		final Map<String, Object> updateWotaskd = new LinkedHashMap<>();
		updateWotaskd.put( type, data );
		final Map<String, Object> monitorRequest = new LinkedHashMap<>();
		monitorRequest.put( "updateWotaskd", updateWotaskd );

		final ResponseWrapper[] responses = sendRequest( monitorRequest, List.of( aHost ), true );
		final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );
		getUpdateErrors( responseDicts, type, false, false, false, false );
	}

	/* ******** COMMANDING ********* */
	private static void sendCommandInstancesToWotaskds( String command, List<MInstance> instanceArray, List<MHost> wotaskdArray, WOTaskdHandler collector ) {

		if( instanceArray.size() > 0 && wotaskdArray.size() > 0 ) {
			final Map<String, Object> monitorRequest = new LinkedHashMap<>();
			final List<Object> commandWotaskd = new ArrayList<>( instanceArray.size() + 1 );

			commandWotaskd.add( command );

			for( MInstance anInst : instanceArray ) {
				final Map<String, Object> instanceDict = new LinkedHashMap<>();
				instanceDict.put( "applicationName", anInst.applicationName() );
				instanceDict.put( "id", anInst.id() );
				instanceDict.put( "hostName", anInst.hostName() );
				instanceDict.put( "port", anInst.port() );
				commandWotaskd.add( instanceDict );
			}

			monitorRequest.put( "commandWotaskd", commandWotaskd );

			final ResponseWrapper[] responses = collector.sendRequest( monitorRequest, wotaskdArray, false );
			final Map<String, Object>[] responseDicts = generateResponseDictionaries( responses );

			logger.debug( "OUT: " + FoundationPropertyListSerialization.stringFromPropertyList( monitorRequest ) + "\n\nIN: " + FoundationPropertyListSerialization.stringFromPropertyList( List.of( responseDicts ) ) );

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
	private Map<String, Object> createQuery( String queryString ) {
		final Map<String, Object> monitorRequest = new LinkedHashMap<>();
		monitorRequest.put( "queryWotaskd", queryString );
		return monitorRequest;
	}

	private ResponseWrapper[] sendQueryToWotaskds( String queryString, List<MHost> wotaskdArray ) {
		return sendRequest( createQuery( queryString ), wotaskdArray, false );
	}

	/* ******** Response Handling ********* */
	private static Map<String, Object> responseParsingFailed = XUtil.errorResponseDict( "monitorResponse", "INTERNAL ERROR: Failed to parse response XML" );

	private static Map<String, Object> emptyResponse = XUtil.errorResponseDict( "monitorResponse", "INTERNAL ERROR: Response returned was null or empty" );

	@SuppressWarnings("unchecked")
	private static Map<String, Object>[] generateResponseDictionaries( ResponseWrapper[] responses ) {

		final Map<String, Object>[] responseDicts = new Map[responses.length];

		for( int i = 0; i < responses.length; i++ ) {
			final ResponseWrapper currentResponse = responses[i];

			if( currentResponse != null && currentResponse.contentString() != null ) {
				try {
					responseDicts[i] = (Map<String, Object>)new FoundationCoder().decodeRootObjectFromString( currentResponse.contentString() );
				}
				catch( Exception e ) {
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
	private void getUpdateErrors( Map<String, Object>[] responseDicts, String updateType, boolean hasHosts, boolean hasApplications, boolean hasInstances, boolean hasSite ) {

		final List<String> errorArray = new ArrayList<>();

		boolean clearOverwrite = false;

		if( (updateType.equals( "overwrite" )) || (updateType.equals( "clear" )) ) {
			clearOverwrite = true;
		}

		for( int i = 0; i < responseDicts.length; i++ ) {
			if( responseDicts[i] != null ) {
				final Map<String, Object> responseDict = responseDicts[i];
				getGlobalErrorFromResponse( responseDict, errorArray );

				@SuppressWarnings("unchecked")
				final Map<String, Object> updateWotaskdResponseDict = (Map<String, Object>)responseDict.get( "updateWotaskdResponse" );

				if( updateWotaskdResponseDict != null ) {
					@SuppressWarnings("unchecked")
					final Map<String, Object> updateTypeResponse = (Map<String, Object>)updateWotaskdResponseDict.get( updateType );

					if( updateTypeResponse != null ) {
						if( clearOverwrite ) {
							final String errorMessage = (String)updateTypeResponse.get( "errorMessage" );

							if( errorMessage != null ) {
								errorArray.add( errorMessage );
							}
						}
						else {
							if( hasSite ) {
								@SuppressWarnings("unchecked")
								final Map<String, Object> aDict = (Map<String, Object>)updateTypeResponse.get( "site" );
								final String errorMessage = (String)aDict.get( "errorMessage" );

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

		logger.debug( "##### getUpdateErrors: " + errorArray );

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
	}

	private void _addUpdateResponseToErrorArray( Map<String, Object> updateTypeResponse, String responseKey, List<String> errorArray ) {

		@SuppressWarnings("unchecked")
		final List<Map<String, Object>> aResponse = (List<Map<String, Object>>)updateTypeResponse.get( responseKey );

		if( aResponse != null ) {
			for( Map<String, Object> aDict : aResponse ) {
				final String errorMessage = (String)aDict.get( "errorMessage" );

				if( errorMessage != null ) {
					errorArray.add( errorMessage );
				}
			}
		}
	}

	private List<String> getCommandErrors( Map<String, Object>[] responseDicts ) {
		final List<String> errorArray = new ArrayList<>();

		for( int i = 0; i < responseDicts.length; i++ ) {
			if( responseDicts[i] != null ) {
				final Map<String, Object> responseDict = responseDicts[i];
				getGlobalErrorFromResponse( responseDict, errorArray );

				@SuppressWarnings("unchecked")
				final List<Map<String, String>> commandWotaskdResponse = (List<Map<String, String>>)responseDict.get( "commandWotaskdResponse" );

				if( (commandWotaskdResponse != null) && (commandWotaskdResponse.size() > 0) ) {
					int count = commandWotaskdResponse.size();

					for( int j = 1; j < count; j++ ) {
						final Map<String, String> aDict = commandWotaskdResponse.get( j );
						final String errorMessage = aDict.get( "errorMessage" );

						if( errorMessage != null ) {
							errorArray.add( errorMessage );
							if( j == 0 ) {
								break; // the command produced an error,
								// parsing didn't finish
							}
						}
					}
				}
			}
		}

		logger.debug( "##### getCommandErrors: " + errorArray );

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
		return errorArray;
	}

	private void getGlobalErrorFromResponse( Map<String, Object> responseDict, List<String> errorArray ) {
		@SuppressWarnings("unchecked")
		final List<String> errorResponse = (List<String>)responseDict.get( "errorResponse" );

		if( errorResponse != null ) {
			errorArray.addAll( errorResponse );
		}
	}

	public void getInstanceStatusForHosts( List<MHost> hostArray ) {
		if( hostArray.size() != 0 ) {

			final ResponseWrapper[] responses = sendQueryToWotaskds( "INSTANCE", hostArray );

			final List<String> errorArray = new ArrayList<>();
			Map<String, Object> responseDictionary = null;

			for( int i = 0; i < responses.length; i++ ) {
				if( (responses[i] == null) || (responses[i].contentString() == null) ) {
					responseDictionary = emptyResponse;
				}
				else {
					try {
						@SuppressWarnings("unchecked")
						final Map<String, Object> decoded = (Map<String, Object>)new FoundationCoder().decodeRootObjectFromString( responses[i].contentString() );
						responseDictionary = decoded;
					}
					catch( Exception e ) {
						logger.error( "MonitorComponent pageWithName(AppDetailPage) Error decoding response: " + responses[i].contentString() );
						responseDictionary = responseParsingFailed;
					}
				}
				getGlobalErrorFromResponse( responseDictionary, errorArray );

				@SuppressWarnings("unchecked")
				final Map<String, Object> queryResponseDictionary = (Map<String, Object>)responseDictionary.get( "queryWotaskdResponse" );

				if( queryResponseDictionary != null ) {
					@SuppressWarnings("unchecked")
					final List<Map<String, Object>> responseArray = (List<Map<String, Object>>)queryResponseDictionary.get( "instanceResponse" );

					if( responseArray != null ) {
						for( int j = 0; j < responseArray.size(); j++ ) {
							final Map<String, Object> instanceDict = responseArray.get( j );

							final String host = (String)instanceDict.get( "host" );
							final Integer port = (Integer)instanceDict.get( "port" );
							final String runningState = (String)instanceDict.get( "runningState" );
							final Boolean refusingNewSessions = (Boolean)instanceDict.get( "refusingNewSessions" );
							@SuppressWarnings("unchecked")
							final Map<String, String> statistics = (Map<String, String>)instanceDict.get( "statistics" );
							@SuppressWarnings("unchecked")
							final List<String> deaths = (List<String>)instanceDict.get( "deaths" );
							final String nextShutdown = (String)instanceDict.get( "nextShutdown" );

							final MInstance anInstance = siteConfig().instanceWithHostnameAndPort( host, port );

							if( anInstance != null ) {
								for( int k = 0; k < MUtil.INSTANCE_STATES.length; k++ ) {
									if( MUtil.INSTANCE_STATES[k].equals( runningState ) ) {
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
								anInstance.setDeaths( deaths == null ? new ArrayList<>() : new ArrayList<>( deaths ) );
								anInstance.setNextScheduledShutdownString_M( nextShutdown );
							}
						}
					}
				}
			}

			logger.debug( "##### pageWithName(AppDetailPage) errors: " + errorArray );

			errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
		}
	}

	private void getHostStatusForHosts( List<MHost> hostArray ) {
		final ResponseWrapper[] responses = sendQueryToWotaskds( "HOST", hostArray );

		final List<String> errorArray = new ArrayList<>();
		Map<String, Object> responseDict = null;

		for( int i = 0; i < responses.length; i++ ) {
			final MHost aHost = siteConfig().hostArray().get( i );

			if( (responses[i] == null) || (responses[i].contentString() == null) ) {
				responseDict = emptyResponse;
			}
			else {
				try {
					@SuppressWarnings("unchecked")
					final Map<String, Object> decoded = (Map<String, Object>)new FoundationCoder().decodeRootObjectFromString( responses[i].contentString() );
					responseDict = decoded;
				}
				catch( Exception e ) {
					logger.error( "MonitorComponent pageWithName(HostsPage) Error decoding response: " + responses[i].contentString() );
					responseDict = responseParsingFailed;
				}
			}

			getGlobalErrorFromResponse( responseDict, errorArray );

			@SuppressWarnings("unchecked")
			final Map<String, Object> queryResponse = (Map<String, Object>)responseDict.get( "queryWotaskdResponse" );

			if( queryResponse != null ) {
				@SuppressWarnings("unchecked")
				final Map<String, Object> hostResponse = (Map<String, Object>)queryResponse.get( "hostResponse" );
				aHost._setHostInfo( hostResponse );
				aHost.isAvailable = true;
			}
			else {
				aHost.isAvailable = false;
			}
		}

		logger.debug( "##### pageWithName(HostsPage) errors: " + errorArray );

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
	}

	private void getApplicationStatusForHosts( List<MHost> hostArray ) {

		final ResponseWrapper[] responses = sendQueryToWotaskds( "APPLICATION", hostArray );

		final List<String> errorArray = new ArrayList<>();
		Map<String, Object> queryResponseDictionary;

		for( int i = 0; i < responses.length; i++ ) {
			if( (responses[i] == null) || (responses[i].contentString() == null) ) {
				queryResponseDictionary = emptyResponse;
			}
			else {
				try {
					@SuppressWarnings("unchecked")
					final Map<String, Object> decoded = (Map<String, Object>)new FoundationCoder().decodeRootObjectFromString( responses[i].contentString() );
					queryResponseDictionary = decoded;
				}
				catch( Exception e ) {
					logger.error( "MonitorComponent pageWithName(ApplicationsPage) Error decoding response: " + responses[i].contentString() );
					queryResponseDictionary = responseParsingFailed;
				}
			}

			getGlobalErrorFromResponse( queryResponseDictionary, errorArray );

			@SuppressWarnings("unchecked")
			final Map<String, Object> applicationResponseDictionary = (Map<String, Object>)queryResponseDictionary.get( "queryWotaskdResponse" );

			if( applicationResponseDictionary != null ) {
				@SuppressWarnings("unchecked")
				final List<Map<String, Object>> responseArray = (List<Map<String, Object>>)applicationResponseDictionary.get( "applicationResponse" );

				if( responseArray != null ) {
					for( int j = 0; j < responseArray.size(); j++ ) {
						final Map<String, Object> appDict = responseArray.get( j );
						String appName = (String)appDict.get( "name" );
						Integer runningInstances = (Integer)appDict.get( "runningInstances" );
						MApplication anApplication = siteConfig().applicationWithName( appName );
						if( anApplication != null ) {
							anApplication.setRunningInstancesCount( anApplication.runningInstancesCount() + runningInstances.intValue() );
						}
					}
				}
			}
		}

		logger.debug( "##### pageWithName(ApplicationsPage) errors: " + errorArray );

		errorCollector().addObjectsFromArrayIfAbsentToErrorMessageArray( errorArray );
	}
}