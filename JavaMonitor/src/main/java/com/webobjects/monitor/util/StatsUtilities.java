/*
© Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */
package com.webobjects.monitor.util;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MInstance;

public class StatsUtilities {

	private static final Logger logger = LoggerFactory.getLogger( StatsUtilities.class );

	/**
	 * @return The total number of transactions for all instances of the application 
	 */
	public static Integer totalTransactions( final MApplication anApp ) {
		return intTotal( anApp, "transactions", false );
	}

	/**
	 * @return Total number of transactions for running instances of the given monitored application
	 */
	public static Integer totalTransactionsForRunningInstances( final MApplication anApp ) {
		return intTotal( anApp, "transactions", true );
	}

	public static Integer totalActiveSessions( MApplication anApp ) {
		return intTotal( anApp, "activeSessions", false );
	}

	/**
	 * @return Total number of active sessions for running instances of the given monitored application
	 */
	public static Integer totalActiveSessionsForRunningInstances( final MApplication anApp ) {
		return intTotal( anApp, "activeSessions", true );
	}

	/**
	 * @return Sum of the statistic indicated by the given statisticsKey of the given monitored application
	 */
	private static Integer intTotal( final MApplication anApp, final String statisticsKey, final boolean runningOnly ) {
		int result = 0;

		for( final MInstance instance : anApp.instanceArray() ) {
			if( !runningOnly || instance.isRunning_M() ) {
				final Map map = instance.statistics();

				if( map != null ) {
					try {
						String value = (String)map.get( statisticsKey );
						result = result + Integer.parseInt( value );
					}
					catch( Throwable ex ) {
						// do nothing
						// FIXME: Don't fail silently! // Hugi 2024-10-25
					}
				}
			}
		}

		return Integer.valueOf( result );
	}

	public static Float totalAverageTransaction( final MApplication anApp ) {

		float aTotalTime = (float)0.0;
		int aTotalTrans = 0;
		float aTotalAvg = (float)0.0;

		for( MInstance instance : anApp.instanceArray() ) {
			final Map map = instance.statistics();

			if( map != null ) {
				try {
					String value = (String)map.get( "transactions" );
					Integer aTrans = Integer.valueOf( value );

					if( aTrans.intValue() > 0 ) {
						value = (String)map.get( "avgTransactionTime" );
						Float aTime = Float.valueOf( value );
						aTotalTime = aTotalTime + (aTrans.intValue() * aTime.floatValue());
						aTotalTrans = aTotalTrans + (aTrans.intValue());
					}
				}
				catch( Throwable ex ) {
					// do nothing
					// FIXME: Don't fail silently! // Hugi 2024-10-25
				}
			}
		}

		if( aTotalTrans > 0 ) {
			aTotalAvg = aTotalTime / aTotalTrans;
		}

		return Float.valueOf( aTotalAvg );
	}

	public static Float totalAverageIdleTime( final MApplication anApp ) {

		float aTotalTime = (float)0.0;
		int aTotalTrans = 0;
		float aTotalAvg = (float)0.0;

		for( final MInstance instance : anApp.instanceArray() ) {
			final Map map = instance.statistics();

			if( map != null ) {
				try {
					String value = (String)map.get( "transactions" );
					Integer aTrans = Integer.valueOf( value );

					if( aTrans.intValue() > 0 ) {
						String idleString = (String)map.get( "averageIdlePeriod" );
						Float aTime = Float.valueOf( idleString );
						aTotalTime = aTotalTime + (aTrans.intValue() * aTime.floatValue());
						aTotalTrans = aTotalTrans + (aTrans.intValue());
					}
				}
				catch( Throwable ex ) {
					// do nothing
					// FIXME: Don't fail silently! // Hugi 2024-10-25
				}
			}
		}

		if( aTotalTrans > 0 ) {
			aTotalAvg = aTotalTime / aTotalTrans;
		}

		return Float.valueOf( aTotalAvg );
	}

	private static final DateTimeFormatter STARTED_AT_FORMATTER = DateTimeFormatter.ofPattern( "yyyy:MM:dd:HH:mm:ss z" ).withZone( ZoneOffset.UTC );

	public static Float actualTransactionsPerSecond( final MApplication anApp ) {

		float result = (float)0.0;

		for( final MInstance instance : anApp.instanceArray() ) {

			final Map map = instance.statistics();

			if( map != null ) {
				String startedAt = (String)map.get( "startedAt" );
				Integer aTrans;
				float anInstRate = (float)0.0;

				try {
					aTrans = Integer.valueOf( (String)map.get( "transactions" ) );
				}
				catch( Throwable ex ) {
					aTrans = null;
				}

				if( aTrans != null && (aTrans.intValue() > 0) ) {
					float aRunningTime;

					try {
						// The stats deliver startedAt as a GMT timestamp string.
						final long startedEpochMillis = ZonedDateTime.parse( startedAt, STARTED_AT_FORMATTER ).toInstant().toEpochMilli();
						aRunningTime = (startedEpochMillis - System.currentTimeMillis()) / 1000.0f;
					}
					catch( DateTimeParseException ex ) {
						aRunningTime = (float)0.0;
						logger.error( "Format error in StatsUtilities: " + startedAt );
						logger.error( "{}", ex.getErrorIndex() );
						logger.error( "Actual Transactions Per Second rate is inaccurate." );
					}

					if( aRunningTime > 0.0 ) {
						anInstRate = (aTrans.floatValue()) / aRunningTime;
					}
				}
				result = result + anInstRate;
			}
		}

		return Float.valueOf( result );
	}
}