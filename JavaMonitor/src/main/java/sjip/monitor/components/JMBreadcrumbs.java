package sjip.monitor.components;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;

import sjip.core.model.MApplication;
import sjip.core.model.MHost;
import sjip.core.model.MInstance;
import sjip.monitor.MonitorComponent;

/**
 * The breadcrumb trail situating the user within Monitor, rendered in JMTablerLook's fixed trail slot.
 *
 * The trail is derived from whichever of the [application], [instance] and [host] bindings are set,
 * so a page only states its context; [current] marks the trail's end with the page's own name.
 * The full trail renders, ancestors as links and the current location as the active crumb —
 * it's the page's primary statement of where the user is.
 *
 * {@snippet :
 * <wo:JMBreadcrumbs instance="$myInstance" current="Configuration" />
 * }
 */
public class JMBreadcrumbs extends MonitorComponent {

	public record Crumb( String label, Supplier<WOComponent> destination ) {}

	public Crumb currentCrumb;
	public int currentIndex;

	public JMBreadcrumbs( WOContext context ) {
		super( context );
	}

	@Override
	public boolean synchronizesVariablesWithBindings() {
		return false;
	}

	public List<Crumb> trail() {
		final MInstance instance = (MInstance)valueForBinding( "instance" );
		final MHost host = (MHost)valueForBinding( "host" );
		final String current = (String)valueForBinding( "current" );

		MApplication boundApplication = (MApplication)valueForBinding( "application" );

		if( boundApplication == null && instance != null ) {
			boundApplication = instance.application();
		}

		final MApplication application = boundApplication;

		final List<Crumb> list = new ArrayList<>();

		if( application != null ) {
			list.add( new Crumb( "Applications", () -> ApplicationsPage.create( context() ) ) );
			list.add( new Crumb( application.name(), () -> AppDetailPage.create( context(), application ) ) );
		}

		if( instance != null ) {
			list.add( new Crumb( instance.displayName(), () -> InstDetailPage.create( context(), instance ) ) );
		}

		if( host != null ) {
			list.add( new Crumb( "Hosts", () -> HostsPage.create( context() ) ) );
			list.add( new Crumb( host.name(), null ) );
		}

		if( current != null ) {
			list.add( new Crumb( current, null ) );
		}

		return list;
	}

	public boolean hasTrail() {
		return !trail().isEmpty();
	}

	public boolean currentCrumbIsLink() {
		return currentCrumb.destination() != null && currentIndex < trail().size() - 1;
	}

	public WOComponent currentCrumbClicked() {
		return currentCrumb.destination().get();
	}
}
