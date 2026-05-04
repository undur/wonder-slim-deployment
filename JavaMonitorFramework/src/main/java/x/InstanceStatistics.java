package x;

import java.util.Map;

import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableDictionary;

/**
 * FIXME: We need to change this to use the actual types we use rather than Strings // Hugi 2026-05-04
 */

public class InstanceStatistics {
	public String transactions;
	public String activeSessions;
	public String avgTransactionTime;
	public String averageIdlePeriod;
	public String startedAt;

	/**
	 * FIXME: Purely here to perform serialization for sending the data over the wire // Hugi 2026-05-04
	 */
	public NSDictionary<String, String> toDictionary() {
		final NSMutableDictionary<String, String> m = new NSMutableDictionary<>();
		put( m, "transactions", transactions );
		put( m, "activeSessions", activeSessions );
		put( m, "avgTransactionTime", avgTransactionTime );
		put( m, "averageIdlePeriod", averageIdlePeriod );
		put( m, "startedAt", startedAt );
		return m;
	}

	/**
	 * Only for adding data to the dictionary in a null-safe way
	 */
	private static void put( Map map, String key, String value ) {
		if( value != null ) {
			map.put( key, value );
		}
	}
}
