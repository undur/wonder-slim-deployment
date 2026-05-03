package x;

import java.net.http.HttpHeaders;
import java.util.Optional;

/**
 * A little wrapper class for us to use while we're wrapping up WO style responses
 */

public class ResponseWrapper {

	private byte[] _content;
	private HttpHeaders _headers;

	// FIXME: Delete. This class should be immutable // Hugi 2026-05-03
	@Deprecated
	public ResponseWrapper() {
		this( null, null );
	}

	// FIXME: Delete. This class should be immutable // Hugi 2026-05-03
	@Deprecated
	public void setContent( byte[] newValue ) {
		_content = newValue;
	}

	public ResponseWrapper( final byte[] content, final HttpHeaders headers ) {
		_content = content;
		_headers = headers;
	}

	public byte[] content() {
		return _content;
	}

	public String contentString() {

		if( _content != null ) {
			return new String( _content );
		}

		return null;
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