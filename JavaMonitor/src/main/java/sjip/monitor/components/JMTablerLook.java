package sjip.monitor.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.foundation.NSArray;

import sjip.monitor.MonitorComponent;
import sjip.monitor.util.Icon;

public class JMTablerLook extends MonitorComponent {

	/**
	 * Bound to by wrapped components to set the actual page <title>
	 */
	public String title;

	/**
	 * Currently active page (purely for indicating currently selected page in menu)
	 */
	public int selectedPage;

	/**
	 * Item currently being iterated over in the top menu
	 */
	public MenuItem currentMenuItem;

	public String searchString;

	public JMTablerLook( WOContext context ) {
		super( context );
	}

	/**
	 * Keeping this around for configurability. "layout-fluid" will give us a full-width layout, while [null] will box us in
	 */
	public String bodyClass() {
		return "layout-fluid";
		//		return null;
	}

	public Map user() {
		return new HashMap<>();
	}

	public String avatarBackgroundStyle() {
		final String url = application().resourceManager().urlForResourceNamed( "images/avatar.png", "app", NSArray.emptyArray(), context().request() );
		return "background-image: url(%s)".formatted( url );
	}
	
	public boolean notSelected() {
		return selectedPage != currentMenuItem.id();
	}
	
	/**
	 * @return Display name for the <title> tag
	 */
	public String pageTitle() {
		return "Monitor: " + title;
	}

	/**
	 * @return true if logout is possible in the given context
	 */
	private boolean showLogout() {
		return siteConfig() != null && (session().isLoggedIn() && siteConfig().isPasswordRequired());
	}

	/**
	 * @return The action result of clicking the current menuitem
	 * 
	 * FIXME: Preferable we'd just use the supplied value in the link's action attribute. Unfortunately, KVC throws a fit when it sees the lambda. We'd like to try to fix that (but that must happen at the KVC level) // Hugi 2024-10-24
	 */
	public WOActionResults currentMenuItemClicked() {
		return currentMenuItem.supplier.get();
	}

	/**
	 * Items in the top menubar 
	 */
	public List<MenuItem> menuItems() {
		final ArrayList<MenuItem> items = new ArrayList<>();
		items.add( new MenuItem( APP_PAGE, "Applications", Icon.Cube, () -> ApplicationsPage.create( context() ) ) );
		items.add( new MenuItem( HOST_PAGE, "Hosts", Icon.Server, () -> HostsPage.create( context() ) ) );
		items.add( new MenuItem( SITE_PAGE, "Site", Icon.Home, () -> ConfigurePage.create( context() ) ) );
		items.add( new MenuItem( PREF_PAGE, "Preferences", Icon.Adjustments, () -> PrefsPage.create( context() ) ) );
		items.add( new MenuItem( HELP_PAGE, "Help", Icon.Help, () -> pageWithName( HelpPage.class ) ) );
		items.add( new MenuItem( MOD_PROXY_PAGE, "mod_proxy", Icon.Polygon, () -> pageWithName( ModProxyPage.class )) );

		if( showLogout() ) {
			items.add( new MenuItem( 7, "Logout", Icon.Home, () -> {
				session().setIsLoggedIn( false );
				return pageWithName( JMLoginPage.class );
			}) );
		}

		return items;
	}

	/**
	 * Represents a menuitem in the top menubar
	 */
	public record MenuItem( int id, String name, Icon icon, Supplier<WOActionResults> supplier ) {}
}