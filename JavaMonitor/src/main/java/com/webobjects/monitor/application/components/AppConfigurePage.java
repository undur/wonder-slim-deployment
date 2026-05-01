package com.webobjects.monitor.application.components;

import java.util.List;

/*
 (c) Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

 IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

 In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

 The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

 IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
 SUCH DAMAGE.
 */
import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;
import com.webobjects.monitor._private.MUtil;
import com.webobjects.monitor._private.StringExtensions;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor._private.model.MSiteConfig;
import com.webobjects.monitor.application.MonitorComponent.AppComponent;

public class AppConfigurePage extends AppComponent {

	private static MSiteConfig _sc = new MSiteConfig( null );

	public boolean isNewInstanceSectionVisible = false;

	public boolean isAppConfigureSectionVisible = false;

	public boolean isEmailSectionVisible = false;

	public boolean isSchedulingSectionVisible = false;

	public boolean isAdaptorSettingsSectionVisible = false;

	public MApplication appDefaults;

	public AppConfigurePage( WOContext aWocontext ) {
		super( aWocontext );
	}

	public WOComponent detailPageClicked() {
		return AppDetailPage.create( context(), myApplication() );
	}

	public WOComponent configurePageClicked() {
		ConfigurePage aPage = ConfigurePage.create( context() );
		return aPage;
	}

	public WOComponent defaultsUpdateClicked() {

		handler().whileReading( () -> {
			myApplication().updateValues( appDefaults.values() );
			handler().sendUpdateApplicationToWotaskds( myApplication(), allHosts() );
		});

		AppConfigurePage aPage = AppConfigurePage.create( context(), myApplication() );
		aPage.isNewInstanceSectionVisible = true;
		return aPage;
	}

	/**
	 * Updates the application, without touching the instances (unless it's an application name change)
	 */
	public WOComponent updateAppDefaultsOnly() {
		
		handler().whileReading( () -> {
			myApplication().setStartingPort( appDefaults.startingPort() );
			myApplication().setTimeForStartup( appDefaults.timeForStartup() );
			myApplication().setPhasedStartup( appDefaults.phasedStartup() );
			myApplication().setAdaptor( appDefaults.adaptor() );
			myApplication().setAdaptorThreads( appDefaults.adaptorThreads() );
			myApplication().setListenQueueSize( appDefaults.listenQueueSize() );
			myApplication().setAdaptorThreadsMin( appDefaults.adaptorThreadsMin() );
			myApplication().setAdaptorThreadsMax( appDefaults.adaptorThreadsMax() );
			myApplication().setProjectSearchPath( appDefaults.projectSearchPath() );
			myApplication().setSessionTimeOut( appDefaults.sessionTimeOut() );
			myApplication().setStatisticsPassword( appDefaults.statisticsPassword() );

			boolean pushAppOnly = true;

			if( myApplication().isStopped_M() ) {
				final String defaultsName = appDefaults.name();

				if( !defaultsName.equals( myApplication().name() ) ) {
					final MApplication app = myApplication().siteConfig().applicationWithName( appDefaults.name() );

					if( app == null ) {
						pushAppOnly = false;
						myApplication().setName( defaultsName );

						for( MInstance anInstance : myApplication().instanceArray() ) {
							anInstance._takeNameFromApplication();
						}
					}
				}
			}

			if( pushAppOnly ) {
				handler().sendUpdateApplicationToWotaskds( myApplication(), allHosts() );
			}
			else {
				_defaultsPush();
			}
		});

		AppConfigurePage aPage = AppConfigurePage.create( context(), myApplication() );
		aPage.isAppConfigureSectionVisible = true;
		return aPage;
	}

	private void _defaultsPush() {
		if( !allHosts().isEmpty() ) {
			handler().sendUpdateApplicationAndInstancesToWotaskds( myApplication(), allHosts() );
		}
	}

	private WOComponent _defaultPage() {
		AppConfigurePage aPage = AppConfigurePage.create( context(), myApplication() );
		aPage.isNewInstanceSectionVisible = true;
		return aPage;
	}

