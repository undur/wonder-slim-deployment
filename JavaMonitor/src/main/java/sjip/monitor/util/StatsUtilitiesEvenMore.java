package sjip.monitor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sjip.core.model.MApplication;
import sjip.core.model.MInstance;
import sjip.core.x.FoundationPropertyListSerialization;

/**
 * FIXME: Temporary holder class for some statistics functionality we're moving out of the front end // Hugi 2024-10-26 
 */

public class StatsUtilitiesEvenMore {

	public static String statisticsString() {
		return FoundationPropertyListSerialization.stringFromPropertyList( StatsUtilitiesEvenMore.statistics() );
	}

	private static List<Map<String, Object>> statistics() {
		final WOTaskdHandler handler = new WOTaskdHandler();

		final List<Map<String, Object>> stats = new ArrayList<>();

		handler.whileReading( () -> {
			for( final MApplication app : WOTaskdHandler.siteConfig().applicationArray() ) {
				// FIXME: Aren't we redundantly fetching the same info multiple times here? // Hugi 2024-11-08
				handler.getInstanceStatusForHosts( app.hostArray() );
				stats.add( statistics( app ) );
			}
		} );

		return stats;
	}

	private static Map<String, Object> statistics( final MApplication app ) {

		final Map<String, Object> result = new LinkedHashMap<>();
		result.put( "applicationName", app.name() );

		final List<MInstance> allInstances = app.instanceArray();
		final int instanceCount = allInstances.size();
		result.put( "configuredInstances", Integer.valueOf( instanceCount ) );

		int runningInstances = 0;
		int refusingInstances = 0;

		int sumSessions = 0;
		int maxSessions = 0;
		int sumTransactions = 0;
		int maxTransactions = 0;
		float sumAvgTransactionTime = 0f;
		float maxAvgTransactionTime = 0f;
		float sumAvgIdleTime = 0f;
		float maxAvgIdleTime = 0f;

		for( final MInstance instance : allInstances ) {
			if( instance.isRunning_M() ) {
				runningInstances++;
			}
			if( instance.isRefusingNewSessions() ) {
				refusingInstances++;
			}

			final int sessions = instance.statistics().activeSessionsValue();
			sumSessions += sessions;
			if( sessions > maxSessions ) maxSessions = sessions;

			final int transactions = instance.statistics().transactionsValue();
			sumTransactions += transactions;
			if( transactions > maxTransactions ) maxTransactions = transactions;

			final float avgTxTime = instance.statistics().avgTransactionTimeValue();
			sumAvgTransactionTime += avgTxTime;
			if( avgTxTime > maxAvgTransactionTime ) maxAvgTransactionTime = avgTxTime;

			final float avgIdleTime = instance.statistics().avgIdleTimeValue();
			sumAvgIdleTime += avgIdleTime;
			if( avgIdleTime > maxAvgIdleTime ) maxAvgIdleTime = avgIdleTime;
		}

		result.put( "runningInstances", Integer.valueOf( runningInstances ) );
		result.put( "refusingInstances", Integer.valueOf( refusingInstances ) );

		result.put( "sumSessions", sumSessions );
		result.put( "maxSessions", maxSessions );
		result.put( "avgSessions", instanceCount > 0 ? (float)sumSessions / instanceCount : 0f );

		result.put( "sumTransactions", sumTransactions );
		result.put( "maxTransactions", maxTransactions );
		result.put( "avgTransactions", instanceCount > 0 ? (float)sumTransactions / instanceCount : 0f );

		result.put( "maxAvgTransactionTime", maxAvgTransactionTime );
		result.put( "avgAvgTransactionTime", instanceCount > 0 ? sumAvgTransactionTime / instanceCount : 0f );

		result.put( "maxAvgIdleTime", maxAvgIdleTime );
		result.put( "avgAvgIdleTime", instanceCount > 0 ? sumAvgIdleTime / instanceCount : 0f );

		return result;
	}
}