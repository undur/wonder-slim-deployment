package com.webobjects.monitor.application.components;

import java.io.File;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor.application.MonitorComponent.InstComponent;
import com.webobjects.monitor.util.ExperimentalUtilities;
import com.webobjects.monitor.util.JMLogViewerPage;

import er.extensions.appserver.ERXApplication;

public class InstDetailPage extends InstComponent {

	public String jstackString;

	public InstDetailPage( WOContext context ) {
		super( context );
	}

	public WOActionResults jstack() {
		jstackString = ExperimentalUtilities.jstack( myInstance() );
		return null;
	}

	public WOComponent returnClicked() {
		return AppDetailPage.create( context(), myInstance().application() );
	}

	/**
	 * FIXME: The logfile should be handed to us by the monitor service (Monitor may not be running on the same machine as the app/may not have access to the file) // Hugi 2024-10-30
	 */
	public WOComponent viewLog() {

		File file = null;

		final String outputPath = myInstance().outputPath();

		if( outputPath != null ) {
			file = new File( outputPath );
		}

		// FIXME: Here for testing only
		if( file == null || !file.exists() ) {
			file = new File( "/Users/hugi/work-strimillinn/Strimillinn-2" );
		}

		return JMLogViewerPage.create( context(), file );
	}

	public static InstDetailPage create( final WOContext context, final MInstance instance ) {
		final InstDetailPage page = ERXApplication.erxApplication().pageWithName( InstDetailPage.class, context );
		page.setMyInstance( instance );
		return page;
	}
}