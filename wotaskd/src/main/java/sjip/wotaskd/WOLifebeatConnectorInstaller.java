package sjip.wotaskd;

import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.DetectorConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.JettyHandlerDecorator;

/**
 * Installs {@link WOLifebeatDetector} in front of HTTP on WOAdaptorJetty's connector, so wotaskd can run on the
 * Jetty adaptor while still hearing WO's header-less lifebeats.
 *
 * Found by WOAdaptorJetty through ServiceLoader (META-INF/services/com.webobjects.appserver.JettyHandlerDecorator).
 * The decorator hook is used for its timing, not its purpose: it runs once the connector has been attached and before
 * the server starts, which is when connection factories can still be changed. The handler chain is returned untouched.
 */
public class WOLifebeatConnectorInstaller implements JettyHandlerDecorator {

	private static final Logger logger = LoggerFactory.getLogger( WOLifebeatConnectorInstaller.class );

	@Override
	public Handler decorate( final Server server, final Handler inner ) {

		for( final Connector connector : server.getConnectors() ) {
			if( connector instanceof AbstractConnector abstractConnector ) {

				// Connections the detector doesn't recognize fall through to the connector's next protocol - HTTP/1.1
				final DetectorConnectionFactory detector = new DetectorConnectionFactory( new WOLifebeatDetector() );
				abstractConnector.addFirstConnectionFactory( detector );
				abstractConnector.setDefaultProtocol( detector.getProtocol() );

				logger.info( "WO lifebeat detection installed on {}", connector );
			}
		}

		return inner;
	}
}
