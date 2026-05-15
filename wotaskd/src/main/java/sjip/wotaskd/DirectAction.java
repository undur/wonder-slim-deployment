package sjip.wotaskd;
/*
� Copyright 2006 - 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. (�Apple�) in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple�s copyrights in this original Apple software (the �Apple Software�), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
SUCH DAMAGE.
 */

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WODirectAction;
import com.webobjects.appserver.WOMessage;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

import sjip.core.MUtil;
import sjip.core.SjipException;
import sjip.core.model.MApplication;
import sjip.core.model.MApplicationDto;
import sjip.core.model.MHost;
import sjip.core.model.MHostDto;
import sjip.core.model.MInstance;
import sjip.core.model.MInstanceDto;
import sjip.core.model.MSiteConfig;
import sjip.core.model.MSiteConfigDto;
import sjip.core.model.MSiteConfigSiteDto;
import sjip.x.AdaptorConfigSerialization;
import sjip.x.FHosts;
import sjip.x.FProperties;
import sjip.x.FoundationCoder;
import sjip.x.FoundationPropertyListSerialization;
import sjip.x.ResponseWrapper;
import sjip.x.XUtil;

public class DirectAction extends WODirectAction {

	private static final Logger logger = LoggerFactory.getLogger( DirectAction.class );

	private static final String _hostName = WOApplication.application().host();

	private static final Map<String, Object> SUCCESS_ELEMENT = Map.of( "success", Boolean.TRUE );
	private static final String XML_ACCESS_DENIED = XUtil.errorResponseXML( "monitorResponse", _hostName + ": wotaskd may not be accessed through a Web server - Access Denied" );
	private static final String XML_INVALID_PASSWORD = XUtil.errorResponseXML( "monitorResponse", _hostName + ": Invalid Password - Access Denied" );
	private static final String XML_INVALID_XML = XUtil.errorResponseXML( "monitorResponse", _hostName + " - INTERNAL ERROR: Request from Monitor was Invalid" );
	private static final Map<String, Object> ARGUMENT_NUMBER_COMMAND_ERROR = errorElement( _hostName + " - INTERNAL ERROR: Not enough elements: Need 'commandString' + 'arrayOfInstances'" );

	private static Map<String, Object> errorElement( String message ) {
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put( "success", Boolean.FALSE );
		m.put( "errorMessage", message );
		return m;
	}
	private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME.withZone( ZoneOffset.UTC );

	public DirectAction( WORequest aRequest ) {
		super( aRequest );
	}

