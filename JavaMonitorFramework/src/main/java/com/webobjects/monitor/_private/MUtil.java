package com.webobjects.monitor._private;

import java.util.ArrayList;
import java.util.List;

public class MUtil {

	public static final List<String> LOAD_SCHEDULERS = new ArrayList<>( List.of( "Default", "Round Robin", "Random", "Load Average", "Custom" ) );
	public static final List<String> LOAD_SCHEDULER_VALUES = new ArrayList<>( List.of( "DEFAULT", "ROUNDROBIN", "RANDOM", "LOADAVERAGE", "CUSTOM" ) );
	public static final List<String> HOST_TYPES = new ArrayList<>( List.of( "MacOSX", "Windows", "Unix" ) );
	public static final List<Integer> URL_VERSIONS = new ArrayList<>( List.of( 4, 3 ) );
	public static final List<String> WEEKDAYS = new ArrayList<>( List.of( "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" ) );
	public static final List<String> TIMES_OF_DAY = new ArrayList<>( List.of( "0000", "0100", "0200", "0300", "0400", "0500", "0600", "0700", "0800", "0900", "1000", "1100", "1200", "1300", "1400", "1500", "1600", "1700", "1800", "1900", "2000", "2100", "2200", "2300" ) );
	public static final List<Integer> SCHEDULING_INTERVALS = new ArrayList<>( List.of( 1, 2, 3, 4, 6, 8, 12 ) );
	public static final List<String> SCHEDULING_TYPES = new ArrayList<>( List.of( "HOURLY", "DAILY", "WEEKLY" ) );
	public static final String[] INSTANCE_STATES = new String[] { "UNKNOWN", "STARTING", "ALIVE", "STOPPING", "DEAD", "CRASHING" };

	public static final int UNKNOWN = 0;
	public static final int STARTING = 1;
	public static final int ALIVE = 2;
	public static final int STOPPING = 3;
	public static final int DEAD = 4;
	public static final int CRASHING = 5;

	public static final String WOTASKD_DIRECT_ACTION_URL = "/cgi-bin/WebObjects/wotaskd.woa/wa/monitorRequest";
	public static final String ADMIN_ACTION_STRING_PREFIX = "/cgi-bin/WebObjects/";
	public static final String ADMIN_ACTION_STRING_POSTFIX = ".woa/womp/instanceRequest";

	public static Integer validatedInteger( final Integer value ) {

		if( value == null ) {
			return null;
		}

		return Integer.valueOf( Math.abs( value.intValue() ) );
	}

	public static Integer validatedUrlVersion( Integer version ) {

		if( version != null ) {
			int intVal = version.intValue();

			if( intVal != 3 && intVal != 4 ) {
				return Integer.valueOf( 4 );
			}
		}

		return version;
	}

	public static String validatedHostType( String value ) {

		if( value != null ) {
			if( value.equals( "UNIX" ) || value.equals( "WINDOWS" ) || value.equals( "MACOSX" ) ) {
				return value;
			}
		}

		return null;
	}

	public static String validatedOutputPath( String value ) {

		if( value == null || value.length() == 0 ) {
			return "/dev/null";
		}

		return value;
	}

	public static Integer validatedLifebeatInterval( Integer value ) {

		int intVal = 0;

		try {
			intVal = value.intValue();
		}
		catch( Exception e ) {}

		if( intVal < 1 ) {
			return Integer.valueOf( 30 );
		}

		return value;
	}

	public static String validatedSchedulingType( String value ) {

		if( value != null ) {
			if( (value.equals( "HOURLY" )) || (value.equals( "DAILY" )) || (value.equals( "WEEKLY" )) ) {
				return value;
			}
		}

		return null;
	}

	public static Integer validatedSchedulingStartTime( Integer value ) {

		if( value != null ) {
			int intVal = value.intValue();

			if( intVal >= 0 && intVal <= 23 ) {
				return value;
			}
		}

		return null;
	}

	// Our array is from 0-23, but the display is for '12 AM' to '11 PM'
	public static Integer morphedSchedulingStartTime( String value ) {
		int i = TIMES_OF_DAY.indexOf( value );

		if( i != -1 ) {
			return Integer.valueOf( i );
		}

		return null;
	}

	public static String morphedSchedulingStartTime( Integer value ) {

		if( value != null ) {
			return TIMES_OF_DAY.get( value.intValue() );
		}

		return null;
	}

	public static Integer validatedSchedulingStartDay( Integer value ) {

		if( value != null ) {
			int intVal = value.intValue();

			if( intVal >= 0 && intVal <= 6 ) {
				return value;
			}
		}

		return null;
	}

	// Java normally returns 1-7, ObjC returned 0-6, JavaFoundation will return 0-6
	// Our array is from 0-6
	public static Integer morphedSchedulingStartDay( String value ) {
		int i = WEEKDAYS.indexOf( value );

		if( i != -1 ) {
			return Integer.valueOf( i );
		}

		return null;
	}

	public static String morphedSchedulingStartDay( Integer value ) {

		if( value != null ) {
			return WEEKDAYS.get( value.intValue() );
		}

		return null;
	}
}