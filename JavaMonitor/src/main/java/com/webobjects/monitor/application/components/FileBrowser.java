package com.webobjects.monitor.application.components;

/*
 © Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

 IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

 In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

 The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

 IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
 SUCH DAMAGE.
 */

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOResponse;
import com.webobjects.monitor._private.MUtil;
import com.webobjects.monitor._private.MonitorException;
import com.webobjects.monitor._private.model.MHost;
import com.webobjects.monitor.application.MonitorComponent;
import com.webobjects.monitor.application.components.FileBrowser.RemoteBrowseClient.RemoteFile;
import com.webobjects.monitor.application.components.FileBrowser.RemoteBrowseClient.RemoteResult;
import com.webobjects.monitor.util.WOTaskdHandler;

import x.FoundationCoder;
import x.ResponseWrapper;
import x.XUtil;

public class FileBrowser extends MonitorComponent {

	private static final Logger logger = LoggerFactory.getLogger( FileBrowser.class );

	public String startingPath; // passed in
	public String callbackUpdateAction; // passed in
	public String callbackSelectionAction; // passed in
	public MHost host; // passed in

	public boolean showFiles = true;
	public boolean isRoots = false;
	public String errorMsg;

	public RemoteFile aCurrentFile;
	public List<RemoteFile> _fileList;

	public FileBrowser( WOContext aWocontext ) {
		super( aWocontext );
	}

	public boolean hasErrorMsg() {
		return errorMsg != null && errorMsg.length() > 0;
	}

	public List<RemoteFile> fileList() {
		if( _fileList == null ) {
			retrieveFileList();
		}

		return _fileList;
	}

