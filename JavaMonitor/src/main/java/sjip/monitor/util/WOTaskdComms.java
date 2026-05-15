package sjip.monitor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sjip.core.model.MHost;
import sjip.core.model.MSiteConfig;
import sjip.core.model.MSiteConfigDto;
import sjip.core.x.FoundationCoder;
import sjip.core.x.ResponseWrapper;

public class WOTaskdComms {

	/**
	 * Communications Goop
	 *
	 * FIXME: {@code port} was added as a parameter to break MHost's hard dependency on
	 * {@code WOApplication.application().lifebeatDestinationPort()} so the wire layer
	 * is reachable from system tests without booting a WOApplication. Needs a nice
	 * central declaration of the wotaskd port. // Hugi 2026-05-12
	 */
	public static ResponseWrapper[] sendRequestToWotaskdArray( final String contentString, final List<MHost> hosts, final int port, final boolean willChange ) {

		final MHost aHost = hosts.get( 0 );

		// FIXME: A little danger sign here... // Hugi 2024-11-02
		if( aHost == null ) {
			return null;
		}

		final MSiteConfig siteConfig = aHost.siteConfig();

		// we had errors reaching a host last time - do it again!
		if( !siteConfig.hostErrorArray.isEmpty() ) {
			syncHostsWithErrors( siteConfig, port );
		}

		final Thread[] workers = new Thread[hosts.size()];
		final ResponseWrapper[] responses = new ResponseWrapper[workers.length];

		for( int i = 0; i < workers.length; i++ ) {
			final int j = i;

			Runnable work = new Runnable() {
				@Override
				public void run() {
					final MHost host = hosts.get( j );
					responses[j] = host.sendRequestToWotaskd( contentString, siteConfig.passwordForRequest(), port, willChange, false );
				}
			};

			workers[j] = new Thread( work );
			workers[j].start();
		}

		try {
			for( int i = 0; i < workers.length; i++ ) {
				workers[i].join();
			}
		}
		catch( InterruptedException ie ) {
			// might be bad?
		}

		return responses;
	}

	private static void syncHostsWithErrors( final MSiteConfig siteConfig, final int port ) {
		final List<MHost> hosts = new ArrayList<>( siteConfig.hostErrorArray );

		final Thread[] workers = new Thread[hosts.size()];

		for( int i = 0; i < workers.length; i++ ) {
			final int j = i;

			final Runnable work = new Runnable() {
				@Override
				public void run() {
					MHost host = hosts.get( j );
					host.sendRequestToWotaskd( syncRequestContent( siteConfig ), siteConfig.passwordForRequest(), port, true, true );
				}
			};

			workers[j] = new Thread( work );
			workers[j].start();
		}

		try {
			for( int i = 0; i < workers.length; i++ ) {
				workers[i].join();
			}
		}
		catch( InterruptedException ie ) {}
	}

	private static String syncRequestContent( final MSiteConfig siteConfig ) {
		final Map<String, Object> data = new LinkedHashMap<>();
		data.put( "SiteConfig", siteConfig.toDto() );
		final Map<String, Object> updateWotaskd = new LinkedHashMap<>();
		updateWotaskd.put( "sync", data );
		final Map<String, Object> monitorRequest = new LinkedHashMap<>();
		monitorRequest.put( "updateWotaskd", updateWotaskd );
		return new FoundationCoder().encodeRootObjectForKey( monitorRequest, "monitorRequest" );
	}
}