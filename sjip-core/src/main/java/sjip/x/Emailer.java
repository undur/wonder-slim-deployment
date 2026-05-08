package sjip.x;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sjip.x.Emailer.EmailMessage.EmailAttachment;

/**
 * A mailer that uses the SimpleJavaMail library to deliver emails.
 */

public class Emailer {

	static {
		// Fix for older versions of Outlook that don't know how to handle UTF-8 encoded filenames.
		System.setProperty( "mail.mime.encodeparameters", "false" );
	}

	private static Logger logger = LoggerFactory.getLogger( Emailer.class );

	/**
	 * Creates a re-used instance of the simplejavamail mailer.
	 */
	private Mailer _wrappedMailer = createWrappedMailer();

	private Emailer() {}

	/**
	 * Creates the wrapped SimpleJavaMail mailer instance that will survive alongside this instance.
	 *
	 * FIXME: We need to make more of those configurable. Current settings are mostly for testing 	// Hugi 2026-05-02
	 */
	private org.simplejavamail.api.mailer.Mailer createWrappedMailer() {
		final String smtpHost = FProperties.stringValue( FProperties.K.MAILER_SMTP_HOST );
		final int smtpPort = 587;
		final boolean useTLS = true;
		final String smtpUsername = FProperties.stringValue( FProperties.K.MAILER_SMTP_USERNAME );
		final String smtpPassword = FProperties.stringValue( FProperties.K.MAILER_SMTP_PASSWORD );

		// FIXME: Horrid, awaiting propert persisted configuration // Hugi 2026-05-02
		if( smtpHost == null ) {
			return null;
		}

		final MailerRegularBuilderImpl builder = MailerBuilder
				.withSMTPServer( smtpHost, smtpPort, smtpUsername, smtpPassword );

		if( useTLS ) {
			builder.withTransportStrategy( TransportStrategy.SMTP_TLS );
		}

		return builder.buildMailer();
	}

	private static Emailer create() {
		return new Emailer();
	}

	public static void sendInThread( final String fromName, final String fromEmailAddress, final List<String> toAddresses, final String subject, final String plainTextContent, final String htmlContent ) {
		Executors.newVirtualThreadPerTaskExecutor().submit( () -> {
			final EmailMessage e = new EmailMessage();
			e.fromName = fromName;
			e.fromEmailAddress = fromEmailAddress;
			e.toAddresses = toAddresses;
			e.subject = subject;

			if( plainTextContent != null ) {
				e.plainTextContent = plainTextContent;
			}

			if( htmlContent != null ) {
				e.htmlContent = htmlContent;
			}

			create().sendMessage( e );
		} );
	}

	private void sendMessage( final EmailMessage emailWrapper ) {

		// FIXME: Using a null check to see if we have a configured mailer is pretty horrid. Fix up // Hugi 2026-05-02
		if( _wrappedMailer == null ) {
			logger.warn( "Mailer not configured. Not sending notification e-mail" );
			return;
		}

		Objects.requireNonNull( emailWrapper );
		logger.debug( "Sending email message {}", emailWrapper );

		try {
			final EmailPopulatingBuilder emailBuilder = EmailBuilder.startingBlank();
			emailBuilder.from( emailWrapper.fromName, emailWrapper.fromEmailAddress );

			if( emailWrapper.replyToEmailAddress != null ) {
				emailBuilder.withReplyTo( emailWrapper.replyToEmailAddress );
			}

			if( emailWrapper.bounceToEmailAddress != null ) {
				emailBuilder.withBounceTo( emailWrapper.bounceToEmailAddress );
			}

			for( final String toAddress : emailWrapper.toAddresses ) {
				emailBuilder.to( toAddress );
			}

			for( final String ccAddress : emailWrapper.ccAddresses ) {
				emailBuilder.to( ccAddress );
			}

			for( final String bccAddress : emailWrapper.bccAddresses ) {
				emailBuilder.to( bccAddress );
			}

			emailBuilder.withSubject( emailWrapper.subject );

			if( emailWrapper.plainTextContent != null ) {
				emailBuilder.withPlainText( emailWrapper.plainTextContent );
			}

			if( emailWrapper.htmlContent != null ) {
				emailBuilder.withHTMLText( emailWrapper.htmlContent );
			}

			if( emailWrapper.attachments != null ) {
				for( EmailAttachment emailAttachment : emailWrapper.attachments ) {
					String mimeType = emailAttachment.mimeType;

					if( mimeType == null ) {
						mimeType = "application/octet-stream";
					}

					emailBuilder.withAttachment( emailAttachment.name, emailAttachment.data, mimeType );
				}
			}

			final Email email = emailBuilder.buildEmail();

			_wrappedMailer.sendMail( email );
		}
		catch( Exception e ) {
			logger.error( "An exception occurred while composing and sending an e-mail", e );
			throw new RuntimeException( e );
		}
	}

	public static class EmailMessage {

		public String fromName;
		public String fromEmailAddress;
		public String replyToEmailAddress;
		public String bounceToEmailAddress;
		public List<String> toAddresses = new ArrayList<>();
		public List<String> ccAddresses = new ArrayList<>();
		public List<String> bccAddresses = new ArrayList<>();
		public String subject;
		public String plainTextContent;
		public String htmlContent;
		public List<EmailAttachment> attachments = new ArrayList<>();

		public static class EmailAttachment {

			/**
			 * Name (including file extension, if applicable)
			 */
			public String name;

			/**
			 * Attachment data
			 */
			public byte[] data;

			/**
			 * Attachment mimeType
			 */
			public String mimeType;
		}
	}
}