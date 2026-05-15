package sjip.wotaskd;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sjip.core.model.MSiteConfig;
import sjip.core.x.FProperties;

public class AppTaskd {

	private static final Logger logger = LoggerFactory.getLogger( AppTaskd.class );

	private final ReentrantReadWriteLock _lock;
	private final String _multicastAddress;
	private final boolean _shouldWriteAdaptorConfig;
	private final boolean _shouldRespondToMulticast;
	private MSiteConfig _siteConfig;
	private final InstanceController _instanceController;

	public AppTaskd( final String hostName, final int port ) {
		_lock = new ReentrantReadWriteLock();

		// Setting the multicast address
		_multicastAddress = FProperties.K.MULTICAST_ADDRESS.value();

		// getting the siteConfig (+ all Hosts, Apps, Instances) from disk
		_siteConfig = MSiteConfig.unarchiveSiteConfig( true );
		_siteConfig.archiveSiteConfig(); // FIXME: I have no idea why we're re-serializing the config here // Hugi 2026-05-10

		// checking to see if we should save WOConfig.xml to disk for the adaptors.
		_shouldWriteAdaptorConfig = FProperties.K.SAVES_ADAPTOR_CONFIGURATION.value();

		if( _shouldWriteAdaptorConfig ) {
			_siteConfig.archiveAdaptorConfig();
		}

		// checking to see if we should respond to adaptor multicast queries
		// we will always respond to non-multicast UDP packets
		_shouldRespondToMulticast = FProperties.K.RESPONDS_TO_MULTICAST_QUERY.value();

		if( _shouldRespondToMulticast ) {
			logger.info( "Multicast Response Enabled" );
		}
		else {
			logger.info( "Multicast Response Disabled" );
		}

		// Set up multicast listen thread
		// FIXME: Currently a side-effect of constructing an AppTaskd. Should be explicit // Hugi 2026-05-10
		new MulticastListener( shouldRespondToMulticast(), port, multicastAddress(), siteConfig() ).start();
		
		// creating an InstanceController to control and query instances
		_instanceController = new InstanceController( hostName, this );
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

	public InstanceController instanceController() {
		return _instanceController;
	}
}