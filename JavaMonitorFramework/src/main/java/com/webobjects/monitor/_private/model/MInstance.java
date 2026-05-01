/*
© Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */
package com.webobjects.monitor._private.model;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOMailDelivery;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.monitor._private.MUtil;

import x.FLog;

public class MInstance extends MObject {

	// Old common code
	protected NSMutableDictionary<String, Object> values;
	protected Map<String, Object> adaptorValues = Collections.synchronizedMap( new HashMap<>() );

	public NSMutableDictionary<String, Object> values() {
		return values;
	}

	//	String hostName;
	//	Integer id;
	//	Integer port;
	//	String applicationName;
	//	Boolean autoRecover;
	//	Integer minimumActiveSessionsCount;
	//	String path;
	//	Boolean cachingEnabled;
	//	Boolean debuggingEnabled;
	//	String outputPath;
	//	Boolean autoOpenInBrowser;
	//	Integer lifebeatInterval;
	//	String additionalArgs;
	//	Boolean schedulingEnabled;
	//	String schedulingType; // HOURLY | WEEKLY | DAILY
	//	Integer schedulingHourlyStartTime; // 1-24 O'clock
	//	Integer schedulingDailyStartTime; // 1-24 O'clock
	//	Integer schedulingWeeklyStartTime; // 1-24 O'clock
	//	Integer schedulingStartDay; // 1-7 (Mon-Sun)
	//	Integer schedulingInterval; // in hours
	//	Boolean gracefulScheduling;
	//	Integer sendTimeout;
	//	Integer recvTimeout;
	//	Integer cnctTimeout;
	//	Integer sendBufSize;
	//	Integer recvBufSize;

	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern( "MM/dd/yyyy HH:mm:ss zzz", Locale.US );
	private static final DateTimeFormatter SHUTDOWN_FORMATTER = DateTimeFormatter.ofPattern( "EEE '@' HH:00", Locale.US ).withZone( ZoneId.of( "UTC" ) );
	public static long TIME_FOR_STARTUP = 30;

	protected MHost _host;
	protected MApplication _application;
	private Instant _lastRegistration = Instant.EPOCH;
	private NSMutableArray<String> _deaths = new NSMutableArray<>();
	private boolean isRefusingNewSessions = false;
	public int state = MUtil.DEAD;
	private NSMutableDictionary _statistics = new NSMutableDictionary();
	private Timer _taskTimer;
	private TimerTask _forceQuitTask;
	private ZonedDateTime _nextScheduledShutdown = ZonedDateTime.of( LocalDate.of( 1970, 1, 1 ).atStartOfDay(), ZoneId.systemDefault() );
	private String _nextScheduledShutdownString = "-";
	private Instant _finishStartingByDate = Instant.now();
	private String _statisticsError = null;
	private int _connectFailureCount = 0;

	// This constructor is for adding new instances through the UI
	public MInstance( MHost aHost, MApplication anApplication, Integer anID, MSiteConfig aConfig ) {
		values = new NSMutableDictionary();
		_host = aHost;
		_application = anApplication;
		_siteConfig = aConfig;

		setApplicationName( _application.name() );
		setHostName( _host.name() );
		setId( anID );

		takeValuesFromApplication();

		setSchedulingEnabled( Boolean.FALSE );
		setSchedulingType( "DAILY" );
		setSchedulingHourlyStartTime( Integer.valueOf( 3 ) );
		setSchedulingDailyStartTime( Integer.valueOf( 3 ) );
		setSchedulingWeeklyStartTime( Integer.valueOf( 3 ) );
		setSchedulingStartDay( Integer.valueOf( 1 ) ); // Sunday
		setSchedulingInterval( Integer.valueOf( 12 ) );
		setGracefulScheduling( Boolean.TRUE );
	}

	// This constructor is for unarchiving Instances
	public MInstance( NSDictionary aDict, MSiteConfig aConfig ) {
		values = new NSMutableDictionary( aDict );

		_host = aConfig.hostWithName( hostName() );
		_application = aConfig.applicationWithName( applicationName() );
		_siteConfig = aConfig;
		calculateNextScheduledShutdown();
	}

	public String hostName() {
		return (String)values.valueForKey( "hostName" );
	}

	public void setHostName( String value ) {
		values.takeValueForKey( value, "hostName" );
		_siteConfig.dataHasChanged();
	}

	public Integer id() {
		return (Integer)values.valueForKey( "id" );
	}

