/*
© Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */
package com.webobjects.monitor._private.model;

import java.util.Enumeration;

import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.monitor._private.MUtil;

public class MApplication extends MObject {

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

	//	String name;
	//	Integer startingPort;
	//	Integer timeForStartup;
	//	Boolean phasedStartup;
	//	Boolean autoRecover;
	//	Integer minimumActiveSessionsCount;
	//	String unixPath;
	//	String winPath;
	//	String macPath;
	//	Boolean cachingEnabled;
	//	String adaptor;
	//	Integer adaptorThreads;
	//	Integer listenQueueSize;
	//	Integer adaptorThreadsMin;
	//	Integer adaptorThreadsMax;
	//	String projectSearchPath;
	//	Integer sessionTimeOut;
	//	String statisticsPassword;
	//	Boolean debuggingEnabled;
	//	String unixOutputPath;
	//	String winOutputPath;
	//	String macOutputPath;
	//	Boolean autoOpenInBrowser;
	//	Integer lifebeatInterval;
	//	String additionalArgs;
	//	Boolean notificationEmailEnabled;
	//	String notificationEmailAddr;
	//	Integer retries;
	//	String scheduler;	// "RANDOM" | "ROUNDROBIN" | "LOADAVERAGE"
	//	Integer dormant;
	//	String redir;
	//	Integer sendTimeout;
	//	Integer recvTimeout;
	//	Integer cnctTimeout;
	//	Integer sendBufSize;
	//	Integer recvBufSize;
	//	Integer poolsize;
	//	Integer urlVersion;	// 3 | 4

	private NSMutableArray<MInstance> _instanceArray = new NSMutableArray<>();
	private NSMutableArray<MHost> _hostArray = new NSMutableArray<>();

	// Used for the ApplicationsPage
	private Integer runningInstancesCount = 0;

	// For UI
	public MApplication( String aName, MSiteConfig aConfig ) {
		this( new NSDictionary<Object, Object>( new Object[] { aName }, new Object[] { "name" } ), aConfig );
		takeValuesFromDefaults();
	}

	// For Unarchiving
	public MApplication( NSDictionary aDict, MSiteConfig aConfig ) {
		_siteConfig = aConfig;
		updateValues( aDict );
	}

	// For Cheating on the AppConfigurePage
	public MApplication( NSMutableDictionary aDict, MSiteConfig aConfig, Object o ) {
		_siteConfig = aConfig;
		values = aDict.mutableClone();
	}

	public String name() {
		return (String)values.valueForKey( "name" );
	}

	public void setName( String value ) {
		if( !value.equals( name() ) ) {
			setOldname( name() );
			values.takeValueForKey( value, "name" );
			dataChanged();
		}
	}

	public Integer startingPort() {
		return (Integer)values.valueForKey( "startingPort" );
	}

