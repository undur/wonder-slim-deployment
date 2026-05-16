/*
(c) Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */
package sjip.core.model;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sjip.core.IInstanceController;
import sjip.core.MUtil;
import sjip.core.SjipException;
import sjip.core.x.AdaptorConfigSerialization;
import sjip.core.x.FApplication;
import sjip.core.x.FHosts;
import sjip.core.x.FProperties;
import sjip.core.x.FoundationCoder;
import sjip.core.x.LegacyPasswordHash;

public class MSiteConfig extends MObject {

	private static final Logger logger = LoggerFactory.getLogger( MSiteConfig.class );

	// ====================================================================
	// Persistence state — site-level scalars
	// --------------------------------------------------------------------
	// Fields below are the canonical persisted site-level state — they
	// round-trip through toSiteDto()/updateValues(MSiteConfigSiteDto) to
	// and from the wire and SiteConfig.xml. The full persisted shape
	// composes these scalars (under the "site" key) with the
	// host/application/instance arrays — see toDto().
	// ====================================================================

	private String _password; // stored as a salted hash, never plain text
	private String _woAdaptor;
	private String _SMTPhost;
	private String _emailReturnAddr;
	private Boolean _viewRefreshEnabled;
	private Integer _viewRefreshRate;
	private Integer _retries;
	private String _scheduler; // "RANDOM" | "ROUNDROBIN" | "LOADAVERAGE" | <Custom Scheduler Name>
	private Integer _dormant;
	private String _redir;
	private Integer _sendTimeout;
	private Integer _recvTimeout;
	private Integer _cnctTimeout;
	private Integer _sendBufSize;
	private Integer _recvBufSize;
	private Integer _poolsize;
	private Integer _urlVersion; // 3 | 4

	// ====================================================================
	// Persistence state — composed children
	// --------------------------------------------------------------------
	// The arrays below hold the child M-objects (hosts, applications,
	// instances). The arrays themselves aren't serialized as scalar fields;
	// toDto() walks them and composes each child's own toDto() into the
	// hostArray/applicationArray/instanceArray components of the persisted
	// shape. Without these, the on-disk SiteConfig.xml would lose every
	// host/app/instance.
	// ====================================================================

	private final List<MHost> _hostArray = new ArrayList<>();
	private final List<MInstance> _instanceArray = new ArrayList<>();
	private final List<MApplication> _applicationArray = new ArrayList<>();

	// ====================================================================
	// Runtime state (not persisted)
	// --------------------------------------------------------------------
	// Fields below are derived/transient — populated at runtime, not part
	// of the persisted contract. Slated to move out of MSiteConfig entirely
	// in a later cleanup round.
	// ====================================================================

	public final Map<String, String> globalErrorDictionary = Collections.synchronizedMap( new HashMap<>() );
	public final Set<MHost> hostErrorArray = Collections.synchronizedSet( new HashSet<>() );

	private MHost _localHost;

	private boolean _hasChanges = true;

	// FIXME: Oh my god. localHost{Address,Name} are read from FProperties' deprecated
	// static fields as a temporary seam — tests can assign them without booting a
	// WOApplication. The "right" shape is to receive these through the constructor
	// (or move them out of MSiteConfig entirely). Goes away with the MSiteConfig
	// clusterfudge cleanup. // Hugi 2026-05-12
	private final InetAddress _localHostAddress;
	private final String _localHostName;

	private String _oldPassword = null;
	private boolean _oldPasswordSet = false;

	private String _lastConfig;

	public int _appIsDeadMultiplier;

