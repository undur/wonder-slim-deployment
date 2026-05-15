package sjip.core.x;

import java.net.http.HttpHeaders;
import java.util.Optional;

/**
 * A little convenience wrapper for HTTP responses
 */

public record ResponseWrapper( String contentString, HttpHeaders headers ) {

	public String headerForKey( String key ) {

		if( headers() == null ) {
			return null;
		}

		final Optional<String> value = headers().firstValue( key );

		if( value.isEmpty() ) {
			return null;
		}

		return value.get();
	}
}