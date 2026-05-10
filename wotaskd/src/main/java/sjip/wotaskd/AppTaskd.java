package sjip.wotaskd;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sjip.core.model.MSiteConfig;
import sjip.x.FProperties;

public class AppTaskd {

	private static final Logger logger = LoggerFactory.getLogger( AppTaskd.class );

	private final ReentrantReadWriteLock _lock;
	private final String _multicastAddress;
	private final boolean _shouldWriteAdaptorConfig;
	private final boolean _shouldRespondToMulticast;
	private MSiteConfig _siteConfig;

	public AppTaskd( final int port ) {
		_lock = new ReentrantReadWriteLock();

		// Setting the multicast address
		_multicastAddress = FProperties.stringValue( FProperties.K.MULTICAST_ADDRESS );

		// getting the siteConfig (+ all Hosts, Apps, Instances) from disk
		_siteConfig = MSiteConfig.unarchiveSiteConfig( true );
		_siteConfig.archiveSiteConfig(); // FIXME: I have no idea why we're re-serializing the config here // Hugi 2026-05-10

		// checking to see if we should save WOConfig.xml to disk for the adaptors.
		_shouldWriteAdaptorConfig = FProperties.booleanValue( FProperties.K.SAVES_ADAPTOR_CONFIGURATION );

		if( _shouldWriteAdaptorConfig ) {
			_siteConfig.archiveAdaptorConfig();
		}

		// checking to see if we should respond to adaptor multicast queries
		// we will always respond to non-multicast UDP packets
		_shouldRespondToMulticast = FProperties.booleanValue( FProperties.K.RESPONDS_TO_MULTICAST_QUERY );

		if( _shouldRespondToMulticast ) {
			logger.info( "Multicast Response Enabled" );
		}
		else {
			logger.info( "Multicast Response Disabled" );
		}

		// Set up multicast listen thread
		// FIXME: Currently a side-effect of constructing an AppTaskd. Should be explicit // Hugi 2026-05-10
		new MulticastListener( shouldRespondToMulticast(), port, multicastAddress(), siteConfig() ).start();
	}

	public ReentrantReadWriteLock lock() {
		return _lock;
	}
	
	public MSiteConfig siteConfig() {
		return _siteConfig;
	}

	public void setSiteConfig( MSiteConfig aConfig ) {
		_siteConfig = aConfig;
	}

	public String multicastAddress() {
		return _multicastAddress;
	}

	public boolean shouldWriteAdaptorConfig() {
		return _shouldWriteAdaptorConfig;
	}

	public boolean shouldRespondToMulticast() {
		return _shouldRespondToMulticast;
	}
}