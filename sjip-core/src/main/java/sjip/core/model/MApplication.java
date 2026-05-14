/*
© Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */
package sjip.core.model;

import java.util.ArrayList;
import java.util.List;

import sjip.core.MUtil;

public class MApplication extends MObject {

	// ====================================================================
	// Persistence state
	// --------------------------------------------------------------------
	// Fields below are the canonical persisted state of this object — they
	// round-trip through toDto()/updateValues(MApplicationDto) to and from
	// the wire and SiteConfig.xml. Renaming or restructuring any of these
	// changes the on-disk and on-wire shape; the system-tests snapshot
	// suite will catch the drift.
	// ====================================================================

	private String _name;
	private Integer _startingPort;
	private Integer _timeForStartup;
	private Boolean _phasedStartup;
	private Boolean _autoRecover;
	private Integer _minimumActiveSessionsCount;
	private String _unixPath;
	private String _winPath;
	private String _macPath;
	private Boolean _cachingEnabled;
	private String _adaptor;
	private Integer _adaptorThreads;
	private Integer _listenQueueSize;
	private Integer _adaptorThreadsMin;
	private Integer _adaptorThreadsMax;
	private String _projectSearchPath;
	private Integer _sessionTimeOut;
	private String _statisticsPassword;
	private Boolean _debuggingEnabled;
	private String _unixOutputPath;
	private String _winOutputPath;
	private String _macOutputPath;
	private Boolean _autoOpenInBrowser;
	private Integer _lifebeatInterval;
	private String _additionalArgs;
	private Boolean _notificationEmailEnabled;
	private String _notificationEmailAddr;
	private Integer _retries;
	private String _scheduler; // "RANDOM" | "ROUNDROBIN" | "LOADAVERAGE"
	private Integer _dormant;
	private String _redir;
	private Integer _sendTimeout;
	private Integer _recvTimeout;
	private Integer _cnctTimeout;
	private Integer _sendBufSize;
	private Integer _recvBufSize;
	private Integer _poolsize;
	private Integer _urlVersion; // 3 | 4
	private String _oldname;

	// ====================================================================
	// Runtime state (not persisted)
	// --------------------------------------------------------------------
	// Fields below are derived/transient — populated at runtime, not part
	// of the persisted contract. Slated to move out of MApplication entirely
	// in a later cleanup round.
	// ====================================================================

	private final List<MInstance> _instanceArray = new ArrayList<>();
	private final List<MHost> _hostArray = new ArrayList<>();

	// Used for the ApplicationsPage
	private Integer runningInstancesCount = 0;

	// For UI
	public MApplication( String aName, MSiteConfig aConfig ) {
		_siteConfig = aConfig;
		_name = aName;
		takeValuesFromDefaults();
		dataChanged();
	}

	/**
	 * Reconstructs an MApplication from its wire DTO. Used by the unarchive path
	 * ({@code MSiteConfig._initApplicationsWithArray}) and by wotaskd's add-application
	 * receive path.
	 */
	public MApplication( final MApplicationDto dto, final MSiteConfig aConfig ) {
		_siteConfig = aConfig;
		readFromDto( dto );
		dataChanged();
	}

	/**
	 * Populates the application from a DTO without notifying {@link #dataChanged()}.
	 * Used to construct a transient "preview" copy that mustn't trigger a SiteConfig
	 * save just because the user is mid-edit on the AppConfigurePage. The unused
	 * {@code Object o} parameter exists purely as a disambiguator from the unarchive
	 * constructor.
	 */
	public MApplication( final MApplicationDto dto, final MSiteConfig aConfig, final Object o ) {
		_siteConfig = aConfig;
		readFromDto( dto );
	}

	/**
	 * Replaces this application's persisted state from a wire DTO. Called on the
	 * wotaskd receive side during {@code updateWotaskd/configure} (see
	 * {@code DirectAction.monitorRequestAction}).
	 */
	public void updateValues( final MApplicationDto dto ) {
		readFromDto( dto );
		dataChanged();
	}