	public void setId( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "id" );
		_siteConfig.dataHasChanged();
	}

	public Integer port() {
		return (Integer)values.valueForKey( "port" );
	}

	public void setPort( Integer value ) {
		Integer valVal = MUtil.validatedInteger( value );
		if( !valVal.equals( port() ) ) {
			setOldport( port() );
			values.takeValueForKey( valVal, "port" );
			_siteConfig.dataHasChanged();
		}
	}

	public String applicationName() {
		return (String)values.valueForKey( "applicationName" );
	}

	public void setApplicationName( String value ) {
		values.takeValueForKey( value, "applicationName" );
		_siteConfig.dataHasChanged();
	}

	public Boolean autoRecover() {
		return (Boolean)values.valueForKey( "autoRecover" );
	}

	public void setAutoRecover( Boolean value ) {
		values.takeValueForKey( value, "autoRecover" );
		_siteConfig.dataHasChanged();
	}

	public Integer minimumActiveSessionsCount() {
		return (Integer)values.valueForKey( "minimumActiveSessionsCount" );
	}

	public void setMinimumActiveSessionsCount( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "minimumActiveSessionsCount" );
		_siteConfig.dataHasChanged();
	}

	public String path() {
		return (String)values.valueForKey( "path" );
	}

	public void setPath( String value ) {
		values.takeValueForKey( value, "path" );
		_siteConfig.dataHasChanged();
	}

	public Boolean cachingEnabled() {
		return (Boolean)values.valueForKey( "cachingEnabled" );
	}

	public void setCachingEnabled( Boolean value ) {
		values.takeValueForKey( value, "cachingEnabled" );
		_siteConfig.dataHasChanged();
	}

	public Boolean debuggingEnabled() {
		return (Boolean)values.valueForKey( "debuggingEnabled" );
	}

	public void setDebuggingEnabled( Boolean value ) {
		values.takeValueForKey( value, "debuggingEnabled" );
		_siteConfig.dataHasChanged();
	}

	public String outputPath() {
		return (String)values.valueForKey( "outputPath" );
	}

	public void setOutputPath( String value ) {
		values.takeValueForKey( MUtil.validatedOutputPath( value ), "outputPath" );
		_siteConfig.dataHasChanged();
	}

	public Boolean autoOpenInBrowser() {
		return (Boolean)values.valueForKey( "autoOpenInBrowser" );
	}

	public void setAutoOpenInBrowser( Boolean value ) {
		values.takeValueForKey( value, "autoOpenInBrowser" );
		_siteConfig.dataHasChanged();
	}

	public Integer lifebeatInterval() {
		return (Integer)values.valueForKey( "lifebeatInterval" );
	}

	public void setLifebeatInterval( Integer value ) {
		values.takeValueForKey( MUtil.validatedLifebeatInterval( value ), "lifebeatInterval" );
		_siteConfig.dataHasChanged();
	}

	public String additionalArgs() {
		return (String)values.valueForKey( "additionalArgs" );
	}

	public void setAdditionalArgs( String value ) {
		values.takeValueForKey( value, "additionalArgs" );
		_siteConfig.dataHasChanged();
	}

	public Boolean schedulingEnabled() {
		return (Boolean)values.valueForKey( "schedulingEnabled" );
	}

	public void setSchedulingEnabled( Boolean value ) {
		values.takeValueForKey( value, "schedulingEnabled" );
		_siteConfig.dataHasChanged();
	}

	public String schedulingType() {
		return (String)values.valueForKey( "schedulingType" );
	}

	public void setSchedulingType( String value ) {
		values.takeValueForKey( MUtil.validatedSchedulingType( value ), "schedulingType" );
		_siteConfig.dataHasChanged();
	}

	public Integer schedulingHourlyStartTime() {
		return (Integer)values.valueForKey( "schedulingHourlyStartTime" );
	}

	public void setSchedulingHourlyStartTime( Integer value ) {
		values.takeValueForKey( MUtil.validatedSchedulingStartTime( value ), "schedulingHourlyStartTime" );
		_siteConfig.dataHasChanged();
	}

	public Integer schedulingDailyStartTime() {
		return (Integer)values.valueForKey( "schedulingDailyStartTime" );
	}

	public void setSchedulingDailyStartTime( Integer value ) {
		values.takeValueForKey( MUtil.validatedSchedulingStartTime( value ), "schedulingDailyStartTime" );
		_siteConfig.dataHasChanged();
	}

	public Integer schedulingWeeklyStartTime() {
		return (Integer)values.valueForKey( "schedulingWeeklyStartTime" );
	}

	public void setSchedulingWeeklyStartTime( Integer value ) {
		values.takeValueForKey( MUtil.validatedSchedulingStartTime( value ), "schedulingWeeklyStartTime" );
		_siteConfig.dataHasChanged();
	}

	public Integer schedulingStartDay() {
		return (Integer)values.valueForKey( "schedulingStartDay" );
	}

	public void setSchedulingStartDay( Integer value ) {
		values.takeValueForKey( MUtil.validatedSchedulingStartDay( value ), "schedulingStartDay" );
		_siteConfig.dataHasChanged();
	}

	public Integer schedulingInterval() {
		return (Integer)values.valueForKey( "schedulingInterval" );
	}

	public void setSchedulingInterval( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "schedulingInterval" );
		_siteConfig.dataHasChanged();
	}

	public Boolean gracefulScheduling() {
		return (Boolean)values.valueForKey( "gracefulScheduling" );
	}

	public void setGracefulScheduling( Boolean value ) {
		values.takeValueForKey( value, "gracefulScheduling" );
		_siteConfig.dataHasChanged();
	}

	public Integer sendTimeout() {
		return (Integer)values.valueForKey( "sendTimeout" );
	}

	public void setSendTimeout( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "sendTimeout" );
		_siteConfig.dataHasChanged();
	}

	public Integer recvTimeout() {
		return (Integer)values.valueForKey( "recvTimeout" );
	}

	public void setRecvTimeout( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "recvTimeout" );
		_siteConfig.dataHasChanged();
	}

	public Integer cnctTimeout() {
		return (Integer)values.valueForKey( "cnctTimeout" );
	}

	public void setCnctTimeout( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "cnctTimeout" );
		_siteConfig.dataHasChanged();
	}

	public Integer sendBufSize() {
		return (Integer)values.valueForKey( "sendBufSize" );
	}

	public void setSendBufSize( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "sendBufSize" );
		_siteConfig.dataHasChanged();
	}

	public Integer recvBufSize() {
		return (Integer)values.valueForKey( "recvBufSize" );
	}

	public void setRecvBufSize( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "recvBufSize" );
		_siteConfig.dataHasChanged();
	}

	/**
	 * FIXME: This was previously marked "don't use this". We whould trust whoever wrote that. Note that at the moment, this can be accessed through KVC in wotaskd's DirectAction class... // Hugi 2024-11-02
	 */
	@Deprecated
	public Integer oldport() {
		return (Integer)values.valueForKey( "oldport" );
	}

	/**
	 * FIXME: This was previously marked "don't use this". We whould trust whoever wrote that. Note that at the moment, this can be accessed through KVC in wotaskd's DirectAction class... // Hugi 2024-11-02
	 */
	@Deprecated
	public void setOldport( Integer value ) {
		values.takeValueForKey( MUtil.validatedInteger( value ), "oldport" );
		_siteConfig.dataHasChanged();
	}

	public MHost host() {
		return _host;
	}

	public MApplication application() {
		return _application;
	}

	public void _takeNameFromApplication() {
		setApplicationName( _application.name() );
	}

	public void _takePortFromApplication() {
		NSDictionary appValues = _application.values;
		MHost aHost = _host;

		Integer appPort = (Integer)appValues.valueForKey( "startingPort" );
		if( (port() == null) || ((port() != null) && (port().intValue() < appPort.intValue())) ) {
			setPort( aHost.nextAvailablePort( appPort ) );
		}
	}

	public void _takePathFromApplication() {
		NSDictionary appValues = _application.values;
		MHost aHost = _host;

		if( aHost.osType().equals( "UNIX" ) ) {
			values.takeValueForKey( appValues.valueForKey( "unixPath" ), "path" );
		}
		else if( aHost.osType().equals( "WINDOWS" ) ) {
			values.takeValueForKey( appValues.valueForKey( "winPath" ), "path" );
		}
		else if( aHost.osType().equals( "MACOSX" ) ) {
			values.takeValueForKey( appValues.valueForKey( "macPath" ), "path" );
		}
	}

	public void _takeOutputPathFromApplication() {
		NSDictionary appValues = _application.values;
		MHost aHost = _host;

		if( aHost.osType().equals( "UNIX" ) ) {
			values.takeValueForKey( generateOutputPath( (String)appValues.valueForKey( "unixOutputPath" ) ), "outputPath" );
		}
		else if( aHost.osType().equals( "WINDOWS" ) ) {
			values.takeValueForKey( generateOutputPath( (String)appValues.valueForKey( "winOutputPath" ) ), "outputPath" );
		}
		else if( aHost.osType().equals( "MACOSX" ) ) {
			values.takeValueForKey( generateOutputPath( (String)appValues.valueForKey( "macOutputPath" ) ), "outputPath" );
		}
	}

	public void _takeValueFromApplication( String valueName ) {
		final Object appValue = _application.values.valueForKey( valueName );
		values.takeValueForKey( appValue, valueName );
	}

	public void takeValuesFromApplication() {
		_takeNameFromApplication();
		_takePortFromApplication();

		_takeValueFromApplication( "autoRecover" );
		_takeValueFromApplication( "minimumActiveSessionsCount" );

		_takePathFromApplication();
		_takeOutputPathFromApplication();

		_takeValueFromApplication( "cachingEnabled" );
		_takeValueFromApplication( "debuggingEnabled" );
		_takeValueFromApplication( "autoOpenInBrowser" );
		_takeValueFromApplication( "lifebeatInterval" );
		_takeValueFromApplication( "additionalArgs" );
	}

	public String generateOutputPath( String pathEndingWithSeperator ) {

		if( pathEndingWithSeperator != null ) {
			return Path.of( pathEndingWithSeperator, displayName() ).normalize().toString();
		}

		return null;
	}

	public void extractAdaptorValuesFromApplication() {
		// get my instance settings
		adaptorValues.put( "sendTimeout", values.valueForKey( "sendTimeout" ) );
		adaptorValues.put( "recvTimeout", values.valueForKey( "recvTimeout" ) );
		adaptorValues.put( "cnctTimeout", values.valueForKey( "cnctTimeout" ) );
		adaptorValues.put( "sendBufSize", values.valueForKey( "sendBufSize" ) );
		adaptorValues.put( "recvBufSize", values.valueForKey( "recvBufSize" ) );

		// get MApplication application settings for setting that are still not set
		if( adaptorValues.get( "sendTimeout" ) == null ) {
			adaptorValues.put( "sendTimeout", _application.values.valueForKey( "sendTimeout" ) );
		}
		if( adaptorValues.get( "recvTimeout" ) == null ) {
			adaptorValues.put( "recvTimeout", _application.values.valueForKey( "recvTimeout" ) );
		}
		if( adaptorValues.get( "cnctTimeout" ) == null ) {
			adaptorValues.put( "cnctTimeout", _application.values.valueForKey( "cnctTimeout" ) );
		}
		if( adaptorValues.get( "sendBufSize" ) == null ) {
			adaptorValues.put( "sendBufSize", _application.values.valueForKey( "sendBufSize" ) );
		}
		if( adaptorValues.get( "recvBufSize" ) == null ) {
			adaptorValues.put( "recvBufSize", _application.values.valueForKey( "recvBufSize" ) );
		}

		// get MSiteConfig application settings for settings that are still not set
		if( adaptorValues.get( "sendTimeout" ) == null ) {
			adaptorValues.put( "sendTimeout", _siteConfig.sendTimeout() );
		}
		if( adaptorValues.get( "recvTimeout" ) == null ) {
			adaptorValues.put( "recvTimeout", _siteConfig.recvTimeout() );
		}
		if( adaptorValues.get( "cnctTimeout" ) == null ) {
			adaptorValues.put( "cnctTimeout", _siteConfig.cnctTimeout() );
		}
		if( adaptorValues.get( "sendBufSize" ) == null ) {
			adaptorValues.put( "sendBufSize", _siteConfig.sendBufSize() );
		}
		if( adaptorValues.get( "recvBufSize" ) == null ) {
			adaptorValues.put( "recvBufSize", _siteConfig.recvBufSize() );
		}
	}

	public String displayName() {
		return applicationName() + "-" + id();
	}

	public String displayHostAndPort() {
		return hostName() + ":" + port();
	}

	public NSDictionary statistics() {
		return _statistics;
	}

	public void setStatistics( NSDictionary newStatistics ) {
		_statistics.takeValueForKey( validatedStats( (String)newStatistics.valueForKey( "transactions" ) ), "transactions" );
		_statistics.takeValueForKey( validatedStats( (String)newStatistics.valueForKey( "activeSessions" ) ), "activeSessions" );
		_statistics.takeValueForKey( validatedStats( (String)newStatistics.valueForKey( "avgTransactionTime" ) ), "avgTransactionTime" );
		_statistics.takeValueForKey( validatedStats( (String)newStatistics.valueForKey( "averageIdlePeriod" ) ), "averageIdlePeriod" );
		_statistics.takeValueForKey( validatedStats( (String)newStatistics.valueForKey( "startedAt" ) ), "startedAt" );
	}

	/**
	 * FIXME: This reeally looks like it's just here to validate potentially bad data from wotaskd // Hugi 2024-11-07
	 */
	private static String validatedStats( String value ) {
		if( value == null ) {
			return "0";
		}

		int i = value.indexOf( '.' );
		int sLen = value.length() - 1;
		if( i == -1 ) {
			return value;
		}
		if( (i + 3) > sLen ) {
			return value;
		}
		return value.substring( 0, (i + 4) );
	}

	public String transactions() {
		if( _statistics != null ) {
			Object _value = _statistics.valueForKey( "transactions" );

			if( _value != null ) {
				return (_value.toString());
			}
		}
		return "-";
	}

	public String activeSessions() {
		if( _statistics != null ) {
			Object _value = _statistics.valueForKey( "activeSessions" );

			if( _value != null ) {
				return (_value.toString());
			}
		}
		return "-";
	}

	public String avgTransactionTime() {
		if( _statistics != null ) {
			Object _value = _statistics.valueForKey( "avgTransactionTime" );

			if( _value != null ) {
				return (_value.toString());
			}
		}
		return "-";
	}

	public String averageIdlePeriod() {
		if( _statistics != null ) {
			Object _value = _statistics.valueForKey( "averageIdlePeriod" );

			if( _value != null ) {
				return (_value.toString());
			}
		}
		return "-";
	}

	public void setStatisticsError( String errorString ) {
		_statisticsError = errorString;
	}

	public String statisticsError() {
		return _statisticsError;
	}

	public void resetStatisticsError() {
		_statisticsError = null;
	}

	/**
	 * Startup Calculations
	 */
	public void willAttemptToStart() {
		state = MUtil.STARTING;
		long timeForStartup;
		Integer tfs = _application.timeForStartup();

		if( tfs != null ) {
			timeForStartup = tfs.intValue();
		}
		else {
			timeForStartup = MInstance.TIME_FOR_STARTUP;
		}

		_finishStartingByDate = Instant.now().plusSeconds( timeForStartup );
	}

	public void failedToConnect() {
		_connectFailureCount++;

		// FIXME: The number of failures required to consider the instance dead should be a constant (or at least documented) // Hugi 2024-11-04
		if( _connectFailureCount > 2 ) {
			state = MUtil.DEAD;
			_lastRegistration = Instant.EPOCH;
		}
	}

	public void succeededInConnection() {
		_connectFailureCount = 0;
	}

	public boolean isRunning_M() {
		return (state == MUtil.ALIVE);
	}

	public int lifebeatCheckInterval() {
		Integer lb = lifebeatInterval();
		if( lb == null ) {
			return 30 * _siteConfig._appIsDeadMultiplier;
		}
		return lb.intValue() * _siteConfig._appIsDeadMultiplier;
	}

	public boolean isRunning_W() {
		// FIXME: lifebeatCheckInterval() is in seconds but is being added to a millisecond
		// epoch — preserved verbatim from the original to avoid behaviour changes during
		// the NSTimestamp → Instant migration. // Hugi 2026-05-01
		long currentTime = Instant.now().toEpochMilli();
		long cutOffTime = _lastRegistration.toEpochMilli() + lifebeatCheckInterval();
		long finishStartingByTime = _finishStartingByDate.toEpochMilli();

		if( state == MUtil.STARTING ) {
			// I'm still trying to start
			if( currentTime < finishStartingByTime ) {
				if( currentTime > cutOffTime ) {
					return false;
				}
				state = MUtil.ALIVE;
				return true;
				// I'm finished trying to start
			}
			// I've received a lifebeat in time
			if( currentTime > cutOffTime ) {
				addDeath();
				sendDeathNotificationEmail();
				setShouldDie( false );
				state = MUtil.DEAD;
				return false;
			}
			state = MUtil.ALIVE;
			return true;
		}
		else if( state == MUtil.ALIVE ) {
			if( currentTime > cutOffTime ) {
				addDeath();
				sendDeathNotificationEmail();
				setShouldDie( false );
				state = MUtil.DEAD;
				return false;
			}
			return true;
		}
		else if( state == MUtil.CRASHING ) {
			addDeath();
			sendDeathNotificationEmail();
			state = MUtil.DEAD;
			return false;
		}
		else { // UNKNOWN, DEAD, STOPPING
			if( currentTime > cutOffTime ) {
				state = MUtil.DEAD;
				return false;
			}
			// KH - I've returned to life - what should I do?
			state = MUtil.ALIVE;
			return true;
		}
	}

	public boolean isAutoRecovering() {
		Boolean aBool = autoRecover();
		if( aBool != null ) {
			return aBool.booleanValue();
		}
		return false;
	}

	public boolean isLocal_W() {
		if( host() == _siteConfig.localHost() ) {
			return true;
		}
		return false;
	}

	private boolean _shouldDie = false;

	public void setShouldDie( boolean b ) {
		_shouldDie = b;
	}

	public boolean shouldDie() {
		return _shouldDie;
	}

	public boolean shouldDieAndReset() {
		boolean b = _shouldDie;
		_shouldDie = false;
		return b;
	}

	public Instant lastRegistration() {
		return _lastRegistration;
	}

	public void startRegistration() {
		updateRegistration();
	}

	public void updateRegistration() {
		succeededInConnection();
		_lastRegistration = Instant.now();
	}

	public void registerStop() {
		succeededInConnection();
		_lastRegistration = Instant.EPOCH;
		state = MUtil.DEAD;
	}

	public void registerCrash() {
		succeededInConnection();
		_lastRegistration = Instant.EPOCH;
		state = MUtil.CRASHING;
	}

	public void sendDeathNotificationEmail() {

		final Instant currentTime = Instant.now();
		final String currentDate = currentTime.toString();

		// FIXME: see note on isRunning_W — preserves the seconds-vs-milliseconds inconsistency
		// from the original. // Hugi 2026-05-01
		final long cutOffTime = _lastRegistration.toEpochMilli() + lifebeatCheckInterval();

		String assumedToBeDead = "";

		if( currentTime.toEpochMilli() > cutOffTime ) {
			long secondsDifference = (currentTime.toEpochMilli() - _lastRegistration.toEpochMilli()) / 1000;
			assumedToBeDead = "The app did not respond for " + secondsDifference + " seconds " + "which is greater than the allowed threshold of " + lifebeatCheckInterval() + " seconds " + "(Lifebeat Interval * WOAssumeApplicationIsDeadMultiplier) so it is assumed to be dead.\n";
		}

		final String message = "Application '" + displayName() + "' on " + _host.name() + ":" + port() + " stopped running at " + (currentDate) + ".\n" + "The app's current state was: " + MUtil.INSTANCE_STATES[state] + ".\n" + assumedToBeDead + "The last successful communication occurred at: " + _lastRegistration.toString() + ". " + "This may be the result of a crash or an intentional shutdown from outside of wotaskd";

		FLog.err.appendln( message );

		boolean shouldEmail = false;
		final Boolean aBool = _application.notificationEmailEnabled();

		if( aBool != null ) {
			shouldEmail = aBool.booleanValue();
		}

		/**
		 * FIXME: Replace WOMailDelivery with a working mailer // Hugi 2024-11-04
		 */
		if( shouldEmail ) {
			try {
				final WOMailDelivery mailer = WOMailDelivery.sharedInstance();
				String fromAddress = siteConfig().emailReturnAddr();
				NSArray<String> toAddress = null;
				String subject = "App stopped running: " + displayName();
				String bodyText = message;

				if( fromAddress != null ) {
					fromAddress = "root@" + _host.name();
				}

				if( _application.notificationEmailAddr() != null ) {
					toAddress = NSArray.componentsSeparatedByString( _application.notificationEmailAddr(), "," );
				}

				if( mailer != null && toAddress != null && toAddress.count() > 0 ) {
					mailer.composePlainTextEmail( fromAddress, toAddress, null, subject, bodyText, true );
				}
			}
			catch( Throwable e ) {
				FLog.err.appendln( "Error attempting to send email: " + e );
			}
		}
	}

	/** ******** Deaths ********* */
	public NSMutableArray<String> deaths() {
		return _deaths;
	}

	public void setDeaths( NSMutableArray<String> values ) {
		_deaths = values;
	}

	public int deathCount() {
		return _deaths.count();
	}

	public void addDeath() {
		_deaths.addObject( DATE_FORMATTER.format( ZonedDateTime.now() ) );
	}

	public void removeAllDeaths() {
		_deaths = new NSMutableArray<>();
	}

	/** ******** Command Line Arguments ********* */
	private List<String> additionalArgumentsAsArray() {
		return Arrays.asList( additionalArgs().split( " " ) );
	}

	private String toNullOrString( Object o ) {
		if( o != null ) {
			return o.toString();
		}
		return null;
	}

	public List<String> commandLineArgumentsAsArray() {
		List<String> anArray = new ArrayList<>( 17 );

		// Only if we were passed a WOHost argument
		if( !WOApplication.application()._unsetHost ) {
			anArray.add( "-WOHost" );
			anArray.add( WOApplication.application().host() );
		}

		// instance stuff
		anArray.add( "-WOPort" );
		anArray.add( port().toString() );
		anArray.add( "-WOCachingEnabled" );
		anArray.add( booleanAsYNString( cachingEnabled() ) );
		anArray.add( "-WODebuggingEnabled" );
		anArray.add( booleanAsYNString( debuggingEnabled() ) );
		anArray.add( "-WOOutputPath" );
		anArray.add( MUtil.validatedOutputPath( outputPath() ) );
		anArray.add( "-WOAutoOpenInBrowser" );
		anArray.add( booleanAsYNString( autoOpenInBrowser() ) );
		anArray.add( "-WOAutoOpenClientApplication" );
		anArray.add( booleanAsYNString( autoOpenInBrowser() ) );
		anArray.add( "-WOLifebeatInterval" );
		anArray.add( lifebeatInterval().toString() );
		anArray.add( "-WOLifebeatEnabled" );
		anArray.add( "YES" );
		anArray.add( "-WOLifebeatDestinationPort" );
		anArray.add( String.valueOf( WOApplication.application().lifebeatDestinationPort() ) );

		// application stuff
		String adaptorString = toNullOrString( _application.adaptor() );
		if( adaptorString != null && adaptorString.length() > 0 ) {
			anArray.add( "-WOAdaptor" );
			anArray.add( adaptorString );
		}
		String adaptorThreadsString = toNullOrString( _application.adaptorThreads() );
		if( adaptorThreadsString != null && adaptorThreadsString.length() > 0 ) {
			anArray.add( "-WOWorkerThreadCount" );
			anArray.add( adaptorThreadsString );
		}
		String listenQueueSizeString = toNullOrString( _application.listenQueueSize() );
		if( listenQueueSizeString != null && listenQueueSizeString.length() > 0 ) {
			anArray.add( "-WOListenQueueSize" );
			anArray.add( listenQueueSizeString );
		}
		String adaptorThreadsMinString = toNullOrString( _application.adaptorThreadsMin() );
		if( adaptorThreadsMinString != null && adaptorThreadsMinString.length() > 0 ) {
			anArray.add( "-WOWorkerThreadCountMin" );
			anArray.add( adaptorThreadsMinString );
		}
		String adaptorThreadsMaxString = toNullOrString( _application.adaptorThreadsMax() );
		if( adaptorThreadsMaxString != null && adaptorThreadsMaxString.length() > 0 ) {
			anArray.add( "-WOWorkerThreadCountMax" );
			anArray.add( adaptorThreadsMaxString );
		}
		String projectSearchPathString = toNullOrString( _application.projectSearchPath() );
		if( projectSearchPathString != null && projectSearchPathString.length() > 0 ) {
			anArray.add( "-NSProjectSearchPath" );
			anArray.add( projectSearchPathString );
		}
		String sessionTimeOutString = toNullOrString( _application.sessionTimeOut() );
		if( sessionTimeOutString != null && sessionTimeOutString.length() > 0 ) {
			anArray.add( "-WOSessionTimeOut" );
			anArray.add( sessionTimeOutString );
		}
		String statisticsPasswordString = toNullOrString( _application.statisticsPassword() );
		if( statisticsPasswordString != null && statisticsPasswordString.length() > 0 ) {
			anArray.add( "-WOStatisticsPassword" );
			anArray.add( statisticsPasswordString );
		}

		String appNameString = toNullOrString( _application.name() );
		if( appNameString != null && appNameString.length() > 0 ) {
			anArray.add( "-WOApplicationName" );
			anArray.add( appNameString );
		}
		anArray.add( "-WOMonitorEnabled" );
		anArray.add( "YES" );
		anArray.add( "-WONoPause" );
		anArray.add( "YES" );

		// Additional Arguments
		String additionalArgsString = toNullOrString( additionalArgs() );
		if( additionalArgsString != null && additionalArgsString.length() > 0 ) {
			anArray.addAll( additionalArgumentsAsArray() );
		}

		return anArray;
	}

	private static String booleanAsYNString( final Boolean b ) {
		return (b != null && b.booleanValue()) ? "YES" : "NO";
	}

	public String commandLineArguments() {
		return String.join( " ", commandLineArgumentsAsArray() ).replace( '\n', ' ' ).replace( '\r', ' ' );
	}

	/**
	 * Overridden for Scheduling
	 */
	public void updateValues( NSDictionary aDict ) {
		values = new NSMutableDictionary<>( aDict );
		_siteConfig.dataHasChanged();

		if( isScheduled() ) {
			calculateNextScheduledShutdown();
		}
	}

	public boolean isScheduled() {
		Boolean aBool = schedulingEnabled();
		if( aBool != null ) {
			return aBool.booleanValue();
		}
		return false;
	}

	public boolean isGracefullyScheduled() {
		Boolean aBool = gracefulScheduling();
		if( aBool != null ) {
			return aBool.booleanValue();
		}
		return true;
	}

	public ZonedDateTime nextScheduledShutdown() {
		return _nextScheduledShutdown;
	}

	public void setNextScheduledShutdown( ZonedDateTime newtime ) {
		_nextScheduledShutdown = newtime;
		_nextScheduledShutdownString = SHUTDOWN_FORMATTER.format( _nextScheduledShutdown );
	}

	public String nextScheduledShutdownString() {
		return _nextScheduledShutdownString;
	}

	public void setNextScheduledShutdownString_M( String newtime ) {
		_nextScheduledShutdownString = newtime;
	}

	public boolean nearNextScheduledShutdown( Instant now ) {
		final long secondsAway = Math.abs( ChronoUnit.SECONDS.between( now, _nextScheduledShutdown.toInstant() ) );

		final long halfHourAsSeconds = 1800;

		if( secondsAway < halfHourAsSeconds ) {
			FLog.debug.appendln( "nearNextScheduledShutdown TRUE" );
			return true;
		}
		FLog.debug.appendln( "nearNextScheduledShutdown FALSE" );
		return false;
	}

	// Note that we store and calculate everything based on GMT (sort of).
	// User selects "17:00", as assume that this means "17:00 relative to the
	// timezone the application is running in".
	// Since we do the comparisons based on the same timezone, we are slightly
	// insulated from the timezone stuff.
	// Finally, we use a formatter to make it "look" like we are storing in the
	// correct timezone, even though we aren't.
	// This should only cause problems if you change the timezone of the
	// appserver.
	public void calculateNextScheduledShutdown() {

		if( !isScheduled() ) {
			return;
		}

		final ZonedDateTime now = ZonedDateTime.now( ZoneId.systemDefault() );
		final int currentHourOfDay = now.getHour(); // [0,23]

		// schedulingStartDay is persisted as 0=Sunday..6=Saturday. java.time's
		// DayOfWeek.getValue() is 1=Monday..7=Sunday, so % 7 lands us back in the
		// stored convention (Mon=1..Sat=6, Sun=0).
		final int currentDayOfWeek = now.getDayOfWeek().getValue() % 7;

		final String type = schedulingType();

		if( type.equals( "HOURLY" ) ) {
			final Integer startTimeTemp = schedulingHourlyStartTime();
			int startTime = (startTimeTemp != null) ? startTimeTemp.intValue() : -1;

			final Integer intervalTemp = schedulingInterval();
			final int interval = (intervalTemp != null) ? intervalTemp.intValue() : -1;

			if( (startTime == -1) || (interval == -1) ) {
				return;
			}

			// This is to make sure that we don't set it in the past!
			while( startTime <= currentHourOfDay ) {
				startTime += interval;
			}

			setNextScheduledShutdown( atHour( now, startTime, 0 ) );
		}
		else if( type.equals( "DAILY" ) ) {
			final Integer startTimeTemp = schedulingDailyStartTime();
			final int startTime = (startTimeTemp != null) ? startTimeTemp.intValue() : -1;

			if( startTime == -1 ) {
				return;
			}

			final int dayOffset = (startTime <= currentHourOfDay) ? 1 : 0;
			setNextScheduledShutdown( atHour( now, startTime, dayOffset ) );
		}
		else if( type.equals( "WEEKLY" ) ) {
			final Integer startTimeTemp = schedulingWeeklyStartTime();
			final int startTime = (startTimeTemp != null) ? startTimeTemp.intValue() : -1;

			final Integer startDayTemp = schedulingStartDay();
			final int startDay = (startDayTemp != null) ? startDayTemp.intValue() : -1;

			if( (startTime == -1) || (startDay == -1) ) {
				return;
			}

			final int temp = (startDay - currentDayOfWeek);
			int dayOffset = (temp < 0) ? 7 + temp : temp;

			// Same day, but checking for past times
			if( (temp == 0) && (startTime <= currentHourOfDay) ) {
				dayOffset += 7;
			}

			setNextScheduledShutdown( atHour( now, startTime, dayOffset ) );
		}
		FLog.debug.appendln( "calculateNextScheduledShutdown: " + _nextScheduledShutdown );
	}

	/**
	 * Returns {@code now}, advanced by {@code dayOffset} days, with hour set to {@code hour}
	 * and minutes/seconds/nanos zeroed. {@code hour} is permitted to exceed 23 — the overflow
	 * rolls into the following days, matching the original NSTimestamp behaviour where
	 * arithmetic on hour/day overflowed into the next calendar position.
	 */
	private static ZonedDateTime atHour( ZonedDateTime now, int hour, int dayOffset ) {
		return now.withMinute( 0 ).withSecond( 0 ).withNano( 0 ).withHour( 0 ).plusDays( dayOffset ).plusHours( hour );
	}

	public void setRefusingNewSessions( boolean isRefusingNewSessions ) {
		this.isRefusingNewSessions = isRefusingNewSessions;
	}

	public boolean isRefusingNewSessions() {
		return isRefusingNewSessions;
	}

	private int intStatisticsValueForKey( String key, int defaultValue ) {
		NSDictionary aStatsDict = statistics();

		if( aStatsDict != null ) {
			try {
				String aValue = (String)aStatsDict.valueForKey( key );
				if( aValue != null ) {
					return Integer.parseInt( aValue );
				}
			}
			catch( Throwable ex ) {
				// do nothing
			}
		}
		return defaultValue;
	}

	private float floatStatisticsValueForKey( String key, float defaultValue ) {
		NSDictionary aStatsDict = statistics();

		if( aStatsDict != null ) {
			try {
				String aValue = (String)aStatsDict.valueForKey( key );
				if( aValue != null ) {
					return Float.parseFloat( aValue );
				}
			}
			catch( Throwable ex ) {
				// do nothing
			}
		}
		return defaultValue;
	}

	public int transactionsValue() {
		return intStatisticsValueForKey( "transactions", 0 );
	}

	public int activeSessionsValue() {
		return intStatisticsValueForKey( "activeSessions", 0 );
	}

	public float avgIdleTimeValue() {
		return floatStatisticsValueForKey( "averageIdlePeriod", 0 );
	}

	public float avgTransactionTimeValue() {
		return floatStatisticsValueForKey( "avgTransactionTime", 0 );
	}

	/** ******** Force quit task ********* */

	public Timer taskTimer() {
		if( _taskTimer == null ) {
			_taskTimer = new Timer();
		}
		return _taskTimer;
	}

	/**
	 * Cancel the forceQuit task if any
	 */
	public void cancelForceQuitTask() {
		if( _taskTimer != null ) {
			_taskTimer.cancel();
			_forceQuitTask = null;
			_taskTimer = null;
		}
	}

	public void setForceQuitTask( TimerTask task ) {
		_forceQuitTask = task;
	}

	public TimerTask forceQuitTask() {
		return _forceQuitTask;
	}

	/**
	 * Only one force quit task can be scheduled
	 *
	 * @param task - task to schedule
	 * @param delay - delay before the task is fired (milliseconds)
	 */
	public void scheduleForceQuit( TimerTask task, int delay ) {
		if( _forceQuitTask == null ) {
			_forceQuitTask = task;
			taskTimer().schedule( _forceQuitTask, delay );
		}
	}

	/**
	 * Schedule a task to repeatedly run
	 *
	 * @param task - task to schedule
	 * @param delay - delay before the task runs (milliseconds)
	 * @param period - interval when the task is ran (milliseconds)
	 */
	public void scheduleRefuseTask( TimerTask task, int delay, int period ) {
		if( _forceQuitTask == null ) {
			_forceQuitTask = task;
			taskTimer().schedule( _forceQuitTask, delay, period );
		}
	}

	@Override
	public String toString() {
		return "MInstance@" + applicationName() + "-" + id();
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
		final MInstance other = (MInstance)obj;
		if( _application == null ) {
			if( other._application != null ) {
				return false;
			}
		}
		else if( !_application.equals( other._application ) ) {
			return false;
		}
		if( id() == null ) {
			if( other.id() != null ) {
				return false;
			}
		}
		else if( !id().equals( other.id() ) ) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((_application == null) ? 0 : _application.hashCode());
		result = prime * result + ((id() == null) ? 0 : id().hashCode());
		return result;
	}
}