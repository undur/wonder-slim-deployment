package com.webobjects.monitor.util;

import java.util.List;

import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSPropertyListSerialization;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MInstance;

/**
 * FIXME: Temporary holder class for some statistics functionality we're moving out of the front end // Hugi 2024-10-26 
 */

public class StatsUtilitiesEvenMore {

	public static String statisticsString() {
		return NSPropertyListSerialization.stringFromPropertyList( StatsUtilitiesEvenMore.statistics() );
	}

	private static NSArray statistics() {
		final WOTaskdHandler handler = new WOTaskdHandler();

		final NSMutableArray stats = new NSMutableArray();

		handler.whileReading( () -> {
			for( final MApplication app : WOTaskdHandler.siteConfig().applicationArray() ) {
				// FIXME: Aren't we redundantly fetching the same info multiple times here? // Hugi 2024-11-08 
				handler.getInstanceStatusForHosts( app.hostArray() );
				stats.addObject( statistics( app ) );
			}
		} );

		return stats;
	}

	private static NSDictionary statistics( final MApplication app ) {

		final NSDictionary<String, Object> result = new NSMutableDictionary<>();
		result.put( "applicationName", app.name() );

		final List<MInstance> allInstances = app.instanceArray();
		result.put( "configuredInstances", Integer.valueOf( allInstances.size() ) );

		int runningInstances = 0;
		int refusingInstances = 0;

		for( MInstance instance : allInstances ) {
			if( instance.isRunning_M() ) {
				runningInstances++;
			}

			if( instance.isRefusingNewSessions() ) {
				refusingInstances++;
			}
		}

		result.put( "runningInstances", Integer.valueOf( runningInstances ) );
		result.put( "refusingInstances", Integer.valueOf( refusingInstances ) );

		result.put( "sumSessions", nonNull( app.instanceArray().valueForKeyPath( "@sum.activeSessionsValue" ) ) );
		result.put( "maxSessions", nonNull( app.instanceArray().valueForKeyPath( "@max.activeSessionsValue" ) ) );
		result.put( "avgSessions", nonNull( app.instanceArray().valueForKeyPath( "@avg.activeSessionsValue" ) ) );

		result.put( "sumTransactions", nonNull( app.instanceArray().valueForKeyPath( "@sum.transactionsValue" ) ) );
		result.put( "maxTransactions", nonNull( app.instanceArray().valueForKeyPath( "@max.transactionsValue" ) ) );
		result.put( "avgTransactions", nonNull( app.instanceArray().valueForKeyPath( "@avg.transactionsValue" ) ) );

		result.put( "maxAvgTransactionTime", nonNull( app.instanceArray().valueForKeyPath( "@max.avgTransactionTimeValue" ) ) );
		result.put( "avgAvgTransactionTime", nonNull( app.instanceArray().valueForKeyPath( "@avg.avgTransactionTimeValue" ) ) );

		result.put( "maxAvgIdleTime", nonNull( app.instanceArray().valueForKeyPath( "@max.avgIdleTimeValue" ) ) );
		result.put( "avgAvgIdleTime", nonNull( app.instanceArray().valueForKeyPath( "@avg.avgIdleTimeValue" ) ) );

		return result;
	}

	private static Object nonNull( Object value ) {
		return value != null ? value : "";
	}
}