package sjip.x;

import java.net.http.HttpHeaders;
import java.util.Optional;

/**
 * A little wrapper class for us to use while we're wrapping up WO style responses
 */

public class ResponseWrapper {

	private String _contentString;
	private HttpHeaders _headers;

	// FIXME: Delete. This class should be immutable // Hugi 2026-05-03
	@Deprecated
	public ResponseWrapper() {
		this( null, null );
	}

	public ResponseWrapper( final String contentString, final HttpHeaders headers ) {
		_contentString = contentString;
		_headers = headers;
	}

	public String contentString() {
		return _contentString;
	}

	// FIXME: Delete. This class should be immutable // Hugi 2026-05-03
	@Deprecated
	public void setContentString( String contentString ) {
		_contentString = contentString;
	}

	public String headerForKey( String key ) {

		if( _headers == null ) {
			return null;
		}

		final Optional<String> value = _headers.firstValue( key );

		if( value.isEmpty() ) {
			return null;
		}

		return value.get();
	}
}