	// This is the biggie - this processes all requests from Monitor
	public WOActionResults monitorRequestAction() {

		final Application theApplication = (Application)WOApplication.application();
		final AppTaskd appTaskd = theApplication.appTaskd();
		final MSiteConfig aConfig = appTaskd.siteConfig();

		// Aren't allowed to call this through the Web server.
		if( FHosts.isUsingWebServer( request().headers() ) ) {
			logger.debug( "Attempt to call DirectAction: monitorRequestAction through Web server" );
			logger.debug( request().contentString() );
			final WOResponse forbiddenResponse = new WOResponse();
			forbiddenResponse.setStatus( WOMessage.HTTP_STATUS_FORBIDDEN );
			forbiddenResponse.appendContentString( XML_ACCESS_DENIED );
			return forbiddenResponse;
		}

		// Checking to see if the password was corrent
		appTaskd.lock().readLock().lock();
		try {
			String passwordHeader = request().headerForKey( "password" );
			if( !aConfig.checkPasswordEncrypted( passwordHeader ) ) {
				logger.debug( "Attempt to call DirectAction: monitorRequestAction with incorrect password." );
				final WOResponse forbiddenResponse = new WOResponse();
				forbiddenResponse.setStatus( WOMessage.HTTP_STATUS_FORBIDDEN );
				forbiddenResponse.appendContentString( XML_INVALID_PASSWORD );
				// the read lock is released in the finally block
				return forbiddenResponse;
			}
		}
		finally {
			appTaskd.lock().readLock().unlock();
		}

		Map<String, Object> requestDict;
		try {
			@SuppressWarnings("unchecked")
			final Map<String, Object> decoded = (Map<String, Object>)new FoundationCoder().decodeRootObject( request().content().bytes() );
			requestDict = decoded;
		}
		catch( Exception e ) {
			logger.error( "Wotaskd monitorRequestAction: Error parsing request" );
			logger.debug( "Wotaskd monitorRequestAction: " + request().contentString() );
			final WOResponse invalidXMLResponse = new WOResponse();
			invalidXMLResponse.appendContentString( XML_INVALID_XML );
			return invalidXMLResponse;
		}

		logger.debug( "\n@@@@@ monitorRequestAction received request from Monitor" );
		logger.debug( "@@@@@ monitorRequestAction requestDict: " + requestDict + "\n" );

		// These 2 get used for everything else - the global response object and the global error object.
		final Map<String, Object> monitorResponse = new LinkedHashMap<>();
		final List<String> errorResponse = new ArrayList<>();

		@SuppressWarnings("unchecked")
		final Map<String, Object> updateWotaskdDict = (Map<String, Object>)requestDict.get( "updateWotaskd" );
		@SuppressWarnings("unchecked")
		final List<Object> commandWotaskdArray = (List<Object>)requestDict.get( "commandWotaskd" );
		final String queryWotaskdString = (String)requestDict.get( "queryWotaskd" );

		// Checking for Updates
		if( updateWotaskdDict != null ) {
			appTaskd.lock().writeLock().lock();
			try {
				final Map<String, Object> updateWotaskdResponse = new LinkedHashMap<>();

				final String clearString = (String)updateWotaskdDict.get( "clear" );
				@SuppressWarnings("unchecked")
				final Map<String, Object> overwriteDict = (Map<String, Object>)updateWotaskdDict.get( "overwrite" );
				@SuppressWarnings("unchecked")
				final Map<String, Object> syncDict = (Map<String, Object>)updateWotaskdDict.get( "sync" );
				@SuppressWarnings("unchecked")
				final Map<String, Object> removeDict = (Map<String, Object>)updateWotaskdDict.get( "remove" );
				@SuppressWarnings("unchecked")
				final Map<String, Object> addDict = (Map<String, Object>)updateWotaskdDict.get( "add" );
				@SuppressWarnings("unchecked")
				final Map<String, Object> configureDict = (Map<String, Object>)updateWotaskdDict.get( "configure" );

				// FIXME: Dead branch — no client in our codebase sends the "clear" command. The
				// corresponding send-side (WOTaskdHandler.sendClearToWotaskd) is itself unused.
				// Candidate for deletion alongside that helper. Functionally redundant with an
				// "overwrite" carrying an empty SiteConfig. // Hugi 2026-05-11
				if( clearString != null ) {
					stopAllInstances();
					((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( null ) );
					updateWotaskdResponse.put( "clear", SUCCESS_ELEMENT );
				}
				else if( overwriteDict != null ) {
					stopAllInstances();
					@SuppressWarnings("unchecked")
					final Map<String, Object> siteConfigDict = (Map<String, Object>)overwriteDict.get( "SiteConfig" );
					final MSiteConfigDto dto = new FoundationCoder().decodeRecord( siteConfigDict, MSiteConfigDto.class );
					((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( dto ) );
					updateWotaskdResponse.put( "overwrite", SUCCESS_ELEMENT );
				}
				else if( syncDict != null ) {
					@SuppressWarnings("unchecked")
					final Map<String, Object> newConfig = (Map<String, Object>)syncDict.get( "SiteConfig" );
					syncSiteConfig( newConfig );
				}
				else {
					if( removeDict != null ) {
						final Map<String, Object> removeResponse = new LinkedHashMap<>();

						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> hostArray = (List<Map<String, Object>>)removeDict.get( "hostArray" );
						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> applicationArray = (List<Map<String, Object>>)removeDict.get( "applicationArray" );
						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> instanceArray = (List<Map<String, Object>>)removeDict.get( "instanceArray" );

						if( hostArray != null ) {
							final List<Object> hostArrayResponse = new ArrayList<>( hostArray.size() );

							// update-remove - for each host listed - hostWithName + (stopAllInstances/new siteConfig) | removeHost_W
							for( Map<String, Object> aHost : hostArray ) {
								String name = (String)aHost.get( "name" );
								MHost anMHost = aConfig.hostWithName( name );
								if( anMHost == null ) {
									hostArrayResponse.add( errorElement( _hostName + ": Host " + name + " not found; REMOVE failed" ) );
								}
								else {
									if( anMHost == aConfig.localHost() ) {
										stopAllInstances();
										((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( null ) );
									}
									else {
										aConfig.removeHost_W( anMHost );
									}
									hostArrayResponse.add( SUCCESS_ELEMENT );
								}
							}
							removeResponse.put( "hostArray", hostArrayResponse );
						}
						if( applicationArray != null ) {
							final List<Object> applicationArrayResponse = new ArrayList<>( applicationArray.size() );

							// update-remove - for each application listed - applicationWithName + removeApplication_W
							for( Map<String, Object> anApp : applicationArray ) {
								String name = (String)anApp.get( "name" );
								MApplication anMApplication = aConfig.applicationWithName( name );
								if( anMApplication == null ) {
									applicationArrayResponse.add( errorElement( _hostName + ": Application " + name + " not found; REMOVE failed" ) );
								}
								else {
									aConfig.removeApplication_W( aConfig.applicationWithName( name ) );
									applicationArrayResponse.add( SUCCESS_ELEMENT );
								}
							}
							removeResponse.put( "applicationArray", applicationArrayResponse );
						}
						if( instanceArray != null ) {
							final List<Object> instanceArrayResponse = new ArrayList<>( instanceArray.size() );

							// update-remove - for each instance listed - instanceWithHostnameAndPort + removeInstance_W
							for( Map<String, Object> anInst : instanceArray ) {
								String hostName = (String)anInst.get( "hostName" );
								Integer port = (Integer)anInst.get( "port" );
								MInstance anMInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
								if( anMInstance == null ) {
									instanceArrayResponse.add( errorElement( _hostName + ": Instance " + hostName + "-" + port + " not found; REMOVE failed" ) );
								}
								else {
									aConfig.removeInstance_W( anMInstance );
									instanceArrayResponse.add( SUCCESS_ELEMENT );
								}
							}
							removeResponse.put( "instanceArray", instanceArrayResponse );
						}
						updateWotaskdResponse.put( "remove", removeResponse );
					}

					if( addDict != null ) {
						final Map<String, Object> addResponse = new LinkedHashMap<>();

						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> hostArray = (List<Map<String, Object>>)addDict.get( "hostArray" );
						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> applicationArray = (List<Map<String, Object>>)addDict.get( "applicationArray" );
						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> instanceArray = (List<Map<String, Object>>)addDict.get( "instanceArray" );

						if( hostArray != null ) {
							final List<Object> hostArrayResponse = new ArrayList<>( hostArray.size() );

							// update-add - for each host listed - addHost_W
							for( Map<String, Object> aHost : hostArray ) {
								final MHostDto dto = new FoundationCoder().decodeRecord( aHost, MHostDto.class );
								aConfig.addHost_W( new MHost( dto, aConfig ) );
								hostArrayResponse.add( SUCCESS_ELEMENT );
							}
							addResponse.put( "hostArray", hostArrayResponse );
						}
						if( applicationArray != null ) {
							final List<Object> applicationArrayResponse = new ArrayList<>( applicationArray.size() );

							// update-add - for each application listed - addApplication_W
							for( Map<String, Object> anApp : applicationArray ) {
								final MApplicationDto dto = new FoundationCoder().decodeRecord( anApp, MApplicationDto.class );
								aConfig.addApplication_W( new MApplication( dto, aConfig ) );
								applicationArrayResponse.add( SUCCESS_ELEMENT );
							}
							addResponse.put( "applicationArray", applicationArrayResponse );
						}
						if( instanceArray != null ) {
							final List<Object> instanceArrayResponse = new ArrayList<>( instanceArray.size() );

							//  update-add - for each instance listed - addInstance_W
							for( Map<String, Object> anInst : instanceArray ) {
								final MInstanceDto dto = new FoundationCoder().decodeRecord( anInst, MInstanceDto.class );
								aConfig.addInstance_W( new MInstance( dto, aConfig ) );
								instanceArrayResponse.add( SUCCESS_ELEMENT );
							}
							addResponse.put( "instanceArray", instanceArrayResponse );
						}
						updateWotaskdResponse.put( "add", addResponse );
					}

					if( configureDict != null ) {
						final Map<String, Object> configureResponse = new LinkedHashMap<>();

						@SuppressWarnings("unchecked")
						final Map<String, Object> siteDict = (Map<String, Object>)configureDict.get( "site" );
						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> hostArray = (List<Map<String, Object>>)configureDict.get( "hostArray" );
						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> applicationArray = (List<Map<String, Object>>)configureDict.get( "applicationArray" );
						@SuppressWarnings("unchecked")
						final List<Map<String, Object>> instanceArray = (List<Map<String, Object>>)configureDict.get( "instanceArray" );

						if( siteDict != null ) {
							// update-configure - siteConfig.updateValues
							final MSiteConfigSiteDto siteDto = new FoundationCoder().decodeRecord( siteDict, MSiteConfigSiteDto.class );
							aConfig.updateValues( siteDto );
							configureResponse.put( "site", SUCCESS_ELEMENT );
						}
						if( hostArray != null ) {
							final List<Object> hostArrayResponse = new ArrayList<>( hostArray.size() );

							// update-configure - for each host listed - hostWithName + updateValues
							for( Map<String, Object> aHost : hostArray ) {
								final MHostDto dto = new FoundationCoder().decodeRecord( aHost, MHostDto.class );
								MHost anMHost = aConfig.hostWithName( dto.name() );
								if( anMHost == null ) {
									hostArrayResponse.add( errorElement( _hostName + ": Host " + dto.name() + " not found; UPDATE failed" ) );
								}
								else {
									anMHost.updateValues( dto );
									hostArrayResponse.add( SUCCESS_ELEMENT );
								}
							}
							configureResponse.put( "hostArray", hostArrayResponse );
						}
						if( applicationArray != null ) {
							final List<Object> applicationArrayResponse = new ArrayList<>( applicationArray.size() );

							// update-configure - for each application listed - applicationWithName + updateValues
							for( Map<String, Object> anApp : applicationArray ) {
								final MApplicationDto dto = new FoundationCoder().decodeRecord( anApp, MApplicationDto.class );
								String name = dto.name();
								MApplication anMApplication = aConfig.applicationWithName( name );
								// if I can't find the application, I might be updating the name - in that case, look under the oldname.
								if( anMApplication == null ) {
									name = dto.oldname();
									anMApplication = aConfig.applicationWithName( name );
								}

								if( anMApplication == null ) {
									applicationArrayResponse.add( errorElement( _hostName + ": Application " + name + " not found; UPDATE failed" ) );
								}
								else {
									anMApplication.updateValues( dto );
									applicationArrayResponse.add( SUCCESS_ELEMENT );
								}
							}
							configureResponse.put( "applicationArray", applicationArrayResponse );
						}
						if( instanceArray != null ) {
							final List<Object> instanceArrayResponse = new ArrayList<>( instanceArray.size() );

							// update-configure - for each instance listed - instanceWithHostnameAndPort + updateValues
							for( Map<String, Object> anInst : instanceArray ) {
								final MInstanceDto dto = new FoundationCoder().decodeRecord( anInst, MInstanceDto.class );
								Integer port = dto.port();
								MInstance anMInstance = aConfig.instanceWithHostnameAndPort( dto.hostName(), port );
								// if I can't find the instance, I might be updating the port - in that case, look under the oldport number.
								if( anMInstance == null ) {
									port = dto.oldport();
									anMInstance = aConfig.instanceWithHostnameAndPort( dto.hostName(), port );
								}
								if( anMInstance == null ) {
									instanceArrayResponse.add( errorElement( _hostName + ": Instance " + dto.hostName() + "-" + port + " not found; UPDATE failed" ) );
								}
								else {
									anMInstance.updateValues( dto );
									instanceArrayResponse.add( SUCCESS_ELEMENT );
								}
							}
							configureResponse.put( "instanceArray", instanceArrayResponse );
						}
						updateWotaskdResponse.put( "configure", configureResponse );
					}
				}
				monitorResponse.put( "updateWotaskdResponse", updateWotaskdResponse );
			}
			finally {
				appTaskd.lock().writeLock().unlock();
			}
		}

		// Checking for Commands
		if( commandWotaskdArray != null ) {
			int instArrayCount = commandWotaskdArray.size();
			final List<Object> commandWotaskdResponse = new ArrayList<>( instArrayCount );

			if( instArrayCount < 2 ) {
				commandWotaskdResponse.add( ARGUMENT_NUMBER_COMMAND_ERROR );
			}
			else {
				String command = (String)commandWotaskdArray.get( 0 );

				if( (command.equals( "START" )) || (command.equals( "CLEAR" )) ||
						(command.equals( "STOP" )) || (command.equals( "REFUSE" )) ||
						(command.equals( "ACCEPT" )) || (command.equals( "QUIT" )) ) {
					commandWotaskdResponse.add( SUCCESS_ELEMENT );
				}
				else {
					commandWotaskdResponse.add( errorElement( _hostName + " - INTERNAL ERROR: Invalid Command " + command ) );
				}

				// Go through each instance and do whatever it is that we do
				for( int i = 1; i < instArrayCount; i++ ) {
					@SuppressWarnings("unchecked")
					final Map<String, Object> instDict = (Map<String, Object>)commandWotaskdArray.get( i );
					String hostName = (String)instDict.get( "hostName" );
					Integer port = (Integer)instDict.get( "port" );
					appTaskd.lock().readLock().lock();
					try {
						MInstance anInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
						if( anInstance != null ) {
							if( anInstance.isLocal_W() ) {
								if( command.equals( "START" ) ) {
									String errorMsg = appTaskd.instanceController().startInstance( anInstance );
									if( errorMsg != null ) {
										commandWotaskdResponse.add( errorElement( errorMsg ) );
									}
								}
								else if( command.equals( "CLEAR" ) ) {
									anInstance.removeAllDeaths();
									commandWotaskdResponse.add( SUCCESS_ELEMENT );
								}
								else {
									try {
										if( command.equals( "STOP" ) ) {
											//we need to expect a response here
											if( appTaskd.instanceController().terminateInstance( anInstance ) == null )
												throw new SjipException( "No response to STOP " + anInstance.displayName() );
										}
										else if( command.equals( "REFUSE" ) ) {
											//we need to expect a response here
											if( appTaskd.instanceController().stopInstance( anInstance ) == null )
												throw new SjipException( "No response to REFUSE " + anInstance.displayName() );
										}
										else if( command.equals( "ACCEPT" ) ) {
											if( appTaskd.instanceController().setAcceptInstance( anInstance ) == null )
												throw new SjipException( "No response to ACCEPT " + anInstance.displayName() );
											//we got a response, cancel any force quit task
											anInstance.cancelForceQuitTask();
										}
										else if( command.equals( "QUIT" ) ) {
											anInstance.setShouldDie( true );
										}
										commandWotaskdResponse.add( SUCCESS_ELEMENT );
									}
									catch( SjipException me ) {
										commandWotaskdResponse.add( errorElement( me.getMessage() ) );
									}
								}
							}
							else {
								commandWotaskdResponse.add( SUCCESS_ELEMENT );
							}
						}
						else {
							commandWotaskdResponse.add( errorElement( _hostName + ": No instance found for Host " + hostName + " and Port: " + port + "; " + command + " failed" ) );
						}
					}
					finally {
						appTaskd.lock().readLock().unlock();
					}
				}
			}
			monitorResponse.put( "commandWotaskdResponse", commandWotaskdResponse );
		}

		// Checking for a Query
		if( queryWotaskdString != null ) {
			final Map<String, Object> queryWotaskdResponse = new LinkedHashMap<>();

			if( queryWotaskdString.equals( "SITE" ) ) {
				appTaskd.lock().readLock().lock();
				try {
					queryWotaskdResponse.put( "SiteConfig", aConfig.toDto() );
				}
				finally {
					appTaskd.lock().readLock().unlock();
				}
			}
			else if( queryWotaskdString.equals( "HOST" ) ) {
				// query - host.runningInstancesCount_W
				final String processorType = FProperties.sysProp( "os.arch" );
				final String operatingSystem = FProperties.sysProp( "os.name" ) + " " + FProperties.sysProp( "os.version" );

				final Map<String, Object> hostResponse = new LinkedHashMap<>();
				hostResponse.put( "processorType", processorType );
				hostResponse.put( "operatingSystem", operatingSystem );

				appTaskd.lock().readLock().lock();
				try {
					if( aConfig.localHost() != null ) {
						hostResponse.put( "runningInstances", aConfig.localHost().runningInstancesCount_W() );
					}
					else {
						hostResponse.put( "runningInstances", Integer.valueOf( 0 ) );
					}
				}
				finally {
					appTaskd.lock().readLock().unlock();
				}
				queryWotaskdResponse.put( "hostResponse", hostResponse );
			}
			else if( queryWotaskdString.equals( "APPLICATION" ) ) {
				List<Object> applicationResponse = null;
				appTaskd.lock().readLock().lock();
				try {
					List<MApplication> appArray = aConfig.applicationArray();
					int appArrayCount = appArray.size();

					applicationResponse = new ArrayList<>( appArrayCount );

					// query - for each application - runningInstancesCount_W();
					for( int i = 0; i < appArrayCount; i++ ) {
						MApplication anApp = appArray.get( i );
						final Map<String, Object> appDict = new LinkedHashMap<>();
						appDict.put( "name", anApp.name() );
						appDict.put( "runningInstances", anApp.runningInstancesCount_W() );
						applicationResponse.add( appDict );
					}
				}
				finally {
					appTaskd.lock().readLock().unlock();
				}

				queryWotaskdResponse.put( "applicationResponse", applicationResponse );
			}
			else if( queryWotaskdString.equals( "INSTANCE" ) ) {
				List<Object> instanceResponse = null;
				appTaskd.lock().readLock().lock();
				try {
					List<MInstance> instanceArray = (aConfig.localHost() != null) ? aConfig.localHost().instanceArray() : Collections.emptyList();
					int instanceArrayCount = instanceArray.size();

					instanceResponse = new ArrayList<>( instanceArrayCount );

					final List<MInstance> runningInstanceArray = new ArrayList<>();
					for( final MInstance anInst : instanceArray ) {
						if( anInst.isRunning_W() ) {
							runningInstanceArray.add( anInst );
						}
					}
					getStatisticsForInstanceArray( runningInstanceArray, errorResponse );

					for( int i = 0; i < instanceArrayCount; i++ ) {
						MInstance anInstance = instanceArray.get( i );

						String error = anInstance.statisticsError();
						if( error != null ) {
							errorResponse.add( error );
							//reset the error
							anInstance.resetStatisticsError();
						}
						// Continue, because wotaskd is expecting a response here.

						final Map<String, Object> instanceDict = new LinkedHashMap<>();
						instanceDict.put( "applicationName", anInstance.applicationName() );
						instanceDict.put( "id", anInstance.id() );
						instanceDict.put( "host", anInstance.hostName() );
						instanceDict.put( "port", anInstance.port() );
						instanceDict.put( "runningState", MUtil.INSTANCE_STATES[anInstance.state] );
						instanceDict.put( "refusingNewSessions", anInstance.isRefusingNewSessions() ? Boolean.TRUE : Boolean.FALSE );
						instanceDict.put( "statistics", anInstance.statistics().toDictionary() );
						instanceDict.put( "deaths", anInstance.deaths() );
						instanceDict.put( "nextShutdown", anInstance.nextScheduledShutdownString() );

						instanceResponse.add( instanceDict );
					}
				}
				finally {
					appTaskd.lock().readLock().unlock();
				}

				queryWotaskdResponse.put( "instanceResponse", instanceResponse );
			}
			else {
				errorResponse.add( _hostName + ": Unrecognized Query: " + queryWotaskdString );
			}
			monitorResponse.put( "queryWotaskdResponse", queryWotaskdResponse );
		}

		// getting the errors
		final List<String> globalErrors;
		synchronized( theApplication.siteConfig().globalErrorDictionary ) {
			globalErrors = new ArrayList<>( theApplication.siteConfig().globalErrorDictionary.values() );
			theApplication.siteConfig().globalErrorDictionary.clear();
		}
		if( !globalErrors.isEmpty() ) {
			errorResponse.addAll( globalErrors );
		}
		if( !errorResponse.isEmpty() ) {
			monitorResponse.put( "errorResponse", errorResponse );
		}

		logger.debug( "@@@@@ monitorRequestAction returning response to Monitor" );
		logger.debug( "@@@@@ monitorRequestAction responseDict: " + monitorResponse + "\n" );
		final WOResponse aResponse = new WOResponse();
		aResponse.appendContentString( (new FoundationCoder()).encodeRootObjectForKey( monitorResponse, "monitorResponse" ) );
		return aResponse;
	}

	private void getStatisticsForInstanceArray( final List<MInstance> instArray, List<String> errorResponse ) {
		final InstanceController instanceController = ((Application)WOApplication.application()).instanceController();

		final List<MInstance> instanceArray = instArray;
		int theCount = instanceArray.size();

		if( theCount == 0 )
			return;

		Thread[] workers = new Thread[theCount];
		final ResponseWrapper[] responses = new ResponseWrapper[theCount];

		for( int i = 0; i < theCount; i++ ) {
			final int j = i;
			Runnable work = new Runnable() {
				public void run() {
					try {
						responses[j] = instanceController.queryInstance( instanceArray.get( j ) );
					}
					catch( SjipException me ) {
						MInstance badInstance = instanceArray.get( j );
						if( !badInstance.isRunning_W() ) {
							logger.debug( "Exception getting Statistics for instance: " + instanceArray.get( j ).displayName() );
						}
						//if we get an exception and the instance state is running, that could mean the app may have been too
						//busy to respond of may have locked up in either case, we need to notify
						//java monitor which instance its having problems with
						if( badInstance.isRunning_W() )
							badInstance.setStatisticsError( me.getMessage() );
						responses[j] = null;
					}
				}
			};
			workers[j] = new Thread( work );
			workers[j].start();
		}

		try {
			for( int i = 0; i < theCount; i++ ) {
				workers[i].join();
			}
		}
		catch( InterruptedException ie ) {
		}

		for( int i = 0; i < theCount; i++ ) {
			ResponseWrapper aResponse = responses[i];
			MInstance anInstance = instArray.get( i );
			if( aResponse != null ) {
				anInstance.updateRegistration();
				if( aResponse.headerForKey( "x-webobjects-refusenewsessions" ) != null ) {
					anInstance.setRefusingNewSessions( true );
				}
				else {
					anInstance.setRefusingNewSessions( false );
				}

				Map<String, Object> instanceResponse = null;
				try {
					@SuppressWarnings("unchecked")
					final Map<String, Object> decoded = (Map<String, Object>)new FoundationCoder().decodeRootObjectFromString( aResponse.contentString() );
					instanceResponse = decoded;
				}
				catch( NullPointerException npe ) {
					logger.error( "Wotaskd getStatisticsForInstanceArray: No content returned from " + anInstance.displayName() );
					continue;
				}
				catch( Exception e ) {
					try {
						Object o = FoundationPropertyListSerialization.propertyListFromString( aResponse.contentString() );
						errorResponse.add( anInstance.displayName() + " is probably an older application that doesn't conform to the current Monitor Protocol. Please update and restart the instance." );
						logger.error( "Got old-style response from instance: " + anInstance.displayName() );
					}
					catch( Throwable t ) {
						logger.error( "Wotaskd getStatisticsForInstanceArray: Error parsing: " + aResponse.contentString() + " from " + anInstance.displayName() );
					}
					continue;
				}

				@SuppressWarnings("unchecked")
				final List<String> queryInstanceError = (List<String>)instanceResponse.get( "errorResponse" );
				if( queryInstanceError != null ) {
					anInstance.setStatisticsError( String.join( ", ", queryInstanceError ) );
					continue;
				}

				String queryInstanceResponse = (String)instanceResponse.get( "queryInstanceResponse" );
				if( queryInstanceResponse == null )
					continue;

				try {
					@SuppressWarnings("unchecked")
					final Map<String, Object> statistics = (Map<String, Object>)FoundationPropertyListSerialization.propertyListFromString( queryInstanceResponse );

					final Map<String, String> newStats = new LinkedHashMap<>();

					newStats.put( "startedAt", (String)statistics.get( "StartedAt" ) );

					@SuppressWarnings("unchecked")
					Map<String, Object> tempDict = (Map<String, Object>)statistics.get( "Transactions" );
					newStats.put( "transactions", (String)tempDict.get( "Transactions" ) );
					newStats.put( "avgTransactionTime", (String)tempDict.get( "Avg. Transaction Time" ) );
					newStats.put( "averageIdlePeriod", (String)tempDict.get( "Avg. Idle Time" ) );

					@SuppressWarnings("unchecked")
					Map<String, Object> sessionsDict = (Map<String, Object>)statistics.get( "Sessions" );
					newStats.put( "activeSessions", (String)sessionsDict.get( "Current Active Sessions" ) );

					anInstance.setStatistics( newStats );
				}
				catch( Exception e ) {
					// Do nothing - assume we died trying to parse the plist
					logger.error( "Wotaskd getStatisticsForInstanceArray: Error parsing PList: " + queryInstanceResponse + " from " + anInstance.displayName() );
				}
			}
			else if( anInstance.isRunning_M() ) {
				//display a hint that this instance is running but did not respond to a query statistics request
				anInstance.setStatisticsError( "No statistics for " + anInstance.displayName() );
			}
		}
	}

	private void syncSiteConfig( Map<String, Object> config ) {
		Application theApplication = (Application)WOApplication.application();
		MSiteConfig aConfig = theApplication.siteConfig();

		@SuppressWarnings("unchecked")
		final Map<String, Object> siteDict = (Map<String, Object>)config.get( "site" );
		@SuppressWarnings("unchecked")
		final List<Map<String, Object>> hostArray = (List<Map<String, Object>>)config.get( "hostArray" );
		@SuppressWarnings("unchecked")
		final List<Map<String, Object>> applicationArray = (List<Map<String, Object>>)config.get( "applicationArray" );
		@SuppressWarnings("unchecked")
		final List<Map<String, Object>> instanceArray = (List<Map<String, Object>>)config.get( "instanceArray" );

		// Configure the site
		if( siteDict != null ) {
			final MSiteConfigSiteDto siteDto = new FoundationCoder().decodeRecord( siteDict, MSiteConfigSiteDto.class );
			aConfig.updateValues( siteDto );
		}

		// Look through the array of hosts, and see if we need to add/remove any - configure the rest
		final List<MHost> currentHosts = new ArrayList<>( aConfig.hostArray() );
		if( hostArray != null ) {
			final FoundationCoder coder = new FoundationCoder();
			for( Map<String, Object> aHost : hostArray ) {
				final MHostDto dto = coder.decodeRecord( aHost, MHostDto.class );
				MHost anMHost = aConfig.hostWithName( dto.name() );
				if( anMHost == null ) {
					// we have to add it
					aConfig.addHost_W( new MHost( dto, aConfig ) );
				}
				else {
					// configure and remove from currentHosts
					anMHost.updateValues( dto );
					currentHosts.remove( anMHost );
				}
			}
		}
		// remove all hosts remaining in currentHosts
		for( final MHost anMHost : currentHosts ) {
			if( anMHost == aConfig.localHost() ) {
				stopAllInstances();
				((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( null ) );
				break;
			}
			aConfig.removeHost_W( anMHost );
		}

		// Look through the array of applications, and see if we need to add/remove any - configure the rest
		final List<MApplication> currentApplications = new ArrayList<>( aConfig.applicationArray() );
		if( applicationArray != null ) {
			final FoundationCoder coder = new FoundationCoder();
			for( Map<String, Object> anApp : applicationArray ) {
				final MApplicationDto dto = coder.decodeRecord( anApp, MApplicationDto.class );
				String name = dto.name();
				MApplication anMApplication = aConfig.applicationWithName( name );
				// if I can't find the application, I might be updating the name - in that case, look under the oldname.
				if( anMApplication == null ) {
					name = dto.oldname();
					anMApplication = aConfig.applicationWithName( name );
				}
				if( anMApplication == null ) {
					// we have to add it
					aConfig.addApplication_W( new MApplication( dto, aConfig ) );
				}
				else {
					// configure and remove from currentHosts
					anMApplication.updateValues( dto );
					currentApplications.remove( anMApplication );
				}
			}
		}
		// remove all hosts remaining in currentHosts
		for( final MApplication app : currentApplications ) {
			aConfig.removeApplication_W( app );
		}

		// Look through the array of instances, and see if we need to add/remove any - configure the rest
		final List<MInstance> currentInstances = new ArrayList<>( aConfig.instanceArray() );
		if( instanceArray != null ) {
			final FoundationCoder coder = new FoundationCoder();
			for( Map<String, Object> anInst : instanceArray ) {
				final MInstanceDto dto = coder.decodeRecord( anInst, MInstanceDto.class );
				Integer port = dto.port();
				MInstance anMInstance = aConfig.instanceWithHostnameAndPort( dto.hostName(), port );
				// if I can't find the instance, I might be updating the port - in that case, look under the oldport number.
				if( anMInstance == null ) {
					port = dto.oldport();
					anMInstance = aConfig.instanceWithHostnameAndPort( dto.hostName(), port );
				}
				if( anMInstance == null ) {
					// we have to add it
					aConfig.addInstance_W( new MInstance( dto, aConfig ) );
				}
				else {
					// configure and remove from currentHosts
					anMInstance.updateValues( dto );
					currentInstances.remove( anMInstance );
				}
			}
		}
		// remove all hosts remaining in currentHosts
		for( final MInstance inst : currentInstances ) {
			aConfig.removeInstance_W( inst );
		}
	}

	// This will stop all instances in parallel, and return after each stopInstance call has returned.
	private void stopAllInstances() {
		final InstanceController instanceController = ((Application)WOApplication.application()).instanceController();

		final List<MInstance> instanceArray = ((Application)WOApplication.application()).siteConfig().instanceArray();
		int theCount = instanceArray.size();

		if( theCount == 0 )
			return;

		Thread[] workers = new Thread[theCount];

		for( int i = 0; i < theCount; i++ ) {
			final int j = i;
			Runnable work = new Runnable() {
				public void run() {
					try {
						instanceController.stopInstance( instanceArray.get( j ) );
					}
					catch( SjipException me ) {
					}
				}
			};
			workers[j] = new Thread( work );
			workers[j].start();
		}

		try {
			for( int i = 0; i < theCount; i++ ) {
				workers[i].join();
			}
		}
		catch( InterruptedException ie ) {
		}
	}

	@Override
	public WOActionResults defaultAction() {
		final Application theApplication = (Application)WOApplication.application();
		final WOResponse aResponse = new WOResponse();
		final WORequest aRequest = request();
		final MSiteConfig aConfig = theApplication.siteConfig();

		theApplication.appTaskd().lock().readLock().lock();

		try {
			final String passwordHeader = aRequest.headerForKey( "password" );

			if( !aConfig.checkPasswordEncrypted( passwordHeader ) ) {
				logger.debug( "Attempt to call Direct Action: defaultAction with incorrect password." );
				aResponse.setStatus( WOMessage.HTTP_STATUS_FORBIDDEN );
				aResponse.appendContentString( "Attempt to call Direct Action: defaultAction on wotaskd with incorrect password." );
				return aResponse;
			}

			aResponse.appendContentString( "<html><head><title>Wotaskd for WebObjects 5</title></head><body>" );
			aResponse.appendContentString( "<center><b>Wotaskd for WebObjects 5: " + _hostName + "</b></center>" );
			aResponse.appendContentString( "<br><br><hr><br>Site Config as written to disk<br><hr><br><pre>" );
			aResponse.appendContentString( WOMessage.stringByEscapingHTMLString( aConfig.generateSiteConfigXML() ) );
			aResponse.appendContentString( "</pre><br><br><hr><br>Adaptor Config as sent to Local WOAdaptors - All Running Applications and Instances<br><hr><br><pre>" );
			aResponse.appendContentString( WOMessage.stringByEscapingHTMLString( AdaptorConfigSerialization.generateAdaptorConfigXML( aConfig, true, true ) ) );
			aResponse.appendContentString( "</pre><br><br><br><br>Adaptor Config as sent to remote WOAdaptors - All Registered and Running Applications and Instances<br><hr><br><pre>" );
			aResponse.appendContentString( WOMessage.stringByEscapingHTMLString( AdaptorConfigSerialization.generateAdaptorConfigXML( aConfig, true, false ) ) );
			aResponse.appendContentString( "</pre><br><br><hr><br>Adaptor Config as written to disk - All Registered Applications and Instances<br><hr><br><pre>" );
			aResponse.appendContentString( WOMessage.stringByEscapingHTMLString( AdaptorConfigSerialization.generateAdaptorConfigXML( aConfig, false, false ) ) );
			aResponse.appendContentString( "</pre><br><br><hr><br>Properties of this wotaskd<br><hr><br><pre>" );

			aResponse.appendContentString( "The Configuration Directory is: " + MSiteConfig.configDirectoryPath() );
			aResponse.appendContentString( "<br>" );
			if( ((Application)WOApplication.application()).shouldWriteAdaptorConfig() ) {
				aResponse.appendContentString( "Wotaskd is writing WOConfig.xml to disk" );
			}
			else {
				aResponse.appendContentString( "Wotaskd is NOT writing WOConfig.xml to disk" );
			}
			aResponse.appendContentString( "<br>" );
			aResponse.appendContentString( "The multicast address is: " + ((Application)WOApplication.application()).multicastAddress() );
			aResponse.appendContentString( "<br>" );
			aResponse.appendContentString( "This wotaskd is running on Port: " + WOApplication.application().port() );
			aResponse.appendContentString( "<br>" );
			if( ((Application)WOApplication.application()).shouldRespondToMulticast() ) {
				aResponse.appendContentString( "Wotaskd is responding to Multicast" );
			}
			else {
				aResponse.appendContentString( "Wotaskd is NOT responding to Multicast" );
			}
			aResponse.appendContentString( "<br>" );
			aResponse.appendContentString( "WOAssumeApplicationIsDeadMultiplier is " + (aConfig._appIsDeadMultiplier / 1000) );
			aResponse.appendContentString( "<br>" );
			aResponse.appendContentString( "The System Properties are: " );
			aResponse.appendContentString( WOMessage.stringByEscapingHTMLString( System.getProperties().toString() ) );
			aResponse.appendContentString( "</pre><br><br></body></html>" );
		}
		finally {
			theApplication.appTaskd().lock().readLock().unlock();
		}

		return aResponse;
	}

	// Adaptor Config Response
	public WOResponse woconfigAction() {
		final Application theApplication = (Application)WOApplication.application();
		final WORequest aRequest = request();

		// FIXME: WO-era cargo with a weak rationale — same-host adaptors get to see manually-started
		// instances; remote adaptors don't. The privacy boundary is asymmetric (registered instances
		// are visible to everyone) and the access-control intent is fuzzy. Goes away when the
		// unknown-applications subsystem is retired (#31).
		// We aren't going to regenerate the list, though, since this gets called a lot.
		boolean shouldIncludeUnregisteredInstances = FHosts.isAnyMachineLocalAddress( aRequest._originatingAddress(), false );

		theApplication.appTaskd().lock().readLock().lock();
		String xml;
		try {
			xml = AdaptorConfigSerialization.generateAdaptorConfigXML( ((Application)WOApplication.application()).siteConfig(), true, shouldIncludeUnregisteredInstances );
		}
		finally {
			theApplication.appTaskd().lock().readLock().unlock();
		}
		WOResponse aResponse = WOApplication.application().createResponseInContext( null );
		aResponse.appendContentString( xml );
		aResponse.setHeader( "text/xml", "content-type" );
		aResponse.setHeader( HTTP_DATE_FORMATTER.format( Instant.now() ), "Last-Modified" );
		logger.debug( "woConfigAction returned: " + xml );

		return aResponse;
	}

}
