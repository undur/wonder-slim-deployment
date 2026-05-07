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
import java.util.Enumeration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WODirectAction;
import com.webobjects.appserver.WOMessage;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;
import com.webobjects.appserver._private.WOHostUtilities;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;

import sjip.core.MUtil;
import sjip.core.SjipException;
import sjip.core.model.MApplication;
import sjip.core.model.MHost;
import sjip.core.model.MInstance;
import sjip.core.model.MSiteConfig;
import sjip.x.AdaptorConfigSerialization;
import sjip.x.FoundationCoder;
import sjip.x.FoundationPropertyListSerialization;
import sjip.x.ResponseWrapper;
import sjip.x.XUtil;

public class DirectAction extends WODirectAction {

	private static final Logger logger = LoggerFactory.getLogger( DirectAction.class );

	private NSMutableDictionary hostResponse;
	private NSDictionary element;

	private static final String _hostName;
	private static final Object[] _hostQueryKeys;
	private static final Object[] _appQueryKeys;
	private static final Object[] _instanceQueryKeys;
	private static final NSDictionary successElement;
	private static final Object[] _errorKeys;
	private static final String _accessDenied;
	private static final String _invalidPassword;
	private static final String _invalidXML;
	private static final String _emptyXML;
	private static final NSDictionary _argumentNumberCommandError;
	private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME.withZone( ZoneOffset.UTC );

