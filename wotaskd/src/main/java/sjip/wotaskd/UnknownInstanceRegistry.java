package sjip.wotaskd;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import sjip.core.IInstanceController;
import sjip.core.x.FHosts;

/**
 * Registry of running app instances that wotaskd <em>didn't</em> start — apps that
 * showed up via lifebeat (heartbeat) without a corresponding entry in the SiteConfig.
 *
 * <h2>How an instance ends up here</h2>
 * <p>The lifebeat handler accepts pings from any instance running on the local
 * machine, even ones wotaskd has no record of, and stuffs them in here. That happens
 * in two scenarios:
 * <ul>
 *   <li>An admin started a {@code .woa} from the shell directly instead of through
 *       JavaMonitor.</li>
 *   <li>A leftover JVM from a previous SiteConfig is still alive and pinging after
 *       its instance entry was removed from the config.</li>
 * </ul>
 *
 * <h2>What we do with them</h2>
 * <p><b>Adaptor routing.</b> {@link #snapshot()} feeds these to the adaptor-config serializer as
 * {@code <application>}/{@code <instance>} entries (with a negative {@code id}
 * sentinel) so the {@code mod_WebObjects} adaptor can route incoming HTTP
 * requests to manually-started apps too.
 *
 * <h2>Triage</h2>
 * <p>{@link #triage()} runs periodically and drops entries whose last lifebeat is
 * older than 45 seconds — twice the default 30-second lifebeat interval, so two
 * missed pings count as dead. Keeps the registry from growing without bound.
 *
 * <h2>Shape</h2>
 * <p>Two-level nested map:
 * {@snippet :
 * appName (String) -> { port (String) -> lastLifebeat (Instant) }
 * }
 * <p>The outer key is the application name (e.g. {@code "Hugi"}), the inner key
 * is the port the instance is listening on (e.g. {@code "2001"}, kept as a String
 * since nothing parses it as an int), and the inner value is the timestamp of
 * the last lifebeat received from that instance. The two-level shape lines up
 * with the grouped-by-app structure the adaptor config serializer emits.
 *
 * <p>FIXME: replace the inner map's value with an {@code UnknownApplication} record
 * once we get there; the current shape predates records and uses Strings/Instant
 * directly. // Hugi 2026-04-30
 *
 * <h2>Is it pulling its weight?</h2>
 * <p>If your deployment is JavaMonitor-only and nobody starts apps from the shell,
 * this whole subsystem is dead weight. It only matters when something runs a WO app
 * outside wotaskd's control. Lifted into its own class (was inline on
 * {@code InstanceController}) so the decision "do we keep this?" is one file's worth
 * of code instead of scattered bookkeeping.
 */
final class UnknownInstanceRegistry {

	private final String _hostName;
	private final Map<String, Map<String, Instant>> _unknownApplications = new LinkedHashMap<>();
	private final ReentrantReadWriteLock _lock = new ReentrantReadWriteLock();

	UnknownInstanceRegistry( final String hostName ) {
		_hostName = hostName;
	}

	/**
	 * Records or refreshes a lifebeat from an instance that wotaskd doesn't have a
	 * SiteConfig entry for. Called from {@code LifebeatRequestHandler} for any "lifebeat"
	 * notification whose instance name doesn't match a registered {@code MInstance}.
	 *
	 * <p>Only applies to instances on the local machine — a remote address silently no-ops,
	 * since wotaskd has no business tracking foreign hosts' processes.
	 *
	 * <p>Failures (DNS lookup of {@code host}, locking, anything) are swallowed. Unknown
	 * instances are best-effort bookkeeping; losing one is harmless.
	 */
	void register( String name, String host, String port ) {
		_lock.writeLock().lock();

		try {
			Instant currentTime = Instant.now();
			// Don't regenerate the localhost list for random applications
			if( FHosts.isConfiguredHostAddress( InetAddress.getByName( host ), false ) ) {
				Map<String, Instant> appDict = _unknownApplications.get( name );
				if( appDict != null ) {
					appDict.put( port, currentTime );
				}
				else {
					final Map<String, Instant> created = new LinkedHashMap<>();
					created.put( port, currentTime );
					_unknownApplications.put( name, created );
				}
			}
		}
		catch( Exception e ) {
			// Just ignore it - unregistered instances are second class citizens anyway
		}
		finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * Drops entries whose last lifebeat is older than 45 seconds — twice the default
	 * 30-second lifebeat interval, so two missed pings count as dead. Called from the
	 * auto-recover periodic timer; keeps the registry from growing without bound when
	 * manually-started apps come and go.
	 *
	 * <p>The 45-second cutoff is hardcoded; making it configurable hasn't been worth the
	 * effort given how marginal this whole subsystem is.
	 */
	void triage() {
		_lock.writeLock().lock();

		try {
			// Should make this configurable?
			final Instant cutOffDate = Instant.now().minusSeconds( 45 );

			final List<String> unknownAppKeys = new ArrayList<>( _unknownApplications.keySet() );

			for( String unknownAppKey : unknownAppKeys ) {
				Map<String, Instant> appDict = _unknownApplications.get( unknownAppKey );

				if( appDict != null ) {
					final List<String> appDictKeys = new ArrayList<>( appDict.keySet() );

					for( String appDictKey : appDictKeys ) {
						Instant lastLifebeat = appDict.get( appDictKey );
						if( (lastLifebeat != null) && (lastLifebeat.isBefore( cutOffDate )) ) {
							appDict.remove( appDictKey );
						}
					}
					if( appDict.isEmpty() ) {
						_unknownApplications.remove( unknownAppKey );
					}
				}
			}
		}
		finally {
			_lock.writeLock().unlock();
		}
	}

	/**
	 * A snapshot of the currently registered unknown instances, for the
	 * adaptor-config serializer. Structured rather than pre-rendered XML so
	 * the serializer can merge these into same-named registered application
	 * elements — emitting duplicate {@code <application>} elements made
	 * adaptors that key applications by name drop one of the two, which once
	 * hid a perfectly healthy registered instance behind its dying siblings.
	 * The host is always wotaskd's own (an unknown instance is by definition
	 * local).
	 */
	List<IInstanceController.UnknownInstance> snapshot() {
		final List<IInstanceController.UnknownInstance> result = new ArrayList<>();

		_lock.readLock().lock();

		try {
			for( Map.Entry<String, Map<String, Instant>> appEntry : _unknownApplications.entrySet() ) {
				for( String port : appEntry.getValue().keySet() ) {
					result.add( new IInstanceController.UnknownInstance( appEntry.getKey(), port, _hostName ) );
				}
			}
		}
		finally {
			_lock.readLock().unlock();
		}

		return result;
	}
}
