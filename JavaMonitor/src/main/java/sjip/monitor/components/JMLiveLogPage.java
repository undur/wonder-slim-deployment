package sjip.monitor.components;

import com.webobjects.appserver.WOContext;

import er.extensions.appserver.ERXApplication;
import sjip.monitor.MonitorComponent;

/**
 * Watches the application's log live, as an SSE stream from JMLogFeed consumed by an EventSource on the page.
 */
public class JMLiveLogPage extends MonitorComponent {

	public JMLiveLogPage( WOContext context ) {
		super( context );
	}

	/**
	 * @return The URL of the SSE feed (DirectAction.liveLogAction). The page adds a nonce parameter per
	 *         connection - Firefox coalesces concurrent GETs to an identical URL, and a second tab's stream
	 *         must not wait for the first tab's to end (which it never does).
	 */
	public String liveLogURL() {
		return context().directActionURLForActionNamed( "liveLog", null );
	}

	public static JMLiveLogPage create( final WOContext context ) {
		return ERXApplication.erxApplication().pageWithName( JMLiveLogPage.class, context );
	}
}