	/**
	 * Snapshot of this application's persisted state as a typed DTO. The codec
	 * encodes this directly to the wire — no dictionary in between.
	 */
	public MApplicationDto toDto() {
		return new MApplicationDto(
				_name,
				_startingPort,
				_timeForStartup,
				_phasedStartup,
				_autoRecover,
				_minimumActiveSessionsCount,
				_unixPath,
				_winPath,
				_macPath,
				_cachingEnabled,
				_adaptor,
				_adaptorThreads,
				_listenQueueSize,
				_adaptorThreadsMin,
				_adaptorThreadsMax,
				_projectSearchPath,
				_sessionTimeOut,
				_statisticsPassword,
				_debuggingEnabled,
				_unixOutputPath,
				_winOutputPath,
				_macOutputPath,
				_autoOpenInBrowser,
				_lifebeatInterval,
				_additionalArgs,
				_notificationEmailEnabled,
				_notificationEmailAddr,
				_retries,
				_scheduler,
				_dormant,
				_redir,
				_sendTimeout,
				_recvTimeout,
				_cnctTimeout,
				_sendBufSize,
				_recvBufSize,
				_poolsize,
				_urlVersion,
				_oldname );
	}

	/**
	 * Reads every persistence field from the given DTO. Matches the legacy
	 * wholesale-replacement semantics — components null in the DTO produce null
	 * fields on the application. No validators applied at this layer; validation
	 * happens via individual setters when used through the UI path.
	 */
	private void readFromDto( final MApplicationDto dto ) {
		_name = dto.name();
		_startingPort = dto.startingPort();
		_timeForStartup = dto.timeForStartup();
		_phasedStartup = dto.phasedStartup();
		_autoRecover = dto.autoRecover();
		_minimumActiveSessionsCount = dto.minimumActiveSessionsCount();
		_unixPath = dto.unixPath();
		_winPath = dto.winPath();
		_macPath = dto.macPath();
		_cachingEnabled = dto.cachingEnabled();
		_adaptor = dto.adaptor();
		_adaptorThreads = dto.adaptorThreads();
		_listenQueueSize = dto.listenQueueSize();
		_adaptorThreadsMin = dto.adaptorThreadsMin();
		_adaptorThreadsMax = dto.adaptorThreadsMax();
		_projectSearchPath = dto.projectSearchPath();
		_sessionTimeOut = dto.sessionTimeOut();
		_statisticsPassword = dto.statisticsPassword();
		_debuggingEnabled = dto.debuggingEnabled();
		_unixOutputPath = dto.unixOutputPath();
		_winOutputPath = dto.winOutputPath();
		_macOutputPath = dto.macOutputPath();
		_autoOpenInBrowser = dto.autoOpenInBrowser();
		_lifebeatInterval = dto.lifebeatInterval();
		_additionalArgs = dto.additionalArgs();
		_notificationEmailEnabled = dto.notificationEmailEnabled();
		_notificationEmailAddr = dto.notificationEmailAddr();
		_retries = dto.retries();
		_scheduler = dto.scheduler();
		_dormant = dto.dormant();
		_redir = dto.redir();
		_sendTimeout = dto.sendTimeout();
		_recvTimeout = dto.recvTimeout();
		_cnctTimeout = dto.cnctTimeout();
		_sendBufSize = dto.sendBufSize();
		_recvBufSize = dto.recvBufSize();
		_poolsize = dto.poolsize();
		_urlVersion = dto.urlVersion();
		_oldname = dto.oldname();
	}

	public String name() {
		return _name;
	}

	public void setName( String value ) {
		if( !value.equals( name() ) ) {
			setOldname( name() );
			_name = value;
			dataChanged();
		}
	}

	public Integer startingPort() {
		return _startingPort;
	}