	public void setStartingPort( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "startingPort" );
		dataChanged();
	}

	public Integer timeForStartup() {
		return (Integer)values.valueForKey( "timeForStartup" );
	}

	public void setTimeForStartup( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "timeForStartup" );
		dataChanged();
	}

	public Boolean phasedStartup() {
		return (Boolean)values.valueForKey( "phasedStartup" );
	}

	public void setPhasedStartup( Boolean value ) {
		values.takeValueForKey( value, "phasedStartup" );
		dataChanged();
	}

	public Boolean autoRecover() {
		return (Boolean)values.valueForKey( "autoRecover" );
	}

	public void setAutoRecover( Boolean value ) {
		values.takeValueForKey( value, "autoRecover" );
		dataChanged();
	}

	public Integer minimumActiveSessionsCount() {
		return (Integer)values.valueForKey( "minimumActiveSessionsCount" );
	}

	public void setMinimumActiveSessionsCount( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "minimumActiveSessionsCount" );
		dataChanged();
	}

	public String unixPath() {
		return (String)values.valueForKey( "unixPath" );
	}

	public void setUnixPath( String value ) {
		values.takeValueForKey( value, "unixPath" );
		dataChanged();
	}

	public String winPath() {
		return (String)values.valueForKey( "winPath" );
	}

	public void setWinPath( String value ) {
		values.takeValueForKey( value, "winPath" );
		dataChanged();
	}

	public String macPath() {
		return (String)values.valueForKey( "macPath" );
	}

	public void setMacPath( String value ) {
		values.takeValueForKey( value, "macPath" );
		dataChanged();
	}

	public Boolean cachingEnabled() {
		return (Boolean)values.valueForKey( "cachingEnabled" );
	}

	public void setCachingEnabled( Boolean value ) {
		values.takeValueForKey( value, "cachingEnabled" );
		dataChanged();
	}

	public String adaptor() {
		return (String)values.valueForKey( "adaptor" );
	}

	public void setAdaptor( String value ) {
		values.takeValueForKey( value, "adaptor" );
		dataChanged();
	}

	public Integer adaptorThreads() {
		return (Integer)values.valueForKey( "adaptorThreads" );
	}

	public void setAdaptorThreads( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "adaptorThreads" );
		dataChanged();
	}

	public Integer listenQueueSize() {
		return (Integer)values.valueForKey( "listenQueueSize" );
	}

	public void setListenQueueSize( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "listenQueueSize" );
		dataChanged();
	}

	public Integer adaptorThreadsMin() {
		return (Integer)values.valueForKey( "adaptorThreadsMin" );
	}

	public void setAdaptorThreadsMin( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "adaptorThreadsMin" );
		dataChanged();
	}

	public Integer adaptorThreadsMax() {
		return (Integer)values.valueForKey( "adaptorThreadsMax" );
	}

	public void setAdaptorThreadsMax( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "adaptorThreadsMax" );
		dataChanged();
	}

	public String projectSearchPath() {
		return (String)values.valueForKey( "projectSearchPath" );
	}

	public void setProjectSearchPath( String value ) {
		values.takeValueForKey( value, "projectSearchPath" );
		dataChanged();
	}

	public Integer sessionTimeOut() {
		return (Integer)values.valueForKey( "sessionTimeOut" );
	}

	public void setSessionTimeOut( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "sessionTimeOut" );
		dataChanged();
	}

	public String statisticsPassword() {
		return (String)values.valueForKey( "statisticsPassword" );
	}

	public void setStatisticsPassword( String value ) {
		values.takeValueForKey( value, "statisticsPassword" );
		dataChanged();
	}

	public Boolean debuggingEnabled() {
		return (Boolean)values.valueForKey( "debuggingEnabled" );
	}

	public void setDebuggingEnabled( Boolean value ) {
		values.takeValueForKey( value, "debuggingEnabled" );
		dataChanged();
	}

	public String unixOutputPath() {
		return (String)values.valueForKey( "unixOutputPath" );
	}

	public void setUnixOutputPath( String value ) {
		values.takeValueForKey( value, "unixOutputPath" );
		dataChanged();
	}

	public String winOutputPath() {
		return (String)values.valueForKey( "winOutputPath" );
	}

	public void setWinOutputPath( String value ) {
		values.takeValueForKey( value, "winOutputPath" );
		dataChanged();
	}

	public String macOutputPath() {
		return (String)values.valueForKey( "macOutputPath" );
	}

	public void setMacOutputPath( String value ) {
		values.takeValueForKey( value, "macOutputPath" );
		dataChanged();
	}

	public Boolean autoOpenInBrowser() {
		return (Boolean)values.valueForKey( "autoOpenInBrowser" );
	}

	public void setAutoOpenInBrowser( Boolean value ) {
		values.takeValueForKey( value, "autoOpenInBrowser" );
		dataChanged();
	}

	public Integer lifebeatInterval() {
		return (Integer)values.valueForKey( "lifebeatInterval" );
	}

	public void setLifebeatInterval( Integer value ) {
		values.takeValueForKey( MUtil.validatedLifebeatInterval( value ), "lifebeatInterval" );
		dataChanged();
	}

	public String additionalArgs() {
		return (String)values.valueForKey( "additionalArgs" );
	}

	public void setAdditionalArgs( String value ) {
		values.takeValueForKey( value, "additionalArgs" );
		dataChanged();
	}

	public Boolean notificationEmailEnabled() {
		return (Boolean)values.valueForKey( "notificationEmailEnabled" );
	}

	public void setNotificationEmailEnabled( Boolean value ) {
		values.takeValueForKey( value, "notificationEmailEnabled" );
		dataChanged();
	}

	public String notificationEmailAddr() {
		return (String)values.valueForKey( "notificationEmailAddr" );
	}

	public void setNotificationEmailAddr( String value ) {
		values.takeValueForKey( value, "notificationEmailAddr" );
		dataChanged();
	}

	public Integer retries() {
		return (Integer)values.valueForKey( "retries" );
	}

	public void setRetries( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "retries" );
		dataChanged();
	}

	public String scheduler() {
		return (String)values.valueForKey( "scheduler" );
	}

	public void setScheduler( String value ) {
		values.takeValueForKey( value, "scheduler" );
		dataChanged();
	}

	public Integer dormant() {
		return (Integer)values.valueForKey( "dormant" );
	}

	public void setDormant( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "dormant" );
		dataChanged();
	}

	public String redir() {
		return (String)values.valueForKey( "redir" );
	}

	public void setRedir( String value ) {
		values.takeValueForKey( value, "redir" );
		dataChanged();
	}

	public Integer sendTimeout() {
		return (Integer)values.valueForKey( "sendTimeout" );
	}

	public void setSendTimeout( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "sendTimeout" );
		dataChanged();
	}

	public Integer recvTimeout() {
		return (Integer)values.valueForKey( "recvTimeout" );
	}

	public void setRecvTimeout( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "recvTimeout" );
		dataChanged();
	}

	public Integer cnctTimeout() {
		return (Integer)values.valueForKey( "cnctTimeout" );
	}

	public void setCnctTimeout( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "cnctTimeout" );
		dataChanged();
	}

	public Integer sendBufSize() {
		return (Integer)values.valueForKey( "sendBufSize" );
	}

	public void setSendBufSize( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "sendBufSize" );
		dataChanged();
	}

	public Integer recvBufSize() {
		return (Integer)values.valueForKey( "recvBufSize" );
	}

	public void setRecvBufSize( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "recvBufSize" );
		dataChanged();
	}

	public Integer poolsize() {
		return (Integer)values.valueForKey( "poolsize" );
	}

	public void setPoolsize( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "poolsize" );
		dataChanged();
	}

	public Integer urlVersion() {
		return (Integer)values.valueForKey( "urlVersion" );
	}

	public void setUrlVersion( Integer value ) {
		values.takeValueForKey( MUtil.validatedUrlVersion( value ), "urlVersion" );
		dataChanged();
	}

	/**
	 * ??
	 */
	public String oldname() {
		return (String)values.valueForKey( "oldname" );
	}

	/**
	 * ??
	 */
	public void setOldname( String value ) {
		values.takeValueForKey( value, "oldname" );
		dataChanged();
	}

	/********** Adding and Removing Instance primitives **********/
	public void _addInstancePrimitive( MInstance anInstance ) {

		_instanceArray.addObject( anInstance );

		if( !_hostArray.containsObject( anInstance._host ) ) {
			_hostArray.addObject( anInstance._host );
		}
	}

	public void _removeInstancePrimitive( MInstance anInstance ) {

		_instanceArray.removeObject( anInstance );
		boolean uniqueHost = true;

		for( Enumeration e = _instanceArray.objectEnumerator(); e.hasMoreElements(); ) {
			MInstance anInst = (MInstance)e.nextElement();
			if( anInstance._host == anInst._host ) {
				uniqueHost = false;
				break;
			}
		}

		if( uniqueHost ) {
			_hostArray.removeObject( anInstance._host );
		}
	}

	public NSArray<MInstance> instanceArray() {
		return _instanceArray;
	}

	public NSArray<MHost> hostArray() {
		return _hostArray;
	}

	private static NSDictionary<Object, Object> _defaults = new NSDictionary<>( new Object[] {
			Integer.valueOf( 2001 ),
			Integer.valueOf( 30 ),
			Boolean.TRUE,
			Boolean.TRUE,
			Integer.valueOf( 0 ),
			Boolean.TRUE,
			"WODefaultAdaptor",
			Integer.valueOf( 8 ),
			Integer.valueOf( 128 ),
			Integer.valueOf( 16 ),
			Integer.valueOf( 256 ),
			"()",
			Integer.valueOf( 3600 ),
			"",
			Boolean.FALSE,
			Boolean.FALSE,
			Integer.valueOf( 30 ),
			"",
			Boolean.FALSE,
			"",
			"" },
			new Object[] {
					"startingPort",
					"timeForStartup",
					"phasedStartup",
					"autoRecover",
					"minimumActiveSessionsCount",
					"cachingEnabled",
					"adaptor",
					"adaptorThreads",
					"listenQueueSize",
					"adaptorThreadsMin",
					"adaptorThreadsMax",
					"projectSearchPath",
					"sessionTimeOut",
					"statisticsPassword",
					"debuggingEnabled",
					"autoOpenInBrowser",
					"lifebeatInterval",
					"additionalArgs",
					"notificationEmailEnabled",
					"macOutputPath",
					"macPath" } );

	private void takeValuesFromDefaults() {
		values.addEntriesFromDictionary( _defaults );
	}

	public void pushValuesToInstances() {

		for( final MInstance instance : _instanceArray ) {
			instance.takeValuesFromApplication();
		}
	}

	public Integer nextID() {
		int instanceArrayCount = _instanceArray.count();
		int lastSequence = 0;
		for( int i = 0; i < instanceArrayCount; i++ ) {
			MInstance anInst = _instanceArray.objectAtIndex( i );
			int thisSequence = anInst.id().intValue();
			if( thisSequence > lastSequence ) {
				lastSequence = thisSequence;
			}
		}
		return Integer.valueOf( lastSequence + 1 );
	}

	public boolean isIDInUse( Integer ID ) {
		for( final MInstance instance : _instanceArray ) {
			if( instance.id().equals( ID ) ) {
				return true;
			}
		}

		return false;
	}

	public Integer runningInstancesCount_W() {
		int runningInstances = 0;

		for( final MInstance instance : _instanceArray ) {
			if( instance.isRunning_W() ) {
				runningInstances++;
			}
		}

		return Integer.valueOf( runningInstances );
	}

	public boolean isRunning_W() {
		return runningInstancesCount_W().intValue() > 0;
	}

	public boolean isRunning() {
		// AK: this one is called from the overview page (may or may not be correct)
		return runningInstancesCount.intValue() > 0;
	}

	public int runningInstancesCount() {
		return runningInstancesCount.intValue();
	}

	public void setRunningInstancesCount( int cnt ) {
		runningInstancesCount = Integer.valueOf( cnt );
	}

	// Used for the AppDetailPage
	public Integer runningInstancesCount_M() {
		int runningInstances = runningInstances_M().count();
		Integer riInt = Integer.valueOf( runningInstances );
		runningInstancesCount = riInt;
		return riInt;
	}

	public NSArray<MInstance> runningInstances_M() {
		final NSMutableArray<MInstance> instances = new NSMutableArray<>();

		for( final MInstance instance : _instanceArray ) {
			if( instance.isRunning_M() ) {
				instances.addObject( instance );
			}
		}

		return instances;
	}

	public boolean isRunning_M() {
		return runningInstancesCount_M().intValue() > 0;
	}

	public boolean isStopped_M() {

		for( final MInstance instance : _instanceArray ) {
			if( instance.state != MUtil.DEAD ) {
				return false;
			}
		}

		return true;
	}

	@Override
	public String toString() {
		return "MApplication@" + name();
	}

	@Override
	public boolean equals( Object obj ) {
		if( this == obj ) {
			return true;
		}
		if( obj == null ) {
			return false;
		}
		if( getClass() != obj.getClass() ) {
			return false;
		}
		final MApplication other = (MApplication)obj;
		if( name() == null ) {
			if( other.name() != null ) {
				return false;
			}
		}
		else if( !name().equals( other.name() ) ) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name() == null) ? 0 : name().hashCode());
		return result;
	}
}