	public WOComponent defaultsPushClicked() {

		handler().whileReading( () -> {
			myApplication().updateValues( appDefaults.values() );
			myApplication().pushValuesToInstances();
			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updatePathOnly() {

		handler().whileReading( () -> {
			myApplication().setUnixPath( appDefaults.unixPath() );
			myApplication().setWinPath( appDefaults.winPath() );
			myApplication().setMacPath( appDefaults.macPath() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takePathFromApplication();
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateAutoRecoverOnly() {

		handler().whileReading( () -> {
			myApplication().setAutoRecover( appDefaults.autoRecover() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeAutoRecoverFromApplication();
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateMinimumOnly() {

		handler().whileReading( () -> {
			myApplication().setMinimumActiveSessionsCount( appDefaults.minimumActiveSessionsCount() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeMinimumActiveSessionsCountFromApplication();
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateCachingOnly() {

		handler().whileReading( () -> {
			myApplication().setCachingEnabled( appDefaults.cachingEnabled() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeCachingEnabledFromApplication();
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateDebuggingOnly() {

		handler().whileReading( () -> {
			myApplication().setDebuggingEnabled( appDefaults.debuggingEnabled() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeValueFromApplication( "debuggingEnabled" );
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateOutputOnly() {
		
		handler().whileReading( () -> {
			myApplication().setUnixOutputPath( appDefaults.unixOutputPath() );
			myApplication().setWinOutputPath( appDefaults.winOutputPath() );
			myApplication().setMacOutputPath( appDefaults.macOutputPath() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeOutputPathFromApplication();
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateAutoOpenOnly() {

		handler().whileReading( () -> {
			myApplication().setAutoOpenInBrowser( appDefaults.autoOpenInBrowser() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeValueFromApplication( "autoOpenInBrowser" );
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateLifebeatOnly() {

		handler().whileReading( () -> {
			myApplication().setLifebeatInterval( appDefaults.lifebeatInterval() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeValueFromApplication( "lifebeatInterval" );
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	public WOComponent updateAddArgsOnly() {

		handler().whileReading( () -> {
			myApplication().setAdditionalArgs( appDefaults.additionalArgs() );

			for( MInstance anInstance : myApplication().instanceArray() ) {
				anInstance._takeValueFromApplication( "additionalArgs" );
			}

			_defaultsPush();
		});

		return _defaultPage();
	}

	private WOComponent _pathPickerWizardClicked( String callbackKeyPath, boolean showFiles ) {
		PathWizardPage1 aPage = PathWizardPage1.create( context() );
		aPage.setCallbackKeypath( callbackKeyPath );
		aPage.setCallbackExpand( "isNewInstanceSectionVisible" );
		aPage.setCallbackPage( this );
		aPage.setShowFiles( showFiles );
		return aPage;
	}

	public WOComponent pathPickerWizardClickedUnix() {
		return _pathPickerWizardClicked( "appDefaults.unixPath", true );
	}

	public WOComponent pathPickerWizardClickedWindows() {
		return _pathPickerWizardClicked( "appDefaults.winPath", true );
	}

	public WOComponent pathPickerWizardClickedMac() {
		return _pathPickerWizardClicked( "appDefaults.macPath", true );
	}

	public WOComponent pathPickerWizardClickedUnixOutput() {
		return _pathPickerWizardClicked( "appDefaults.unixOutputPath", false );
	}

	public WOComponent pathPickerWizardClickedWindowsOutput() {
		return _pathPickerWizardClicked( "appDefaults.winOutputPath", false );
	}

	public WOComponent pathPickerWizardClickedMacOutput() {
		return _pathPickerWizardClicked( "appDefaults.macOutputPath", false );
	}

	public boolean isMailingConfigured() {
		final String aHost = siteConfig().SMTPhost();
		final String anAddress = siteConfig().emailReturnAddr();

		if( aHost != null && aHost.length() > 0 && anAddress != null && anAddress.length() > 0 ) {
			return true;
		}

		return false;
	}

	public WOComponent emailUpdateClicked() {
		
		handler().whileReading( () -> {
			handler().sendUpdateApplicationToWotaskds( myApplication(), allHosts() );
		});

		AppConfigurePage aPage = AppConfigurePage.create( context(), myApplication() );
		aPage.isEmailSectionVisible = true;
		return aPage;
	}

	public boolean shouldSchedule() {
		return !myApplication().instanceArray().isEmpty();
	}

	public MInstance currentScheduledInstance;

	public List<String> weekList = MUtil.WEEKDAYS;

	public List<String> timeOfDayList = MUtil.TIMES_OF_DAY;

	public List<String> schedulingTypeList = MUtil.SCHEDULING_TYPES;

	public List<Integer> schedulingIntervalList = MUtil.SCHEDULING_INTERVALS;

	public String weekSelection() {
		return MUtil.morphedSchedulingStartDay( currentScheduledInstance.schedulingStartDay() );
	}

	public void setWeekSelection( String value ) {
		currentScheduledInstance.setSchedulingStartDay( MUtil.morphedSchedulingStartDay( value ) );
	}

	public String timeHourlySelection() {
		return MUtil.morphedSchedulingStartTime( currentScheduledInstance.schedulingHourlyStartTime() );
	}

	public void setTimeHourlySelection( String value ) {
		currentScheduledInstance.setSchedulingHourlyStartTime( MUtil.morphedSchedulingStartTime( value ) );
	}

	public String timeDailySelection() {
		return MUtil.morphedSchedulingStartTime( currentScheduledInstance.schedulingDailyStartTime() );
	}

	public void setTimeDailySelection( String value ) {
		currentScheduledInstance.setSchedulingDailyStartTime( MUtil.morphedSchedulingStartTime( value ) );
	}

	public String timeWeeklySelection() {
		return MUtil.morphedSchedulingStartTime( currentScheduledInstance.schedulingWeeklyStartTime() );
	}

	public void setTimeWeeklySelection( String value ) {
		currentScheduledInstance.setSchedulingWeeklyStartTime( MUtil.morphedSchedulingStartTime( value ) );
	}

	public WOComponent schedulingUpdateClicked() {
		
		handler().whileReading( () -> {
			if( (myApplication().instanceArray().count() != 0) && (allHosts().size() != 0) ) {
				handler().sendUpdateInstancesToWotaskds( myApplication().instanceArray(), allHosts() );
			}
		});

		AppConfigurePage aPage = AppConfigurePage.create( context(), myApplication() );
		aPage.isSchedulingSectionVisible = true;
		return aPage;
	}

	/** ******** Adaptor Settings Section ******** */
	public String _loadSchedulerSelection = null;

	public String loadSchedulerItem;

	public List<String> loadSchedulerList = MUtil.LOAD_SCHEDULERS;

	public Integer urlVersionItem;

	public List<Integer> urlVersionList = MUtil.URL_VERSIONS;

	public String customSchedulerName;

	public String loadSchedulerSelection() {
		if( myApplication().scheduler() != null ) {
			int indexOfScheduler = MUtil.LOAD_SCHEDULER_VALUES.indexOf( myApplication().scheduler() );
			if( indexOfScheduler != -1 ) {
				_loadSchedulerSelection = loadSchedulerList.get( indexOfScheduler );
			}
			else {
				// Custom scheduler
				_loadSchedulerSelection = loadSchedulerList.get( loadSchedulerList.size() - 1 );
				customSchedulerName = myApplication().scheduler();
			}
		}
		return _loadSchedulerSelection;
	}

	public void setLoadSchedulerSelection( String value ) {
		_loadSchedulerSelection = value;
	}

	public Integer urlVersionSelection() {
		return myApplication().urlVersion();
	}

	public void setUrlVersionSelection( Integer value ) {
		myApplication().setUrlVersion( value );
	}

	public WOComponent adaptorUpdateClicked() {

		handler().whileReading( () -> {
			String newValue;
			int i = loadSchedulerList.indexOf( _loadSchedulerSelection );
			if( i == 0 ) {
				newValue = null;
			}
			else if( i == (loadSchedulerList.size() - 1) ) {
				newValue = customSchedulerName;

				if( !StringExtensions.isValidXMLString( newValue ) ) {
					newValue = null;
				}
			}
			else {
				newValue = MUtil.LOAD_SCHEDULER_VALUES.get( i );
			}
			myApplication().setScheduler( newValue );

			handler().sendUpdateApplicationToWotaskds( myApplication(), allHosts() );
		});

		AppConfigurePage aPage = AppConfigurePage.create( context(), myApplication() );
		aPage.isAdaptorSettingsSectionVisible = true;
		return aPage;
	}

	/**
	 * Create an ApplicationConfigurePage instance for the given MApplication
	 * 
	 * @param context the current context
	 * @param application the application object to configure
	 * @return ApplicationConfigurePage
	 */
	public static AppConfigurePage create( WOContext context, MApplication application ) {
		AppConfigurePage page = (AppConfigurePage)context.page().pageWithName( AppConfigurePage.class.getName() );
		page.setMyApplication( application );
		page.appDefaults = new MApplication( application.values(), _sc, null );
		return page;
	}
}