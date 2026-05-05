package x;

import com.webobjects.appserver.WOApplication;
import com.webobjects.monitor._private.IInstanceController;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor._private.model.MSiteConfig;

/**
 * Produces the adaptor-configuration XML consumed by WO HTTP adaptors (mod_WebObjects, modulo, …).
 *
 * <p>Settings flow: instance → application → siteConfig. The first non-null wins.
 *
 * <p>FIXME: Domain-wise this belongs in wotaskd — JavaMonitor never needs to generate adaptor
 * configuration, it only persists what wotaskd serves. Lives here for now to keep the migration
 * out of the framework module trivial // Hugi 2026-05-05
 */
public class AdaptorConfigSerialization {

	private AdaptorConfigSerialization() {}

	public static String generateAdaptorConfigXML( MSiteConfig siteConfig, boolean onlyIncludeRunningInstances, boolean shouldIncludeUnregisteredInstances ) {
		final StringBuilder sb = new StringBuilder( "<?xml version=\"1.0\" encoding=\"ASCII\"?>\n<adaptor>\n" );

		for( final MApplication anApp : siteConfig.applicationArray() ) {

			if( !(onlyIncludeRunningInstances && !anApp.isRunning_W()) ) {

				final Integer retries = XUtil.firstNonNull( anApp.retries(), siteConfig.retries() );
				final String scheduler = XUtil.firstNonNull( anApp.scheduler(), siteConfig.scheduler() );
				final Integer dormant = XUtil.firstNonNull( anApp.dormant(), siteConfig.dormant() );
				final String redir = XUtil.firstNonNull( anApp.redir(), siteConfig.redir() );
				final Integer poolsize = XUtil.firstNonNull( anApp.poolsize(), siteConfig.poolsize() );
				final Integer urlVersion = XUtil.firstNonNull( anApp.urlVersion(), siteConfig.urlVersion() );

				sb.append( "  <application name=\"" );
				sb.append( anApp.name() );

				if( retries != null ) {
					sb.append( "\" retries=\"" );
					sb.append( retries.toString() );
				}
				if( scheduler != null ) {
					sb.append( "\" scheduler=\"" );
					sb.append( scheduler );
				}
				if( dormant != null ) {
					sb.append( "\" dormant=\"" );
					sb.append( dormant );
				}
				if( redir != null ) {
					sb.append( "\" redir=\"" );
					sb.append( redir );
				}
				if( poolsize != null ) {
					sb.append( "\" poolsize=\"" );
					sb.append( poolsize.toString() );
				}
				if( urlVersion != null ) {
					sb.append( "\" urlVersion=\"" );
					sb.append( urlVersion.toString() );
				}
				sb.append( "\">\n" );

				for( final MInstance anInst : anApp.instanceArray() ) {

					if( !(onlyIncludeRunningInstances && !anInst.isRunning_W()) ) {

						final Integer id = anInst.id();
						final Integer port = anInst.port();
						final String host = anInst.hostName();
						final Integer sendTimeout = XUtil.firstNonNull( anInst.sendTimeout(), anApp.sendTimeout(), siteConfig.sendTimeout() );
						final Integer recvTimeout = XUtil.firstNonNull( anInst.recvTimeout(), anApp.recvTimeout(), siteConfig.recvTimeout() );
						final Integer cnctTimeout = XUtil.firstNonNull( anInst.cnctTimeout(), anApp.cnctTimeout(), siteConfig.cnctTimeout() );
						final Integer sendBufSize = XUtil.firstNonNull( anInst.sendBufSize(), anApp.sendBufSize(), siteConfig.sendBufSize() );
						final Integer recvBufSize = XUtil.firstNonNull( anInst.recvBufSize(), anApp.recvBufSize(), siteConfig.recvBufSize() );

						sb.append( "    <instance" );

						if( id != null ) {
							sb.append( " id=\"" );
							sb.append( id.toString() );
						}
						if( port != null ) {
							sb.append( "\" port=\"" );
							sb.append( port.toString() );
						}
						if( host != null ) {
							sb.append( "\" host=\"" );
							sb.append( host );
						}
						if( sendTimeout != null ) {
							sb.append( "\" sendTimeout=\"" );
							sb.append( sendTimeout.toString() );
						}
						if( recvTimeout != null ) {
							sb.append( "\" recvTimeout=\"" );
							sb.append( recvTimeout.toString() );
						}
						if( cnctTimeout != null ) {
							sb.append( "\" cnctTimeout=\"" );
							sb.append( cnctTimeout.toString() );
						}
						if( sendBufSize != null ) {
							sb.append( "\" sendBufSize=\"" );
							sb.append( sendBufSize.toString() );
						}
						if( recvBufSize != null ) {
							sb.append( "\" recvBufSize=\"" );
							sb.append( recvBufSize.toString() );
						}
						sb.append( "\"/>\n" );
					}
				}

				sb.append( "  </application>\n" );
			}
		}

		if( shouldIncludeUnregisteredInstances ) {
			final IInstanceController plMonitor = (IInstanceController)WOApplication.application().valueForKey( "localMonitor" );
			if( plMonitor != null ) {
				final StringBuffer unknownSB = plMonitor.generateAdaptorConfigXML();
				if( unknownSB.length() > 0 ) {
					sb.append( unknownSB );
				}
			}
		}

		sb.append( "</adaptor>\n" );
		return sb.toString();
	}
}