	/**
	 * Constructs an MSiteConfig from a wire/disk DTO. {@code dto == null} means
	 * empty config; the site-level defaults ({@code viewRefreshEnabled=TRUE,
	 * viewRefreshRate=60}) are applied.
	 */
	public MSiteConfig( final MSiteConfigDto dto ) {
		_localHostAddress = FProperties.siteConfigLocalHostAddress;
		_localHostName = FProperties.siteConfigLocalHostName;

		_siteConfig = this;
		if( dto == null ) {
			setViewRefreshEnabled( Boolean.TRUE );
			setViewRefreshRate( 60 );
		}
		else {
			if( dto.site() == null ) {
				// rdar://3935864 - Seed: "Null Pointer Exception" for WO Application Instances
				// It seems this should not be necessary, but there is no other place for default values to get fed in. -rrk
				_viewRefreshEnabled = Boolean.TRUE;
				_viewRefreshRate = 60;
			}
			else {
				// NB: pre-refactor, the site dict was stored raw — no validators were
				// applied at dict-read time (validation only happens via individual
				// setters). Preserving that exactly so snapshots don't drift.
				readSiteFromDto( dto.site() );
			}

			_initHostsFromDtos( dto.hostArray() );
			_initApplicationsFromDtos( dto.applicationArray() );
			_initInstancesFromDtos( dto.instanceArray() );
		}

		// setting the multiplier for assuming an application is dead
		_appIsDeadMultiplier = 2 * 1000;
		final String WOAssumeAppIsDeadMultiplier = FProperties.K.ASSUME_APPLICATION_IS_DEAD_MULTIPLIER.value();
		if( WOAssumeAppIsDeadMultiplier != null ) {
			try {
				final Integer tempInt = Integer.valueOf( WOAssumeAppIsDeadMultiplier );
				_appIsDeadMultiplier = tempInt.intValue() * 1000;
			}
			catch( final NumberFormatException e ) {
				logger.debug( "WOAssumeApplicationIsDeadMultiplier is not a valid integer: '{}'. Using default.", WOAssumeAppIsDeadMultiplier );
			}
		}
		_lastConfig = generateSiteConfigXML();
	}

	/**
	 * @return A cloned dict of the site-level scalars (no nested host/application/instance arrays),
	 *         suitable for the {@code site} payload in a wotaskd configure request.
	 *
	 *         <p>Distinct from {@link #toDto()}, which returns the full archive shape
	 *         (site scalars composed with the host/application/instance lists).
	 */
	public MSiteConfigSiteDto toSiteDto() {
		return new MSiteConfigSiteDto(
				_password,
				_woAdaptor,
				_SMTPhost,
				_emailReturnAddr,
				_viewRefreshEnabled,
				_viewRefreshRate,
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
				_urlVersion );
	}

	/**
	 * Replaces this site config's site-level scalars from a wire DTO. Called on the
	 * wotaskd receive side during {@code updateWotaskd/configure} with a site sub-dict
	 * (see {@code DirectAction.monitorRequestAction}).
	 */
	public void updateValues( final MSiteConfigSiteDto dto ) {
		readSiteFromDto( dto );
		dataChanged();
	}

	/**
	 * Reads every site-level persistence field from the given DTO. Matches the legacy
	 * wholesale-replacement semantics — components null in the DTO produce null fields.
	 * No validators applied at this layer; validation happens via individual setters
	 * when used through the UI path.
	 */
	private void readSiteFromDto( final MSiteConfigSiteDto dto ) {
		_password = dto.password();
		_woAdaptor = dto.woAdaptor();
		_SMTPhost = dto.SMTPhost();
		_emailReturnAddr = dto.emailReturnAddr();
		_viewRefreshEnabled = dto.viewRefreshEnabled();
		_viewRefreshRate = dto.viewRefreshRate();
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
	}

	/********** 'values' accessors **********/
	public String password() {
		return _password;
	}

	// Special treatment - the password is stored encrypted!
	public void setPassword( String value ) {
		if( value != null ) {
			_password = LegacyPasswordHash.encryptStringWithKey( value, null );
		}
		else {
			_password = null;
		}
		dataChanged();
	}

	public String woAdaptor() {
		return _woAdaptor;
	}

