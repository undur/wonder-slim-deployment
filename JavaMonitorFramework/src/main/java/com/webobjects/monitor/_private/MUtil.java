package com.webobjects.monitor._private;

import com.webobjects.monitor._private.model.MObject;

public class MUtil {

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
		int i = MObject.TIMES_OF_DAY.indexOf( value );

		if( i != -1 ) {
			return Integer.valueOf( i );
		}

		return null;
	}

	public static String morphedSchedulingStartTime( Integer value ) {

		if( value != null ) {
			return MObject.TIMES_OF_DAY.get( value.intValue() );
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
		int i = MObject.WEEKDAYS.indexOf( value );

		if( i != -1 ) {
			return Integer.valueOf( i );
		}

		return null;
	}

	public static String morphedSchedulingStartDay( Integer value ) {

		if( value != null ) {
			return MObject.WEEKDAYS.get( value.intValue() );
		}

		return null;
	}
}