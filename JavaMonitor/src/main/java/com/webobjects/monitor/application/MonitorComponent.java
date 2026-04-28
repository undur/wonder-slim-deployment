package com.webobjects.monitor.application;

import java.util.List;

import com.webobjects.appserver.WOContext;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MHost;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor._private.model.MSiteConfig;
import com.webobjects.monitor.util.WOTaskdHandler;

import er.extensions.components.ERXComponent;

public abstract class MonitorComponent extends ERXComponent {

	// FIXME: Get these constants out of the way and make "currentl selected page" mechanism nicer // Hugi 2024-11-09
	public final int APP_PAGE = 0;
	public final int HOST_PAGE = 1;
	public final int SITE_PAGE = 2;
	public final int PREF_PAGE = 3;
	public final int HELP_PAGE = 4;
	public final int MOD_PROXY_PAGE = 6;

	private WOTaskdHandler _handler;
	
	private String _message;

	public MonitorComponent( WOContext context ) {
		super( context );
		_handler = new WOTaskdHandler( session() );
	}

	@Override
	public void awake() {
		super.awake();
		_message = null;
	}

	public String message() {
		if( _message == null ) {
			_message = session().message();
		}
		return _message;
	}

	public Application application() {
		return (Application)super.application();
	}

	public Session session() {
		return (Session)super.session();
	}

	public MSiteConfig siteConfig() {
		return WOTaskdHandler.siteConfig();
	}
	
	public WOTaskdHandler handler() {
		return _handler;
	}

	public static abstract class AppComponent extends MonitorComponent {
		
		private MApplication _myApplication;

		public AppComponent( WOContext context ) {
			super( context );
		}

		public final MApplication myApplication() {
			return _myApplication;
		}
		
		public void setMyApplication( MApplication application ) {
			_myApplication = application;
		}
		
		/**
		 * FIXME: We should really just inline invocations of this method // Hugi 2024-11-09
		 */
		@Deprecated
		protected List<MHost> allHosts() {
			return siteConfig().hostArray();
		}
	}

	public static abstract class InstComponent extends MonitorComponent {

		private MInstance _myInstance;

		public InstComponent( WOContext context ) {
			super( context );
		}
		
		public final MInstance myInstance() {
			return _myInstance;
		}
		
		public void setMyInstance( MInstance instance ) {
			_myInstance = instance;
		}
	}
}