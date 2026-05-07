package sjip.core;

/**
 * Generic exception class used widely in the deployment system.
 * Too widely, really. It has very little structural meaning.
 */

public class SjipException extends Exception {

	public SjipException( String message ) {
		super( message );
	}

	public SjipException( String message, Throwable cause ) {
		super( message, cause );
	}
}