	static {
		// get the hostname for the error messages
		_hostName = WOApplication.application().host();

		// pre-cache dictionary keys
		_hostQueryKeys = new Object[] { "runningInstances", "processorType", "operatingSystem" };
		_appQueryKeys = new Object[] { "name", "runningInstances" };
		_instanceQueryKeys = new Object[] { "applicationName", "id", "host", "port", "runningState", "refusingNewSessions", "statistics", "deaths", "nextShutdown" };
		successElement = new NSDictionary( new Object[] { Boolean.TRUE }, new Object[] { "success" } );
		_errorKeys = new Object[] { "success", "errorMessage" };

		// Pre-cache error messages
		_accessDenied = XUtil.errorResponseXML( "monitorResponse", _hostName + ": wotaskd may not be accessed through a Web server - Access Denied" );
		_invalidPassword = XUtil.errorResponseXML( "monitorResponse", _hostName + ": Invalid Password - Access Denied" );
		_invalidXML = XUtil.errorResponseXML( "monitorResponse", _hostName + " - INTERNAL ERROR: Request from Monitor was Invalid" );
		_emptyXML = XUtil.errorResponseXML( "monitorResponse", _hostName + " - INTERNAL ERROR: Request from Monitor was Empty" );
		_argumentNumberCommandError = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + " - INTERNAL ERROR: Not enough elements: Need 'commandString' + 'arrayOfInstances'" }, _errorKeys );
	}

	public DirectAction( WORequest aRequest ) {
		super( aRequest );
	}

	// This is the biggie - this processes all requests from Monitor
	public WOActionResults monitorRequestAction() {
		Application theApplication = (Application)WOApplication.application();
		MSiteConfig aConfig = theApplication.siteConfig();

		WORequest aRequest = request();
		WOResponse aResponse = theApplication.createResponseInContext( null );

		// Aren't allowed to call this through the Web server.
		if( aRequest.isUsingWebServer() ) {
			logger.debug( "Attempt to call DirectAction: monitorRequestAction through Web server" );
			logger.debug( aRequest.contentString() );
			aResponse.setStatus( WOMessage.HTTP_STATUS_FORBIDDEN );
			aResponse.appendContentString( _accessDenied );
			return aResponse;
		}

		// Checking to see if the password was corrent
		theApplication._lock.readLock().lock();
		try {
			String passwordHeader = aRequest.headerForKey( "password" );
			if( !aConfig.checkPasswordEncrypted( passwordHeader ) ) {
				logger.debug( "Attempt to call DirectAction: monitorRequestAction with incorrect password." );
				aResponse.setStatus( WOMessage.HTTP_STATUS_FORBIDDEN );
				aResponse.appendContentString( _invalidPassword );
				// the read lock is released in the finally block
				return aResponse;
			}
		}
		finally {
			theApplication._lock.readLock().unlock();
		}

		NSDictionary requestDict;
		try {
			requestDict = (NSDictionary)new FoundationCoder().decodeRootObject( aRequest.content().bytes() );
		}
		catch( Exception e ) {
			logger.error( "Wotaskd monitorRequestAction: Error parsing request" );
			logger.debug( "Wotaskd monitorRequestAction: " + aRequest.contentString() );
			aResponse.appendContentString( _invalidXML );
			return aResponse;
		}

		logger.debug( "\n@@@@@ monitorRequestAction received request from Monitor" );
		logger.debug( "@@@@@ monitorRequestAction requestDict: " + requestDict + "\n" );

		// These 2 get used for everything else - the global response object and the global error object.
		NSMutableDictionary monitorResponse = new NSMutableDictionary();
		NSMutableArray errorResponse = new NSMutableArray();

		NSDictionary updateWotaskdDict = (NSDictionary)requestDict.valueForKey( "updateWotaskd" );
		NSArray commandWotaskdArray = (NSArray)requestDict.valueForKey( "commandWotaskd" );
		String queryWotaskdString = (String)requestDict.valueForKey( "queryWotaskd" );

		// Checking for Updates
		if( updateWotaskdDict != null ) {
			theApplication._lock.writeLock().lock();
			try {
				NSMutableDictionary updateWotaskdResponse = new NSMutableDictionary( 2 );

				String clearString = (String)updateWotaskdDict.valueForKey( "clear" );
				NSDictionary overwriteDict = (NSDictionary)updateWotaskdDict.valueForKey( "overwrite" );
				NSDictionary syncDict = (NSDictionary)updateWotaskdDict.valueForKey( "sync" );
				NSDictionary removeDict = (NSDictionary)updateWotaskdDict.valueForKey( "remove" );
				NSDictionary addDict = (NSDictionary)updateWotaskdDict.valueForKey( "add" );
				NSDictionary configureDict = (NSDictionary)updateWotaskdDict.valueForKey( "configure" );

				if( clearString != null ) {
					stopAllInstances();
					((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( null ) );
					updateWotaskdResponse.takeValueForKey( successElement, "clear" );
				}
				else if( overwriteDict != null ) {
					stopAllInstances();
					((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( (NSDictionary)overwriteDict.valueForKey( "SiteConfig" ) ) );
					updateWotaskdResponse.takeValueForKey( successElement, "overwrite" );
				}
				else if( syncDict != null ) {
					NSDictionary newConfig = (NSDictionary)syncDict.valueForKey( "SiteConfig" );
					syncSiteConfig( newConfig );
				}
				else {
					if( removeDict != null ) {
						NSMutableDictionary removeResponse = new NSMutableDictionary( 1 );

						NSArray hostArray = (NSArray)removeDict.valueForKey( "hostArray" );
						NSArray applicationArray = (NSArray)removeDict.valueForKey( "applicationArray" );
						NSArray instanceArray = (NSArray)removeDict.valueForKey( "instanceArray" );

						if( hostArray != null ) {
							NSMutableArray hostArrayResponse = new NSMutableArray( hostArray.count() );

							// update-remove - for each host listed - hostWithName + (stopAllInstances/new siteConfig) | removeHost_W
							for( Enumeration e = hostArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary aHost = (NSDictionary)e.nextElement();
								String name = (String)aHost.valueForKey( "name" );
								MHost anMHost = aConfig.hostWithName( name );
								if( anMHost == null ) {
									element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + ": Host " + name + " not found; REMOVE failed" }, _errorKeys );
									hostArrayResponse.addObject( element );
								}
								else {
									if( anMHost == aConfig.localHost() ) {
										stopAllInstances();
										((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( null ) );
									}
									else {
										aConfig.removeHost_W( anMHost );
									}
									hostArrayResponse.addObject( successElement );
								}
							}
							removeResponse.takeValueForKey( hostArrayResponse, "hostArray" );
						}
						if( applicationArray != null ) {
							NSMutableArray applicationArrayResponse = new NSMutableArray( applicationArray.count() );

							// update-remove - for each application listed - applicationWithName + removeApplication_W
							for( Enumeration e = applicationArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary anApp = (NSDictionary)e.nextElement();
								String name = (String)anApp.valueForKey( "name" );
								MApplication anMApplication = aConfig.applicationWithName( name );
								if( anMApplication == null ) {
									element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + ": Application " + name + " not found; REMOVE failed" }, _errorKeys );
									applicationArrayResponse.addObject( element );
								}
								else {
									aConfig.removeApplication_W( aConfig.applicationWithName( name ) );
									applicationArrayResponse.addObject( successElement );
								}
							}
							removeResponse.takeValueForKey( applicationArrayResponse, "applicationArray" );
						}
						if( instanceArray != null ) {
							NSMutableArray instanceArrayResponse = new NSMutableArray( instanceArray.count() );

							// update-remove - for each instance listed - instanceWithHostnameAndPort + removeInstance_W
							for( Enumeration e = instanceArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary anInst = (NSDictionary)e.nextElement();
								String hostName = (String)anInst.valueForKey( "hostName" );
								Integer port = (Integer)anInst.valueForKey( "port" );
								MInstance anMInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
								if( anMInstance == null ) {
									element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + ": Instance " + hostName + "-" + port + " not found; REMOVE failed" }, _errorKeys );
									instanceArrayResponse.addObject( element );
								}
								else {
									aConfig.removeInstance_W( anMInstance );
									instanceArrayResponse.addObject( successElement );
								}
							}
							removeResponse.takeValueForKey( instanceArrayResponse, "instanceArray" );
						}
						updateWotaskdResponse.takeValueForKey( removeResponse, "remove" );
					}

					if( addDict != null ) {
						NSMutableDictionary addResponse = new NSMutableDictionary( 1 );

						NSArray hostArray = (NSArray)addDict.valueForKey( "hostArray" );
						NSArray applicationArray = (NSArray)addDict.valueForKey( "applicationArray" );
						NSArray instanceArray = (NSArray)addDict.valueForKey( "instanceArray" );

						if( hostArray != null ) {
							NSMutableArray hostArrayResponse = new NSMutableArray( hostArray.count() );

							// update-add - for each host listed - addHost_W
							for( Enumeration e = hostArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary aHost = (NSDictionary)e.nextElement();
								aConfig.addHost_W( new MHost( aHost, aConfig ) );
								hostArrayResponse.addObject( successElement );
							}
							addResponse.takeValueForKey( hostArrayResponse, "hostArray" );
						}
						if( applicationArray != null ) {
							NSMutableArray applicationArrayResponse = new NSMutableArray( applicationArray.count() );

							// update-add - for each application listed - addApplication_W
							for( Enumeration e = applicationArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary anApp = (NSDictionary)e.nextElement();
								aConfig.addApplication_W( new MApplication( anApp, aConfig ) );
								applicationArrayResponse.addObject( successElement );
							}
							addResponse.takeValueForKey( applicationArrayResponse, "applicationArray" );
						}
						if( instanceArray != null ) {
							NSMutableArray instanceArrayResponse = new NSMutableArray( instanceArray.count() );

							//  update-add - for each instance listed - addInstance_W
							for( Enumeration e = instanceArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary anInst = (NSDictionary)e.nextElement();
								aConfig.addInstance_W( new MInstance( anInst, aConfig ) );
								instanceArrayResponse.addObject( successElement );
							}
							addResponse.takeValueForKey( instanceArrayResponse, "instanceArray" );
						}
						updateWotaskdResponse.takeValueForKey( addResponse, "add" );
					}

					if( configureDict != null ) {
						NSMutableDictionary configureResponse = new NSMutableDictionary( 2 );

						NSDictionary siteDict = (NSDictionary)configureDict.valueForKey( "site" );
						NSArray hostArray = (NSArray)configureDict.valueForKey( "hostArray" );
						NSArray applicationArray = (NSArray)configureDict.valueForKey( "applicationArray" );
						NSArray instanceArray = (NSArray)configureDict.valueForKey( "instanceArray" );

						if( siteDict != null ) {
							// update-configure - siteConfig.updateValues
							aConfig.updateValues( siteDict );
							configureResponse.takeValueForKey( successElement, "site" );
						}
						if( hostArray != null ) {
							NSMutableArray hostArrayResponse = new NSMutableArray( hostArray.count() );

							// update-configure - for each host listed - hostWithName + updateValues
							for( Enumeration e = hostArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary aHost = (NSDictionary)e.nextElement();
								String name = (String)aHost.valueForKey( "name" );
								MHost anMHost = aConfig.hostWithName( name );
								if( anMHost == null ) {
									element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + ": Host " + name + " not found; UPDATE failed" }, _errorKeys );
									hostArrayResponse.addObject( element );
								}
								else {
									anMHost.updateValues( aHost );
									hostArrayResponse.addObject( successElement );
								}
							}
							configureResponse.takeValueForKey( hostArrayResponse, "hostArray" );
						}
						if( applicationArray != null ) {
							NSMutableArray applicationArrayResponse = new NSMutableArray( applicationArray.count() );

							// update-configure - for each application listed - applicationWithName + updateValues
							for( Enumeration e = applicationArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary anApp = (NSDictionary)e.nextElement();
								String name = (String)anApp.valueForKey( "name" );
								MApplication anMApplication = aConfig.applicationWithName( name );
								// if I can't find the application, I might be updating the name - in that case, look under the oldname.
								if( anMApplication == null ) {
									name = (String)anApp.valueForKey( "oldname" );
									anMApplication = aConfig.applicationWithName( name );
								}

								if( anMApplication == null ) {
									element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + ": Application " + name + " not found; UPDATE failed" }, _errorKeys );
									applicationArrayResponse.addObject( element );
								}
								else {
									anMApplication.updateValues( anApp );
									applicationArrayResponse.addObject( successElement );
								}
							}
							configureResponse.takeValueForKey( applicationArrayResponse, "applicationArray" );
						}
						if( instanceArray != null ) {
							NSMutableArray instanceArrayResponse = new NSMutableArray( instanceArray.count() );

							// update-configure - for each instance listed - instanceWithHostnameAndPort + updateValues
							for( Enumeration e = instanceArray.objectEnumerator(); e.hasMoreElements(); ) {
								NSDictionary anInst = (NSDictionary)e.nextElement();
								String hostName = (String)anInst.valueForKey( "hostName" );
								Integer port = (Integer)anInst.valueForKey( "port" );
								MInstance anMInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
								// if I can't find the instance, I might be updating the port - in that case, look under the oldport number.
								if( anMInstance == null ) {
									port = (Integer)anInst.valueForKey( "oldport" );
									anMInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
								}
								if( anMInstance == null ) {
									element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + ": Instance " + hostName + "-" + port + " not found; UPDATE failed" }, _errorKeys );
									instanceArrayResponse.addObject( element );
								}
								else {
									anMInstance.updateValues( anInst );
									instanceArrayResponse.addObject( successElement );
								}
							}
							configureResponse.takeValueForKey( instanceArrayResponse, "instanceArray" );
						}
						updateWotaskdResponse.takeValueForKey( configureResponse, "configure" );
					}
				}
				monitorResponse.takeValueForKey( updateWotaskdResponse, "updateWotaskdResponse" );
			}
			finally {
				theApplication._lock.writeLock().unlock();
			}
		}

		// Checking for Commands
		if( commandWotaskdArray != null ) {
			int instArrayCount = commandWotaskdArray.count();
			NSMutableArray commandWotaskdResponse = new NSMutableArray( instArrayCount );

			if( instArrayCount < 2 ) {
				commandWotaskdResponse.addObject( _argumentNumberCommandError );
			}
			else {
				String command = (String)commandWotaskdArray.objectAtIndex( 0 );

				if( (command.equals( "START" )) || (command.equals( "CLEAR" )) ||
						(command.equals( "STOP" )) || (command.equals( "REFUSE" )) ||
						(command.equals( "ACCEPT" )) || (command.equals( "QUIT" )) ) {
					commandWotaskdResponse.addObject( successElement );
				}
				else {
					element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + " - INTERNAL ERROR: Invalid Command " + command }, _errorKeys );
					commandWotaskdResponse.addObject( element );
				}

				// Go through each instance and do whatever it is that we do
				for( int i = 1; i < instArrayCount; i++ ) {
					NSDictionary instDict = (NSDictionary)commandWotaskdArray.objectAtIndex( i );
					String hostName = (String)instDict.valueForKey( "hostName" );
					Integer port = (Integer)instDict.valueForKey( "port" );
					theApplication._lock.readLock().lock();
					try {
						MInstance anInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
						if( anInstance != null ) {
							if( anInstance.isLocal_W() ) {
								if( command.equals( "START" ) ) {
									String errorMsg = theApplication.instanceController().startInstance( anInstance );
									if( errorMsg != null ) {
										element = new NSDictionary( new Object[] { Boolean.FALSE, errorMsg }, _errorKeys );
										commandWotaskdResponse.addObject( element );
									}
								}
								else if( command.equals( "CLEAR" ) ) {
									anInstance.removeAllDeaths();
									commandWotaskdResponse.addObject( successElement );
								}
								else {
									try {
										if( command.equals( "STOP" ) ) {
											//we need to expect a response here
											if( theApplication.instanceController().terminateInstance( anInstance ) == null )
												throw new SjipException( "No response to STOP " + anInstance.displayName() );
										}
										else if( command.equals( "REFUSE" ) ) {
											//we need to expect a response here
											if( theApplication.instanceController().stopInstance( anInstance ) == null )
												throw new SjipException( "No response to REFUSE " + anInstance.displayName() );
										}
										else if( command.equals( "ACCEPT" ) ) {
											if( theApplication.instanceController().setAcceptInstance( anInstance ) == null )
												throw new SjipException( "No response to ACCEPT " + anInstance.displayName() );
											//we got a response, cancel any force quit task
											anInstance.cancelForceQuitTask();
										}
										else if( command.equals( "QUIT" ) ) {
											anInstance.setShouldDie( true );
										}
										commandWotaskdResponse.addObject( successElement );
									}
									catch( SjipException me ) {
										element = new NSDictionary( new Object[] { Boolean.FALSE, me.getMessage() }, _errorKeys );
										commandWotaskdResponse.addObject( element );
									}
								}
							}
							else {
								//element = new NSDictionary(new Object[]{Boolean.FALSE, anInstance.displayName() + " does not exist on " + _hostName + "; " + command + " failed"}, errorKeys);
								//commandWotaskdResponse.addObject(element);
								commandWotaskdResponse.addObject( successElement );
							}
						}
						else {
							element = new NSDictionary( new Object[] { Boolean.FALSE, _hostName + ": No instance found for Host " + hostName + " and Port: " + port + "; " + command + " failed" }, _errorKeys );
							commandWotaskdResponse.addObject( element );
						}
					}
					finally {
						theApplication._lock.readLock().unlock();
					}
				}
			}
			monitorResponse.takeValueForKey( commandWotaskdResponse, "commandWotaskdResponse" );
		}

		// Checking for a Query
		if( queryWotaskdString != null ) {
			NSMutableDictionary queryWotaskdResponse = new NSMutableDictionary( 1 );

			if( queryWotaskdString.equals( "SITE" ) ) {
				theApplication._lock.readLock().lock();
				try {
					queryWotaskdResponse.takeValueForKey( aConfig.dictionaryForArchive(), "SiteConfig" );
				}
				finally {
					theApplication._lock.readLock().unlock();
				}
			}
			else if( queryWotaskdString.equals( "HOST" ) ) {
				// query - host.runningInstancesCount_W
				if( hostResponse == null ) {
					Integer runningInstances = Integer.valueOf( 0 );
					String processorType = System.getProperties().getProperty( "os.arch" );
					String operatingSystem = System.getProperties().getProperty( "os.name" ) + " " + System.getProperties().getProperty( "os.version" );

					hostResponse = new NSMutableDictionary( new Object[] { runningInstances, processorType, operatingSystem }, _hostQueryKeys );
				}
				theApplication._lock.readLock().lock();
				try {
					if( aConfig.localHost() != null ) {
						hostResponse.takeValueForKey( aConfig.localHost().runningInstancesCount_W(), "runningInstances" );
					}
					else {
						hostResponse.takeValueForKey( Integer.valueOf( 0 ), "runningInstances" );
					}
				}
				finally {
					theApplication._lock.readLock().unlock();
				}
				queryWotaskdResponse.takeValueForKey( hostResponse, "hostResponse" );
			}
			else if( queryWotaskdString.equals( "APPLICATION" ) ) {
				NSMutableArray applicationResponse = null;
				theApplication._lock.readLock().lock();
				try {
					NSArray appArray = aConfig.applicationArray();
					int appArrayCount = appArray.count();
					MApplication anApp;
					String name;
					Integer runningInstances;
					NSDictionary elementApp;

					applicationResponse = new NSMutableArray( appArrayCount );

					// query - for each application - runningInstancesCount_W();
					for( int i = 0; i < appArrayCount; i++ ) {
						anApp = (MApplication)appArray.objectAtIndex( i );
						name = anApp.name();
						runningInstances = anApp.runningInstancesCount_W();
						elementApp = new NSDictionary( new Object[] { name, runningInstances }, _appQueryKeys );
						applicationResponse.addObject( elementApp );
					}
				}
				finally {
					theApplication._lock.readLock().unlock();
				}

				queryWotaskdResponse.takeValueForKey( applicationResponse, "applicationResponse" );
			}
			else if( queryWotaskdString.equals( "INSTANCE" ) ) {
				NSMutableArray instanceResponse = null;
				theApplication._lock.readLock().lock();
				try {
					NSArray instanceArray = (aConfig.localHost() != null) ? aConfig.localHost().instanceArray() : NSArray.EmptyArray;
					int instanceArrayCount = instanceArray.count();

					MInstance anInstance;
					String applicationName;
					Integer id;
					String host;
					Integer port;
					String runningState;
					Boolean refusingNewSessions;
					NSDictionary statistics;
					NSArray deaths;
					String nextShutdown;
					NSDictionary elementInst;

					instanceResponse = new NSMutableArray( instanceArrayCount );

					NSMutableArray runningInstanceArray = new NSMutableArray();
					for( Enumeration e = instanceArray.objectEnumerator(); e.hasMoreElements(); ) {
						MInstance anInst = (MInstance)e.nextElement();
						if( anInst.isRunning_W() ) {
							runningInstanceArray.addObject( anInst );
						}
					}
					getStatisticsForInstanceArray( runningInstanceArray, errorResponse );

					for( int i = 0; i < instanceArrayCount; i++ ) {
						anInstance = (MInstance)instanceArray.objectAtIndex( i );

						String error = anInstance.statisticsError();
						if( error != null ) {
							errorResponse.addObject( error );
							//reset the error
							anInstance.resetStatisticsError();
						}
						// Continue, because wotaskd is expecting a response here.

						applicationName = anInstance.applicationName();
						id = anInstance.id();
						host = anInstance.hostName();
						port = anInstance.port();
						runningState = MUtil.INSTANCE_STATES[anInstance.state];
						statistics = anInstance.statistics().toDictionary();
						refusingNewSessions = (anInstance.isRefusingNewSessions()) ? Boolean.TRUE : Boolean.FALSE;
						deaths = anInstance.deaths();
						nextShutdown = anInstance.nextScheduledShutdownString();

						elementInst = new NSDictionary( new Object[] { applicationName, id, host, port, runningState, refusingNewSessions, statistics, deaths, nextShutdown }, _instanceQueryKeys );
						instanceResponse.addObject( elementInst );
					}
				}
				finally {
					theApplication._lock.readLock().unlock();
				}

				queryWotaskdResponse.takeValueForKey( instanceResponse, "instanceResponse" );
			}
			else {
				errorResponse.addObject( _hostName + ": Unrecognized Query: " + queryWotaskdString );
			}
			monitorResponse.takeValueForKey( queryWotaskdResponse, "queryWotaskdResponse" );
		}

		// getting the errors
		final List<String> globalErrors;
		synchronized( theApplication.siteConfig().globalErrorDictionary ) {
			globalErrors = new ArrayList<>( theApplication.siteConfig().globalErrorDictionary.values() );
			theApplication.siteConfig().globalErrorDictionary.clear();
		}
		if( !globalErrors.isEmpty() ) {
			errorResponse.addObjectsFromArray( new NSArray( globalErrors.toArray() ) );
		}
		if( errorResponse.count() != 0 ) {
			monitorResponse.takeValueForKey( errorResponse, "errorResponse" );
		}

		logger.debug( "@@@@@ monitorRequestAction returning response to Monitor" );
		logger.debug( "@@@@@ monitorRequestAction responseDict: " + monitorResponse + "\n" );
		aResponse.appendContentString( (new FoundationCoder()).encodeRootObjectForKey( monitorResponse, "monitorResponse" ) );
		return aResponse;
	}

	private void getStatisticsForInstanceArray( NSArray instArray, NSMutableArray errorResponse ) {
		final InstanceController instanceController = ((Application)WOApplication.application()).instanceController();

		final NSArray instanceArray = instArray;
		int theCount = instanceArray.count();

		if( theCount == 0 )
			return;

		Thread[] workers = new Thread[theCount];
		final ResponseWrapper[] responses = new ResponseWrapper[theCount];

		for( int i = 0; i < theCount; i++ ) {
			final int j = i;
			Runnable work = new Runnable() {
				public void run() {
					try {
						responses[j] = instanceController.queryInstance( (MInstance)instanceArray.objectAtIndex( j ) );
					}
					catch( SjipException me ) {
						MInstance badInstance = ((MInstance)instanceArray.objectAtIndex( j ));
						if( !badInstance.isRunning_W() ) {
							logger.debug( "Exception getting Statistics for instance: " + ((MInstance)instanceArray.objectAtIndex( j )).displayName() );
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
			MInstance anInstance = (MInstance)instArray.objectAtIndex( i );
			if( aResponse != null ) {
				anInstance.updateRegistration();
				if( aResponse.headerForKey( "x-webobjects-refusenewsessions" ) != null ) {
					anInstance.setRefusingNewSessions( true );
				}
				else {
					anInstance.setRefusingNewSessions( false );
				}

				NSDictionary instanceResponse = null;
				try {
					instanceResponse = (NSDictionary)new FoundationCoder().decodeRootObjectFromString( aResponse.contentString() );
				}
				catch( NullPointerException npe ) {
					logger.error( "Wotaskd getStatisticsForInstanceArray: No content returned from " + anInstance.displayName() );
					continue;
				}
				catch( Exception e ) {
					try {
						Object o = FoundationPropertyListSerialization.propertyListFromString( aResponse.contentString() );
						errorResponse.addObject( anInstance.displayName() + " is probably an older application that doesn't conform to the current Monitor Protocol. Please update and restart the instance." );
						logger.error( "Got old-style response from instance: " + anInstance.displayName() );
					}
					catch( Throwable t ) {
						logger.error( "Wotaskd getStatisticsForInstanceArray: Error parsing: " + aResponse.contentString() + " from " + anInstance.displayName() );
					}
					continue;
				}

				NSArray queryInstanceError = (NSArray)instanceResponse.valueForKey( "errorResponse" );
				if( queryInstanceError != null ) {
					anInstance.setStatisticsError( queryInstanceError.componentsJoinedByString( ", " ) );
					continue;
				}

				String queryInstanceResponse = (String)instanceResponse.valueForKey( "queryInstanceResponse" );
				if( queryInstanceResponse == null )
					continue;

				try {
					NSDictionary statistics = (NSDictionary)FoundationPropertyListSerialization.propertyListFromString( queryInstanceResponse );

					NSMutableDictionary newStats = new NSMutableDictionary( 5 );

					newStats.takeValueForKey( statistics.valueForKey( "StartedAt" ), "startedAt" );

					NSDictionary tempDict = (NSDictionary)statistics.valueForKey( "Transactions" );
					newStats.takeValueForKey( tempDict.valueForKey( "Transactions" ), "transactions" );
					newStats.takeValueForKey( tempDict.valueForKey( "Avg. Transaction Time" ), "avgTransactionTime" );
					newStats.takeValueForKey( tempDict.valueForKey( "Avg. Idle Time" ), "averageIdlePeriod" );

					tempDict = (NSDictionary)statistics.valueForKey( "Sessions" );
					newStats.takeValueForKey( tempDict.valueForKey( "Current Active Sessions" ), "activeSessions" );

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

	private void syncSiteConfig( NSDictionary config ) {
		Application theApplication = (Application)WOApplication.application();
		MSiteConfig aConfig = theApplication.siteConfig();

		NSDictionary siteDict = (NSDictionary)config.valueForKey( "site" );
		NSArray hostArray = (NSArray)config.valueForKey( "hostArray" );
		NSArray applicationArray = (NSArray)config.valueForKey( "applicationArray" );
		NSArray instanceArray = (NSArray)config.valueForKey( "instanceArray" );

		// Configure the site
		if( siteDict != null )
			aConfig.updateValues( siteDict );

		// Look through the array of hosts, and see if we need to add/remove any - configure the rest
		NSMutableArray currentHosts = new NSMutableArray( aConfig.hostArray() );
		if( hostArray != null ) {
			for( Enumeration e = hostArray.objectEnumerator(); e.hasMoreElements(); ) {
				NSDictionary aHost = (NSDictionary)e.nextElement();
				String name = (String)aHost.valueForKey( "name" );
				MHost anMHost = aConfig.hostWithName( name );
				if( anMHost == null ) {
					// we have to add it
					aConfig.addHost_W( new MHost( aHost, aConfig ) );
				}
				else {
					// configure and remove from currentHosts
					anMHost.updateValues( aHost );
					currentHosts.removeObject( anMHost );
				}
			}
		}
		// remove all hosts remaining in currentHosts
		for( Enumeration e = currentHosts.objectEnumerator(); e.hasMoreElements(); ) {
			MHost anMHost = (MHost)e.nextElement();
			if( anMHost == aConfig.localHost() ) {
				stopAllInstances();
				((Application)WOApplication.application()).setSiteConfig( new MSiteConfig( null ) );
				break;
			}
			aConfig.removeHost_W( anMHost );
		}

		// Look through the array of applications, and see if we need to add/remove any - configure the rest
		NSMutableArray currentApplications = new NSMutableArray( aConfig.applicationArray() );
		if( applicationArray != null ) {
			for( Enumeration e = applicationArray.objectEnumerator(); e.hasMoreElements(); ) {
				NSDictionary anApp = (NSDictionary)e.nextElement();
				String name = (String)anApp.valueForKey( "name" );
				MApplication anMApplication = aConfig.applicationWithName( name );
				// if I can't find the application, I might be updating the name - in that case, look under the oldname.
				if( anMApplication == null ) {
					name = (String)anApp.valueForKey( "oldname" );
					anMApplication = aConfig.applicationWithName( name );
				}
				if( anMApplication == null ) {
					// we have to add it
					aConfig.addApplication_W( new MApplication( anApp, aConfig ) );
				}
				else {
					// configure and remove from currentHosts
					anMApplication.updateValues( anApp );
					currentApplications.removeObject( anMApplication );
				}
			}
		}
		// remove all hosts remaining in currentHosts
		for( Enumeration e = currentApplications.objectEnumerator(); e.hasMoreElements(); ) {
			aConfig.removeApplication_W( (MApplication)e.nextElement() );
		}

		// Look through the array of instances, and see if we need to add/remove any - configure the rest
		NSMutableArray currentInstances = new NSMutableArray( aConfig.instanceArray() );
		if( instanceArray != null ) {
			for( Enumeration e = instanceArray.objectEnumerator(); e.hasMoreElements(); ) {
				NSDictionary anInst = (NSDictionary)e.nextElement();
				String hostName = (String)anInst.valueForKey( "hostName" );
				Integer port = (Integer)anInst.valueForKey( "port" );
				MInstance anMInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
				// if I can't find the instance, I might be updating the port - in that case, look under the oldport number.
				if( anMInstance == null ) {
					port = (Integer)anInst.valueForKey( "oldport" );
					anMInstance = aConfig.instanceWithHostnameAndPort( hostName, port );
				}
				if( anMInstance == null ) {
					// we have to add it
					aConfig.addInstance_W( new MInstance( anInst, aConfig ) );
				}
				else {
					// configure and remove from currentHosts
					anMInstance.updateValues( anInst );
					currentInstances.removeObject( anMInstance );
				}
			}
		}
		// remove all hosts remaining in currentHosts
		for( Enumeration e = currentInstances.objectEnumerator(); e.hasMoreElements(); ) {
			aConfig.removeInstance_W( (MInstance)e.nextElement() );
		}
	}

	// This will stop all instances in parallel, and return after each stopInstance call has returned.
	private void stopAllInstances() {
		final InstanceController instanceController = ((Application)WOApplication.application()).instanceController();

		final NSArray instanceArray = ((Application)WOApplication.application()).siteConfig().instanceArray();
		int theCount = instanceArray.count();

		if( theCount == 0 )
			return;

		Thread[] workers = new Thread[theCount];

		for( int i = 0; i < theCount; i++ ) {
			final int j = i;
			Runnable work = new Runnable() {
				public void run() {
					try {
						instanceController.stopInstance( (MInstance)instanceArray.objectAtIndex( j ) );
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
		// KH - make this faster as well :)
		Application theApplication = (Application)WOApplication.application();
		WOResponse aResponse = theApplication.createResponseInContext( null );
		WORequest aRequest = request();
		MSiteConfig aConfig = theApplication.siteConfig();

		theApplication._lock.readLock().lock();
		try {

			// Check for correct password
			String passwordHeader = aRequest.headerForKey( "password" );
			if( !aConfig.checkPasswordEncrypted( passwordHeader ) ) {
				logger.debug( "Attempt to call Direct Action: defaultAction with incorrect password." );
				aResponse.setStatus( WOMessage.HTTP_STATUS_FORBIDDEN );
				aResponse.appendContentString( "Attempt to call Direct Action: defaultAction on wotaskd with incorrect password." );
				// the read lock is released in the finally block
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
			theApplication._lock.readLock().unlock();
		}

		return aResponse;
	}

	// Adaptor Config Response
	public WOResponse woconfigAction() {
		Application theApplication = (Application)WOApplication.application();
		WORequest aRequest = request();

		// This will return true if we match either WOHost or any known local address
		// We aren't going to regenerate the list, though, since this gets called a lot.
		boolean shouldIncludeUnregisteredInstances = WOHostUtilities.isAnyLocalInetAddress( aRequest._originatingAddress(), false );

		theApplication._lock.readLock().lock();
		String xml;
		try {
			xml = AdaptorConfigSerialization.generateAdaptorConfigXML( ((Application)WOApplication.application()).siteConfig(), true, shouldIncludeUnregisteredInstances );
		}
		finally {
			theApplication._lock.readLock().unlock();
		}
		WOResponse aResponse = WOApplication.application().createResponseInContext( null );
		aResponse.appendContentString( xml );
		aResponse.setHeader( "text/xml", "content-type" );
		aResponse.setHeader( HTTP_DATE_FORMATTER.format( Instant.now() ), "Last-Modified" );
		logger.debug( "woConfigAction returned: " + xml );

		return aResponse;
	}

	/**********/

	// used by WOInfoCenter and perhaps others
	public WOActionResults findPortAction() {
		Application theApplication = (Application)WOApplication.application();
		WOResponse aResponse = theApplication.createResponseInContext( null );
		WORequest aRequest = request();
		String portString = null;

		// We wouldn't have registered it in the first place, so we don't regenerate
		if( WOHostUtilities.isAnyLocalInetAddress( aRequest._originatingAddress(), false ) ) {
			String anAppName = request().stringFormValueForKey( "appName" );
			portString = theApplication.instanceController().portForUnregisteredAppNamed( anAppName );
		}

		if( portString == null ) {
			portString = "-1";
		}
		aResponse.appendContentString( portString );
		return aResponse;
	}

}
