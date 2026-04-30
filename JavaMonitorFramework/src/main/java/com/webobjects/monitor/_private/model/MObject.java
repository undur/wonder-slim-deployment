/*
© Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS.

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
 */
package com.webobjects.monitor._private.model;

import java.util.ArrayList;
import java.util.List;

import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation._NSThreadsafeMutableDictionary;

public class MObject {

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

	protected MSiteConfig _siteConfig;

	protected NSMutableDictionary<String, Object> values;
	protected _NSThreadsafeMutableDictionary<String, Object> adaptorValues = new _NSThreadsafeMutableDictionary<>( new NSMutableDictionary<>() );

	public MSiteConfig siteConfig() {
		return _siteConfig;
	}

	public NSMutableDictionary<String, Object> values() {
		return values;
	}

	public void setValues( NSMutableDictionary<String, Object> newValues ) {
		values = newValues;
		_siteConfig.dataHasChanged();
	}

	public void updateValues( NSDictionary<String, Object> aDict ) {
		values = new NSMutableDictionary<>( aDict );
		_siteConfig.dataHasChanged();
	}
}