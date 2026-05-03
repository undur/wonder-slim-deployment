package x;

import java.util.List;

public class FNotifications {

	public static void sendNotification( final String fromName, final String fromEmailAddress, final List<String> toAddresses, final String subject, final String plainTextMessage ) {
		try {
			Emailer.sendInThread( fromName, fromEmailAddress, toAddresses, subject, plainTextMessage, null );
		}
		catch( Throwable e ) {
			FLog.error( "Error attempting to send email: " + e );
		}
	}
}