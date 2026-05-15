package sjip.core.x;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FNotifications {

	private static final Logger logger = LoggerFactory.getLogger( FNotifications.class );

	public static void sendNotification( final String fromName, final String fromEmailAddress, final List<String> toAddresses, final String subject, final String plainTextMessage ) {
		try {
			Emailer.sendInThread( fromName, fromEmailAddress, toAddresses, subject, plainTextMessage, null );
		}
		catch( Throwable e ) {
			logger.error( "Error attempting to send email: " + e );
		}
	}
}