	public void setWoAdaptor( String value ) {
		_woAdaptor = value;
		dataChanged();
	}

	public String SMTPhost() {
		return _SMTPhost;
	}

	public void setSMTPhost( String value ) {
		_SMTPhost = value;
		dataChanged();
	}

	public String emailReturnAddr() {
		return _emailReturnAddr;
	}

	public void setEmailReturnAddr( String value ) {
		_emailReturnAddr = value;
		dataChanged();
	}

	public Boolean viewRefreshEnabled() {
		return _viewRefreshEnabled;
	}

	public void setViewRefreshEnabled( Boolean value ) {
		_viewRefreshEnabled = value;
		dataChanged();
	}

	public Integer viewRefreshRate() {
		return _viewRefreshRate;
	}

	public void setViewRefreshRate( Integer value ) {
		_viewRefreshRate = MUtil.validatedInteger( value );
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

	public List<MHost> hostArray() {
		return _hostArray;
	}

	public List<MInstance> instanceArray() {
		return _instanceArray;
	}

	public List<MApplication> applicationArray() {
		return _applicationArray;
	}

	public MHost localHost() {
		return _localHost;
	}

	public boolean hasChanges() {
		return _hasChanges;
	}

	public void resetChanges() {
		_hasChanges = false;
	}

	public void dataHasChanged() {
		_hasChanges = true;
	}

	/********** Adding and Deleting **********/
	private void _addHost( MHost newHost ) {
		// If WOHost was passed, it'll resolve against that, otherwise, it'll resolve any local address
		if( FHosts.isConfiguredHostAddress( newHost.address(), true ) ) {
			_localHost = newHost;
		}
		_hostArray.add( newHost );
		dataHasChanged();
	}

	public void addHost_M( MHost newHost ) {
		backup( "addHost-" + newHost.name() );
		_addHost( newHost );
	}

	public void addHost_W( MHost newHost ) {
		_addHost( newHost );
	}

	private void _removeHost( MHost aHost ) {
		_hostArray.remove( aHost );
		if( aHost == _localHost ) {
			_localHost = null;
		}
		dataHasChanged();
	}

	public void removeHost_M( MHost aHost ) {

		backup( "removeHost-" + aHost.name() );

		// Iterate a defensive copy — removeInstance_M mutates aHost.instanceArray().
		for( final MInstance instance : new ArrayList<>( aHost.instanceArray() ) ) {
			removeInstance_M( instance, false );
		}

		_removeHost( aHost );
	}

	public void removeHost_W( MHost aHost ) {
		for( final MInstance instance : new ArrayList<>( aHost.instanceArray() ) ) {
			removeInstance_W( instance );
		}

		_removeHost( aHost );
	}

	private void _addApplication( MApplication newApplication ) {
		_applicationArray.add( newApplication );
		dataHasChanged();
	}

	public void addApplication_M( MApplication newApplication ) {
		backup( "addApplication-" + newApplication.name() );
		_addApplication( newApplication );
	}

	public void addApplication_W( MApplication newApplication ) {
		_addApplication( newApplication );
	}

	private void _removeApplication( MApplication anApplication ) {
		_applicationArray.remove( anApplication );
		dataHasChanged();
	}

	public void removeApplication_M( MApplication anApplication ) {

		backup( "removeApplication-" + anApplication.name() );

		// Defensive copy — removeInstance_M mutates anApplication.instanceArray().
		for( final MInstance instance : new ArrayList<>( anApplication.instanceArray() ) ) {
			removeInstance_M( instance, false );
		}

		_removeApplication( anApplication );
	}

	public void removeApplication_W( MApplication anApplication ) {

		for( final MInstance instance : new ArrayList<>( anApplication.instanceArray() ) ) {
			removeInstance_W( instance );
		}

		_removeApplication( anApplication );
	}

	private void _addInstance( MInstance newInstance ) {
		_instanceArray.add( newInstance );
		newInstance.host()._addInstancePrimitive( newInstance );
		newInstance.application()._addInstancePrimitive( newInstance );
		dataHasChanged();
	}

	public List<MInstance> addInstances_M( MHost selectedHost, MApplication anApplication, int numberToAdd ) {

		backup( "addInstances-" + anApplication.name() + "-" + selectedHost.name() + "-" + numberToAdd );

		final List<MInstance> newInstanceArray = new ArrayList<>( numberToAdd );

		for( int i = 0; i < numberToAdd; i++ ) {
			final Integer aUniqueID = anApplication.nextID();
			final MInstance newInstance = new MInstance( selectedHost, anApplication, aUniqueID, this );
			addInstance_M( newInstance );
			newInstanceArray.add( newInstance );
		}

		return newInstanceArray;
	}

	private void addInstance_M( MInstance newInstance ) {
		_addInstance( newInstance );
	}

	public void addInstance_W( MInstance newInstance ) {
		_addInstance( newInstance );
	}

	private void _removeInstance( MInstance anInstance ) {
		//cancel all tasks
		anInstance.cancelForceQuitTask();
		anInstance.host()._removeInstancePrimitive( anInstance );
		anInstance.application()._removeInstancePrimitive( anInstance );
		_instanceArray.remove( anInstance );
		dataHasChanged();
	}

	public void removeInstance_M( MInstance anInstance ) {
		removeInstance_M( anInstance, true );
	}

	public void removeInstances_M( MApplication application, List<MInstance> instances ) {

		backup( "removeInstances-" + application + "-" + instances.size() );

		for( final MInstance instance : instances ) {
			removeInstance_M( instance, false );
		}
	}

	private void removeInstance_M( MInstance anInstance, boolean doBackup ) {

		if( doBackup ) {
			backup( "removeInstance-" + anInstance.displayName() );
		}

		_removeInstance( anInstance );
	}

	public void removeInstance_W( MInstance anInstance ) {

		if( (anInstance.host() == _localHost) && anInstance.isRunning_W() ) {
			final IInstanceController instanceController = FApplication.instanceController();

			try {
				instanceController.stopInstance( anInstance );
			}
			catch( final SjipException me ) {
				logger.error( "Can't remove", me );
			}
		}

		_removeInstance( anInstance );
	}

	public boolean isPasswordRequired() {
		return password() != null;
	}

	public void _setOldPassword() {
		_oldPassword = password();
		_oldPasswordSet = true;
	}

	public void _resetOldPassword() {
		_oldPassword = null;
		_oldPasswordSet = false;
	}

	public boolean checkPasswordPlaintext( String plainTextPasswordParam ) {
		final String stored = password();

		// Strict null handling: both null = match, exactly one null = no match.
		// (Distinct from checkPasswordEncrypted's "stored null = allow all" semantics.)
		if( plainTextPasswordParam == null && stored == null ) {
			return true;
		}
		if( plainTextPasswordParam == null || stored == null ) {
			return false;
		}

		// Hash the plaintext using the salt prefix from the stored hash, then compare hashes.
		final String salt = stored.substring( 0, 4 );
		final String candidate = LegacyPasswordHash.encryptStringWithKey( plainTextPasswordParam, salt );
		return checkPasswordEncrypted( candidate );
	}

	// The argument is always the tested. _encryptedPassword is the "correct" one.
	public boolean checkPasswordEncrypted( String encryptedPasswordParam ) {
		final String _encryptedPassword = password();

		if( (_encryptedPassword == null) || (_encryptedPassword.length() == 0) ) {
			// if _encryptedPassword is null or blank, match
			return true;
		}
		else if( (encryptedPasswordParam == null) || (encryptedPasswordParam.length() == 0) ) {
			// if aString is null or blank, no match (since by this time, _encryptedPassword is non-null and not blank)
			return false;
		}
		else if( encryptedPasswordParam.equals( _encryptedPassword ) ) {
			return true;
		}
		return false;
	}

	/**
	 * @return The password value to send in the {@code password:} HTTP header, or {@code null}
	 *         if no password header should be sent. During the password-change UI flow, the
	 *         pre-change password is returned so the user can be re-authenticated against the
	 *         old hash before the new one takes effect.
	 */
	public String passwordForRequest() {
		if( _oldPasswordSet ) {
			return _oldPassword;
		}
		return password();
	}

	private void _initHostsFromDtos( final List<MHostDto> list ) {
		if( list == null ) {
			return;
		}
		for( final MHostDto dto : list ) {
			_addHost( new MHost( dto, this ) );
		}
	}

	private void _initApplicationsFromDtos( final List<MApplicationDto> list ) {
		if( list == null ) {
			return;
		}
		for( final MApplicationDto dto : list ) {
			_addApplication( new MApplication( dto, this ) );
		}
	}

	private void _initInstancesFromDtos( final List<MInstanceDto> list ) {
		if( list == null ) {
			return;
		}
		for( final MInstanceDto dto : list ) {
			_addInstance( new MInstance( dto, this ) );
		}
	}

	/**********/

	/********** File System Stuff **********/
	private static String _configDirectoryPath = null;
	private static String _pathForSiteConfig = null;
	private static String _pathForAdaptorConfig = null;
	private static File _fileForSiteConfig = null;
	private static File _fileForAdaptorConfig = null;

	public static String configDirectoryPath() {

		if( _configDirectoryPath == null ) {
			_configDirectoryPath = FProperties.K.DEPLOYMENT_CONFIGURATION_DIRECTORY.value();

			if( _configDirectoryPath == null || _configDirectoryPath.isEmpty() ) {
				logger.error( "WODeploymentConfigurationDirectory not set" );
				System.exit( 1 );
			}

			logger.info( "WODeploymentConfigurationDirectory is: " + _configDirectoryPath );

			if( !_configDirectoryPath.endsWith( File.separator ) ) {
				_configDirectoryPath = _configDirectoryPath + File.separator;
			}

			final File configDir = new File( _configDirectoryPath );

			if( !configDir.exists() ) {
				if( !configDir.mkdirs() ) {
					logger.error( "Configuration Directory {} does not exist, and cannot be created.", _configDirectoryPath );
					System.exit( 1 );
				}
			}
			else {
				if( !configDir.isDirectory() ) {
					logger.error( "Configuration Directory {} is not actually a directory.", _configDirectoryPath );
					System.exit( 1 );
				}
				if( !configDir.canRead() ) {
					logger.error( "Don't have permission to read from Configuration Directory {} as this user, please change the permissions or restart {} as another user.", _configDirectoryPath, FApplication.role() );
					System.exit( 1 );
				}
				if( FApplication.isWotaskd() && !configDir.canWrite() ) {
					logger.error( "Don't have permission to write to Configuration Directory {} as this user; please change the permissions.", _configDirectoryPath );
					System.exit( 1 );
				}
			}
		}

		return _configDirectoryPath;
	}

	private static String pathForSiteConfig() {
		if( _pathForSiteConfig == null ) {
			_pathForSiteConfig = MSiteConfig.configDirectoryPath().concat( "SiteConfig.xml" );
		}
		return _pathForSiteConfig;
	}

	private static String pathForAdaptorConfig() {
		if( _pathForAdaptorConfig == null ) {
			_pathForAdaptorConfig = MSiteConfig.configDirectoryPath().concat( "WOConfig.xml" );
		}
		return _pathForAdaptorConfig;
	}

	private static File fileForSiteConfig() {
		if( _fileForSiteConfig == null ) {
			_fileForSiteConfig = new File( pathForSiteConfig() );
		}
		return _fileForSiteConfig;
	}

	private static File fileForAdaptorConfig() {
		if( _fileForAdaptorConfig == null ) {
			_fileForAdaptorConfig = new File( pathForAdaptorConfig() );
		}
		return _fileForAdaptorConfig;
	}

	public static MSiteConfig unarchiveSiteConfig( boolean isWotaskd ) {
		MSiteConfig aConfig = null;

		// The file may not exist, but we can create it.
		// It is awkward to do the file creation here, in this way, but this stuff needs to be factored properly.
		// If creation fails (typically a permission problem on the parent directory) we
		// rethrow as RuntimeException — same effective behaviour as before, with a
		// proper cause chain.
		if( !fileForSiteConfig().exists() ) {
			final String emptySiteConfig = new FoundationCoder().encodeRootObjectForKey( Map.of(), "SiteConfig" );
			try {
				Files.writeString( fileForSiteConfig().toPath(), emptySiteConfig, StandardCharsets.UTF_8 );
			}
			catch( final IOException e ) {
				throw new RuntimeException( "Failed to create empty SiteConfig at " + fileForSiteConfig(), e );
			}
		}

		// Now, the file should exist, or we have an error.
		if( fileForSiteConfig().exists() ) {
			if( fileForSiteConfig().canRead() ) {
				try {
					final FoundationCoder coder = new FoundationCoder();
					@SuppressWarnings("unchecked")
					final Map<String, Object> siteDict = (Map<String, Object>)coder.decodeRootObject( pathForSiteConfig() );
					final MSiteConfigDto dto = coder.decodeRecord( siteDict, MSiteConfigDto.class );

					aConfig = new MSiteConfig( dto );

					logger.debug( "the SiteConfig is \n" + aConfig.generateSiteConfigXML() );
				}
				catch( final Throwable ex ) {
					if( isWotaskd ) {
						logger.error( "Failed to parse {}. Backing up original SiteConfig and continuing as if empty.", pathForSiteConfig() );
						backupSiteConfig();
					}
					else {
						logger.error( "Failed to parse {}. Continuing as if empty.", pathForSiteConfig() );
					}
				}
			}
			else {
				logger.error( "Cannot read from SiteConfig file {}. Possible Permissions Problem.", pathForSiteConfig() );
				System.exit( 1 );
			}
		}
		else {
			logger.error( "SiteConfig file {} doesn't exist. Continuing as if empty.", pathForSiteConfig() );
		}

		if( aConfig == null ) {
			aConfig = new MSiteConfig( null );
		}

		return aConfig;
	}

	private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern( "yyyyMMddHHmmssSSS" );

	private static void backupSiteConfig() {
		final File sc = fileForSiteConfig();
		if( sc.exists() ) {
			final File renamedFile = new File( pathForSiteConfig() + "." + LocalDateTime.now().format( BACKUP_TIMESTAMP_FORMAT ) );
			if( !sc.renameTo( renamedFile ) ) {
				logger.error( "Cannot backup file {}. Possible Permissions Problem.", pathForSiteConfig() );
			}
		}
	}

	public void archiveSiteConfig() {
		saveSiteConfig( fileForSiteConfig(), generateSiteConfigXML(), false );
	}

	private void saveSiteConfig( File siteConfigFile, String serialisedSiteConfig, boolean compress ) {
		try {
			if( siteConfigFile.exists() && !siteConfigFile.canWrite() ) {
				logger.error( "Don't have permission to write to file {} as this user, please change the permissions.", siteConfigFile.getAbsolutePath() );
				final String pre = FApplication.role() + " - " + _localHostName;
				globalErrorDictionary.put( "archiveSiteConfig", pre + " Don't have permission to write to file " + siteConfigFile.getAbsolutePath() + " as this user, please change the permissions." );
				return;
			}

			if( compress ) {
				siteConfigFile = new File( siteConfigFile.getParentFile(), siteConfigFile.getName() + ".gz" );
				stringToGZippedFile( serialisedSiteConfig, siteConfigFile );
			}
			else {
				Files.writeString( siteConfigFile.toPath(), serialisedSiteConfig, StandardCharsets.UTF_8 );
			}

			globalErrorDictionary.remove( "archiveSiteConfig" );
		}
		catch( final IOException e ) {
			final String message = "Cannot write to file " + siteConfigFile.getAbsolutePath() + ". IOException: " + e.getLocalizedMessage();
			logger.error( message );
			final String pre = FApplication.role() + " - " + _localHostName;
			globalErrorDictionary.put( "archiveSiteConfig", pre + message );
		}
	}

	private static void stringToGZippedFile( final String string, final File file ) throws IOException {
		Objects.requireNonNull( string );
		Objects.requireNonNull( file );

		final byte[] bytes = string.getBytes( StandardCharsets.UTF_8 );
		final ByteArrayInputStream stream = new ByteArrayInputStream( bytes );

		try( final GZIPOutputStream out = new GZIPOutputStream( new FileOutputStream( file ) )) {
			stream.transferTo( out );
		}
	}

	public void archiveAdaptorConfig() {
		try {
			final File ac = fileForAdaptorConfig();
			if( ac.exists() && !ac.canWrite() ) {
				logger.error( "Don't have permission to write to file {} as this user, please change the permissions.", fileForAdaptorConfig() );
				final String pre = FApplication.role() + " - " + _localHostName;
				globalErrorDictionary.put( "archiveSiteConfig", pre + " Don't have permission to write to file " + fileForAdaptorConfig() + "as this user, please change the permissions." );
				return;
			}
			Files.writeString( fileForAdaptorConfig().toPath(), AdaptorConfigSerialization.generateAdaptorConfigXML( this, false, false ), StandardCharsets.UTF_8 );
			globalErrorDictionary.remove( "archiveAdaptorConfig" );
		}
		catch( final IOException e ) {
			final String message = "Cannot write to file " + pathForAdaptorConfig() + ". IOException: " + e.getLocalizedMessage();
			logger.error( message );
			final String pre = FApplication.role() + " - " + _localHostName;
			globalErrorDictionary.put( "archiveAdaptorConfig", pre + " " + message );
		}
	}

	public String generateSiteConfigXML() {
		return new FoundationCoder().encodeRootObjectForKey( toDto(), "SiteConfig" );
	}

	private void backup( String action ) {
		if( Boolean.getBoolean( "WODeploymentBackups" ) ) {
			final String currentSiteConfig = generateSiteConfigXML();
			if( !_lastConfig.equals( generateSiteConfigXML() ) ) {
				final String date = new SimpleDateFormat( "yyyy-MM-dd-hh_mm_ss" ).format( new Date() );
				saveSiteConfig( new File( fileForSiteConfig().getParentFile(), "SiteConfigBackup.xml." + date + "." + action ), _lastConfig, true );
				_lastConfig = currentSiteConfig;
			}
		}
	}

	public void forceBackup( String reason ) {
		reason = reason != null ? "." + reason : "";
		final String date = new SimpleDateFormat( "yyyy-MM-dd-hh_mm_ss" ).format( new Date() );
		saveSiteConfig( new File( fileForSiteConfig().getParentFile(), "SiteConfigBackup.xml." + date + reason ), generateSiteConfigXML(), true );
	}

	/**
	 * Snapshot of the full persisted state as a typed DTO. The codec encodes this
	 * directly to the wire — no dictionary in between. Composes the site-level
	 * scalars (via {@link #toSiteDto()}) with the host/application/instance lists
	 * (via each child's own {@code toDto()}).
	 */
	public MSiteConfigDto toDto() {
		final int hostArrayCount = _hostArray.size();
		final int applicationArrayCount = _applicationArray.size();
		final int instanceArrayCount = _instanceArray.size();

		final List<MHostDto> hostArray = new ArrayList<>( hostArrayCount );
		for( int i = 0; i < hostArrayCount; i++ ) {
			hostArray.add( _hostArray.get( i ).toDto() );
		}

		final List<MApplicationDto> applicationArray = new ArrayList<>( applicationArrayCount );
		for( int i = 0; i < applicationArrayCount; i++ ) {
			applicationArray.add( _applicationArray.get( i ).toDto() );
		}

		final List<MInstanceDto> instanceArray = new ArrayList<>( instanceArrayCount );
		for( int i = 0; i < instanceArrayCount; i++ ) {
			instanceArray.add( _instanceArray.get( i ).toDto() );
		}

		return new MSiteConfigDto( toSiteDto(), hostArray, applicationArray, instanceArray );
	}

	@Override
	public String toString() {
		return toSiteDto().toString() + "\n" + "hasChanges = " + _hasChanges + "\n" + "configDirectoryPath = " + _configDirectoryPath;
	}

	// KH - all these should be cached!
	public long autoRecoverInterval() {
		int smallestInterval = 0;
		for( final MInstance anInst : _instanceArray ) {
			final Integer Interval = anInst.lifebeatInterval();
			if( Interval != null ) {
				final int interval = Interval.intValue();
				if( interval < smallestInterval ) {
					smallestInterval = interval;
				}
			}
		}
		if( smallestInterval < 1 ) {
			return 30 * 1000;
		}
		return smallestInterval * 1000;
	}

	public MApplication applicationWithName( String anAppName ) {

		if( anAppName == null ) {
			return null;
		}

		for( final MApplication anApp : _applicationArray ) {
			if( anApp.name().equals( anAppName ) ) {
				return anApp;
			}
		}

		return null;
	}

	public MHost hostWithName( String aHostName ) {

		if( aHostName == null ) {
			return null;
		}

		if( aHostName.equals( "localhost" ) ) {
			return localHost();
		}

		for( final MHost aHost : _hostArray ) {
			if( aHost.name().equals( aHostName ) ) {
				return aHost;
			}
		}

		return null;
	}

	public boolean localhostOrLoopbackHostExists() {

		final String localhost = "localhost";
		final String loopback = "127.0.0.1";

		for( final MHost aHost : _hostArray ) {
			if( aHost.name().equals( localhost ) || aHost.name().equals( loopback ) ) {
				return true;
			}
		}

		return false;
	}

	public MHost hostWithAddress( InetAddress anAddress ) {

		if( anAddress == null ) {
			return null;
		}

		if( _localHost != null && anAddress.equals( _localHostAddress ) ) {
			return _localHost;
		}

		for( final MHost aHost : _hostArray ) {
			if( anAddress.equals( aHost.address() ) ) {
				return aHost;
			}
		}

		return null;
	}

	public MInstance instanceWithName( String anInstanceName ) {

		if( anInstanceName == null ) {
			return null;
		}

		for( final MInstance anInstance : _instanceArray ) {
			if( anInstance.displayName().equals( anInstanceName ) ) {
				return anInstance;
			}
		}

		return null;
	}

	public MInstance instanceWithHostnameAndPort( String hostName, Integer port ) {

		final MHost aHost = hostWithName( hostName );

		if( aHost == null ) {
			return null;
		}

		return aHost.instanceWithPort( port );
	}

	public MInstance instanceWithHostAndPort( String name, InetAddress host, String port ) {

		try {
			final Integer anIntPort = Integer.valueOf( port );
			final MHost aHost = hostWithAddress( host );

			if( aHost == null ) {
				return null;
			}

			final MInstance anInstance = aHost.instanceWithPort( anIntPort );

			if( anInstance != null ) {
				if( anInstance.applicationName().equals( name ) ) {
					return anInstance;
				}
			}
		}
		catch( final Exception e ) {
			logger.error( "Exception getting instance: {}:{}", host, port, e );
		}

		return null;
	}
}
