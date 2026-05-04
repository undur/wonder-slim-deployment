package com.webobjects.monitor.application.components;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.foundation.NSArray;
import com.webobjects.monitor.application.MonitorComponent;
import com.webobjects.monitor.util.Icon;

public class JMTablerLook extends MonitorComponent {

	/**
	 * Bound to by wrapped components to set the actual page <title>
	 */
	public String title;

	/**
	 * FIXME: wat?
	 */
	public int currentPage = APP_PAGE;
	
	/**
	 * FIXME: wat?
	 */
	public String pageId;

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
		return currentPage != currentMenuItem.id();
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
		items.add( new MenuItem( 0, "Applications", Icon.Cube, () -> ApplicationsPage.create( context() ) ) );
		items.add( new MenuItem( 1, "Hosts", Icon.Server, () -> HostsPage.create( context() ) ) );
		items.add( new MenuItem( 2, "Site", Icon.Home, () -> ConfigurePage.create( context() ) ) );
		items.add( new MenuItem( 3, "Preferences", Icon.Adjustments, () -> PrefsPage.create( context() ) ) );
		items.add( new MenuItem( 4, "Help", Icon.Help, () -> pageWithName( HelpPage.class ) ) );
		items.add( new MenuItem( 6, "mod_proxy", Icon.Polygon, () -> pageWithName( ModProxyPage.class )) );

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