	public void setStartingPort( Integer value ) {
		_startingPort = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer timeForStartup() {
		return _timeForStartup;
	}

	public void setTimeForStartup( Integer value ) {
		_timeForStartup = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Boolean phasedStartup() {
		return _phasedStartup;
	}

	public void setPhasedStartup( Boolean value ) {
		_phasedStartup = value;
		dataChanged();
	}

	public Boolean autoRecover() {
		return _autoRecover;
	}

	public void setAutoRecover( Boolean value ) {
		_autoRecover = value;
		dataChanged();
	}

	public Integer minimumActiveSessionsCount() {
		return _minimumActiveSessionsCount;
	}

	public void setMinimumActiveSessionsCount( Integer value ) {
		_minimumActiveSessionsCount = MUtil.validatedInteger( value );
		dataChanged();
	}

	public String unixPath() {
		return _unixPath;
	}

	public void setUnixPath( String value ) {
		_unixPath = value;
		dataChanged();
	}

	public String winPath() {
		return _winPath;
	}

	public void setWinPath( String value ) {
		_winPath = value;
		dataChanged();
	}

	public String macPath() {
		return _macPath;
	}

	public void setMacPath( String value ) {
		_macPath = value;
		dataChanged();
	}

	public Boolean cachingEnabled() {
		return _cachingEnabled;
	}

	public void setCachingEnabled( Boolean value ) {
		_cachingEnabled = value;
		dataChanged();
	}

	public String adaptor() {
		return _adaptor;
	}

	public void setAdaptor( String value ) {
		_adaptor = value;
		dataChanged();
	}

	public Integer adaptorThreads() {
		return _adaptorThreads;
	}

	public void setAdaptorThreads( Integer value ) {
		_adaptorThreads = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer listenQueueSize() {
		return _listenQueueSize;
	}

	public void setListenQueueSize( Integer value ) {
		_listenQueueSize = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer adaptorThreadsMin() {
		return _adaptorThreadsMin;
	}

	public void setAdaptorThreadsMin( Integer value ) {
		_adaptorThreadsMin = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer adaptorThreadsMax() {
		return _adaptorThreadsMax;
	}

	public void setAdaptorThreadsMax( Integer value ) {
		_adaptorThreadsMax = MUtil.validatedInteger( value );
		dataChanged();
	}

	public String projectSearchPath() {
		return _projectSearchPath;
	}

	public void setProjectSearchPath( String value ) {
		_projectSearchPath = value;
		dataChanged();
	}

	public Integer sessionTimeOut() {
		return _sessionTimeOut;
	}

	public void setSessionTimeOut( Integer value ) {
		_sessionTimeOut = MUtil.validatedInteger( value );
		dataChanged();
	}

	public String statisticsPassword() {
		return _statisticsPassword;
	}

	public void setStatisticsPassword( String value ) {
		_statisticsPassword = value;
		dataChanged();
	}

	public Boolean debuggingEnabled() {
		return _debuggingEnabled;
	}

	public void setDebuggingEnabled( Boolean value ) {
		_debuggingEnabled = value;
		dataChanged();
	}

	public String unixOutputPath() {
		return _unixOutputPath;
	}

	public void setUnixOutputPath( String value ) {
		_unixOutputPath = value;
		dataChanged();
	}

	public String winOutputPath() {
		return _winOutputPath;
	}

	public void setWinOutputPath( String value ) {
		_winOutputPath = value;
		dataChanged();
	}

	public String macOutputPath() {
		return _macOutputPath;
	}

	public void setMacOutputPath( String value ) {
		_macOutputPath = value;
		dataChanged();
	}

	public Boolean autoOpenInBrowser() {
		return _autoOpenInBrowser;
	}

	public void setAutoOpenInBrowser( Boolean value ) {
		_autoOpenInBrowser = value;
		dataChanged();
	}

	public Integer lifebeatInterval() {
		return _lifebeatInterval;
	}

	public void setLifebeatInterval( Integer value ) {
		_lifebeatInterval = MUtil.validatedLifebeatInterval( value );
		dataChanged();
	}

	public String additionalArgs() {
		return _additionalArgs;
	}

	public void setAdditionalArgs( String value ) {
		_additionalArgs = value;
		dataChanged();
	}

	public Boolean notificationEmailEnabled() {
		return _notificationEmailEnabled;
	}

	public void setNotificationEmailEnabled( Boolean value ) {
		_notificationEmailEnabled = value;
		dataChanged();
	}

	public String notificationEmailAddr() {
		return _notificationEmailAddr;
	}

	public void setNotificationEmailAddr( String value ) {
		_notificationEmailAddr = value;
		dataChanged();
	}

	public Integer retries() {
		return _retries;
	}

	public void setRetries( Integer value ) {
		_retries = MUtil.validatedInteger( value );
		dataChanged();
	}

	public String scheduler() {
		return _scheduler;
	}

	public void setScheduler( String value ) {
		_scheduler = value;
		dataChanged();
	}

	public Integer dormant() {
		return _dormant;
	}

	public void setDormant( Integer value ) {
		_dormant = MUtil.validatedInteger( value );
		dataChanged();
	}

	public String redir() {
		return _redir;
	}

	public void setRedir( String value ) {
		_redir = value;
		dataChanged();
	}

	public Integer sendTimeout() {
		return _sendTimeout;
	}

	public void setSendTimeout( Integer value ) {
		_sendTimeout = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer recvTimeout() {
		return _recvTimeout;
	}

	public void setRecvTimeout( Integer value ) {
		_recvTimeout = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer cnctTimeout() {
		return _cnctTimeout;
	}

	public void setCnctTimeout( Integer value ) {
		_cnctTimeout = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer sendBufSize() {
		return _sendBufSize;
	}

	public void setSendBufSize( Integer value ) {
		_sendBufSize = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer recvBufSize() {
		return _recvBufSize;
	}

	public void setRecvBufSize( Integer value ) {
		_recvBufSize = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer poolsize() {
		return _poolsize;
	}

	public void setPoolsize( Integer value ) {
		_poolsize = MUtil.validatedInteger( value );
		dataChanged();
	}

	public Integer urlVersion() {
		return _urlVersion;
	}

	public void setUrlVersion( Integer value ) {
		_urlVersion = MUtil.validatedUrlVersion( value );
		dataChanged();
	}

	/**
	 * ??
	 */
	public String oldname() {
		return _oldname;
	}

	/**
	 * ??
	 */
	public void setOldname( String value ) {
		_oldname = value;
		dataChanged();
	}

	/********** Adding and Removing Instance primitives **********/
	public void _addInstancePrimitive( MInstance anInstance ) {

		_instanceArray.add( anInstance );

		if( !_hostArray.contains( anInstance.host() ) ) {
			_hostArray.add( anInstance.host() );
		}
	}

	public void _removeInstancePrimitive( MInstance anInstance ) {

		_instanceArray.remove( anInstance );
		boolean uniqueHost = true;

		for( final MInstance anInst : _instanceArray ) {
			if( anInstance.host() == anInst.host() ) {
				uniqueHost = false;
				break;
			}
		}

		if( uniqueHost ) {
			_hostArray.remove( anInstance.host() );
		}
	}

	public List<MInstance> instanceArray() {
		return _instanceArray;
	}

	public List<MHost> hostArray() {
		return _hostArray;
	}

	/**
	 * Defaults applied by the UI add-application path. Field assignment intentionally
	 * mirrors the legacy dict's contents — same keys, same values — so the on-wire
	 * shape after a UI-initiated add is byte-for-byte identical.
	 */
	private void takeValuesFromDefaults() {
		_startingPort = 2001;
		_timeForStartup = 30;
		_phasedStartup = Boolean.TRUE;
		_autoRecover = Boolean.TRUE;
		_minimumActiveSessionsCount = 0;
		_cachingEnabled = Boolean.TRUE;
		_adaptor = "WODefaultAdaptor";
		_adaptorThreads = 8;
		_listenQueueSize = 128;
		_adaptorThreadsMin = 16;
		_adaptorThreadsMax = 256;
		_projectSearchPath = "()";
		_sessionTimeOut = 3600;
		_statisticsPassword = "";
		_debuggingEnabled = Boolean.FALSE;
		_autoOpenInBrowser = Boolean.FALSE;
		_lifebeatInterval = 30;
		_additionalArgs = "";
		_notificationEmailEnabled = Boolean.FALSE;
		_macOutputPath = "";
		_macPath = "";
	}

	public void pushValuesToInstances() {

		for( final MInstance instance : _instanceArray ) {
			instance.takeValuesFromApplication();
		}
	}

	public Integer nextID() {
		return _instanceArray.stream()
				.mapToInt( MInstance::id )
				.max()
				.orElse( 0 ) + 1;
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

		return runningInstances;
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
		runningInstancesCount = cnt;
	}

	// Used for the AppDetailPage
	private Integer runningInstancesCount_M() {
		runningInstancesCount = runningInstances_M().size();
		return runningInstancesCount;
	}

	public List<MInstance> runningInstances_M() {
		final List<MInstance> instances = new ArrayList<>();

		for( final MInstance instance : _instanceArray ) {
			if( instance.isRunning_M() ) {
				instances.add( instance );
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