	private String retrieveFileList() {
		try {
			RemoteResult fetched = RemoteBrowseClient.getFileList( startingPath, host, showFiles );
			_fileList = fetched.files();
			isRoots = fetched.isRoots();
			startingPath = fetched.filepath();
			errorMsg = null;
		}
		catch( MonitorException me ) {
			if( isRoots )
				startingPath = null;
			logger.error( "Path Wizard Error: " + me.getMessage() );
			me.printStackTrace();
			errorMsg = me.getMessage();
		}
		return errorMsg;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {
		fileList(); // init variable
		super.appendToResponse( response, context );
	}

	public boolean isCurrentFileDirectory() {
		return aCurrentFile.fileType().equals( MUtil.FILE_TYPE_DIRECTORY );
	}

	public Object backClicked() {
		final String originalPath = startingPath;
		final Path parent = Path.of( startingPath ).getParent();
		startingPath = (parent == null) ? null : parent.normalize().toString();
		if( startingPath != null && originalPath.equals( startingPath ) ) {
			startingPath = null;
		}
		if( retrieveFileList() != null ) {
			startingPath = originalPath;
		}
		return performParentAction( callbackUpdateAction );
	}

	public Object directoryClicked() {
		final String originalPath = startingPath;
		final String aFile = aCurrentFile.file();
		startingPath = Path.of( startingPath, aFile ).normalize().toString();
		retrieveFileList();
		if( retrieveFileList() != null ) {
			startingPath = originalPath;
		}
		return performParentAction( callbackUpdateAction );
	}

	public Object jumpToClicked() {
		final String originalPath = startingPath;
		startingPath = Path.of( startingPath ).normalize().toString();
		retrieveFileList();
		if( retrieveFileList() != null ) {
			startingPath = originalPath;
		}
		return performParentAction( callbackUpdateAction );
	}

	public Object selectClicked() {
		final String aFile = aCurrentFile.file();
		startingPath = Path.of( startingPath, aFile ).normalize().toString();
		return performParentAction( callbackSelectionAction );
	}

	public Object selectCurrentDirClicked() {
		startingPath = Path.of( startingPath ).normalize().toString();
		return performParentAction( callbackSelectionAction );
	}

	public static class RemoteBrowseClient {

		public record RemoteResult( boolean isRoots, String filepath, List<RemoteFile> files ) {}

		public record RemoteFile( String file, Integer fileSize, String fileType ) {}

		private static final Logger logger = LoggerFactory.getLogger( RemoteBrowseClient.class );

		/**
		 * The URL invoked to get a remote file list.
		 */
		private static String BROWSE_URL = "/cgi-bin/WebObjects/wotaskd.woa/wa/RemoteBrowse/getPath";

		// FIXME: Dear lord. I feel the pain of whomever wrote this // Hugi 2024-11-10
		private static String EVIL_HACK = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>";

		private static RemoteResult extractFileListFromResponse( final ResponseWrapper response, final String sourcePath ) throws MonitorException {

			final String responseContentString = response.contentString();

			List<Map<String, ?>> deserializedResponseContent = Collections.emptyList();

			if( responseContentString != null ) {
				if( responseContentString.startsWith( "ERROR" ) ) {
					throw new MonitorException( "Path " + sourcePath + " does not exist" );
				}

				try {
					deserializedResponseContent = (List<Map<String,?>>)new FoundationCoder().decodeRootObjectFromString( EVIL_HACK + responseContentString );
				}
				catch( Exception e ) {
					logger.error( "RemoteBrowseClient _getFileListOutOfResponse Error decoding response: " + responseContentString );
					throw new MonitorException( "Host returned bad response for path " + sourcePath, e );
				}
			}
			else {
				logger.error( "RemoteBrowseClient _getFileListOutOfResponse Error decoding null response" );
				throw new MonitorException( "Host returned null response for path " + sourcePath );
			}

			final String isRoots = response.headerForKey( "isRoots" );
			final String filepath = response.headerForKey( "filepath" );

			final RemoteResult remoteResult = new RemoteResult( isRoots != null, filepath, new ArrayList<>() );

			for( Map<String, ?> map : deserializedResponseContent ) {
				final String file = (String)map.get( "file" );
				final Integer fileSize = (Integer)map.get( "fileSize" );
				final String fileType = (String)map.get( "fileType" );
				remoteResult.files().add( new RemoteFile( file, fileSize, fileType ) );
			}

			return remoteResult;
		}

		public static RemoteResult getFileList( final String path, final MHost host, final boolean showFiles ) throws MonitorException {

			try {
				final Builder requestBuilder = HttpRequest
						.newBuilder()
						.uri( URI.create( "http://%s:%s%s".formatted( host.name(), WOApplication.application().lifebeatDestinationPort(), RemoteBrowseClient.BROWSE_URL ) ) )
						.timeout( Duration.ofMillis( 5000 ) )
						.GET();

				// FIXME: Entering an extremely lame method of constructing and setting the headers. Later... // Hugi 2024-11-06
				final Map<String, List<String>> headers = new HashMap<>( WOTaskdHandler.siteConfig().passwordHeaderMap() );

				// FIXME: I don't think the path can/should ever be null or empty. Validate in method invocation and handle in UI? // Hugi 2024-11-10
				if( path != null && path.length() > 0 ) {
					headers.put( "filepath", List.of( path ) );
				}

				if( showFiles ) {
					headers.put( "showFiles", List.of( "YES" ) );
				}

				for( final Entry<String, List<String>> entry : headers.entrySet() ) {
					for( final String headerValue : entry.getValue() ) {
						requestBuilder.setHeader( entry.getKey(), headerValue );
					}
				}

				final HttpRequest request = requestBuilder.build();

				logger.info( "--> Sending request: =======" );
				logger.info( "{}", request );
				final HttpResponse<String> response = XUtil.sendRequest( request );
				logger.info( "--> Response received ======= " + response.headers() );

				// FIXME: Look into this error handling // Hugi 2024-11-10
				if( response.statusCode() != 200 ) {
					throw new MonitorException( "Error requesting directory listing for " + path + " from " + host.name() );
				}

				final ResponseWrapper responseWrapper = new ResponseWrapper( response.body(), response.headers() );
				final RemoteResult result = extractFileListFromResponse( responseWrapper, path );
				
				// By submitting a successful request, we've certainly certified that the host is available. Should probably stay...
				host.isAvailable = true;

				return result;
			}
			catch( MonitorException me ) {
				host.isAvailable = true;
				throw me;
			}
			catch( InterruptedException ie ) {
				Thread.currentThread().interrupt();
				host.isAvailable = false;
				throw new MonitorException( "Interrupted while requesting directory listing for " + path + " from " + host.name(), ie );
			}
			catch( Exception localException ) {
				host.isAvailable = false;
				logger.error( "Exception requesting directory listing: " );
				localException.printStackTrace();
				throw new MonitorException( "Exception requesting directory listing for " + path + " from " + host.name() + ": " + localException.toString(), localException );
			}
		}
	}
}