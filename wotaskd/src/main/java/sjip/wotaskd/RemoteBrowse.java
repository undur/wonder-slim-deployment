package sjip.wotaskd;
/*
© Copyright 2006 - 2007 Apple Computer, Inc. All rights reserved.

IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
SUCH DAMAGE.
 */

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.webobjects.appserver.WODirectAction;
import com.webobjects.appserver.WOMessage;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;

import sjip.core.MUtil;
import sjip.x.FHosts;
import sjip.x.FoundationCoder;

public class RemoteBrowse extends WODirectAction {

	private static final Object[] FILE_KEYS = new Object[] { "file", "fileType", "fileSize" };

	private final String[] rootStrings;
	private final boolean singleRoot;
	private final String xmlRoots;

	public RemoteBrowse( WORequest aRequest ) {
		super( aRequest );

		final File[] roots = File.listRoots();
		singleRoot = roots.length <= 1;

		rootStrings = new String[roots.length];

		for( int i = 0; i < roots.length; i++ ) {
			rootStrings[i] = Path.of( roots[i].getAbsolutePath() ).normalize().toString();
		}

		final NSMutableArray rootArray = new NSMutableArray( rootStrings.length );

		for( int i = 0; i < rootStrings.length; i++ ) {
			final NSDictionary aFileDict = new NSDictionary( new Object[] { rootStrings[i], MUtil.FILE_TYPE_DIRECTORY, Long.valueOf( 0 ) }, FILE_KEYS );
			rootArray.addObject( aFileDict );
		}

		xmlRoots = new FoundationCoder().encodeRootObjectForKey( rootArray, "pathArray" ) + " \r\n";
	}

	private static List<Map<String,Object>> fileListForStartingPath( final String aStartingPath, final boolean showFiles ) {
		
		final File startingPathAsFile = new File( aStartingPath );

		if( !startingPathAsFile.exists() ) {
			return null;
		}

		// FIXME: IF directory is not accessible, we'll get an exception here. insert a check.
		
		final List<Map<String,Object>> aDirectoryArray = new ArrayList<>();
		final List<Map<String,Object>> aFileArray = new ArrayList<>();

		Arrays.stream( startingPathAsFile.list() )
				.sorted()
				.forEach( aFile -> {
					String aFileType;
					Long aFileSize;

					final String fullPath = Path.of( aStartingPath, aFile ).normalize().toString();
					File subfile = new File( fullPath );

					if( subfile.isDirectory() ) {
						aFileType = MUtil.FILE_TYPE_DIRECTORY;
						aFileSize = Long.valueOf( 0 );
					}
					else {
						aFileType = MUtil.FILE_TYPE_REGULAR;
						aFileSize = Long.valueOf( subfile.length() );
					}

					final Map<String,Object> aFileDict = Map.of( "file", aFile, "fileType", aFileType, "fileSize", aFileSize );

					if( aFileType.equals( MUtil.FILE_TYPE_DIRECTORY ) ) {
						aDirectoryArray.add( aFileDict );
					}
					else {
						aFileArray.add( aFileDict );
					}
				} );

		if( showFiles ) {
			aDirectoryArray.addAll( aFileArray );
		}

		return aDirectoryArray;
	}

	public WOResponse getPathAction() {
		final WORequest aRequest = request();
		final WOResponse aResponse = new WOResponse();

		if( FHosts.isUsingWebServer( aRequest.headers() ) ) {
			aResponse.setStatus( WOMessage.HTTP_STATUS_FORBIDDEN );
			aResponse.appendContentString( "Access Denied" );
			return aResponse;
		}

		String aPath = aRequest.headerForKey( "filepath" );
		final boolean showFiles = (aRequest.headerForKey( "showFiles" ) != null) ? true : false;

		// looking for roots, or root listing of only 1 root
		if( aPath == null && !singleRoot ) {
			aResponse.appendContentString( xmlRoots );
			aResponse.setHeader( "YES", "isRoots" );
		}
		else {
			if( aPath == null )
				aPath = rootStrings[0];

			final List<Map<String,Object>> anArray = fileListForStartingPath( aPath, showFiles );

			if( anArray == null ) {
				aResponse.appendContentString( "ERROR" );
			}
			else {
				final FoundationCoder aCoder = new FoundationCoder();
				String xmlString = aCoder.encodeRootObjectForKey( anArray, "pathArray" );
				xmlString = xmlString + " \r\n";
				aResponse.appendContentString( xmlString );
				aResponse.setHeader( aPath, "filepath" );
			}
		}

		return aResponse;
	}
}