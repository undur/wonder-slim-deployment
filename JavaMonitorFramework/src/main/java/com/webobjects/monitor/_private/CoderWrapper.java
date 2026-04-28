package com.webobjects.monitor._private;

import com.webobjects.appserver.xml._JavaMonitorCoder;
import com.webobjects.appserver.xml._JavaMonitorDecoder;
import com.webobjects.foundation.NSData;

/**
 * Thin façade around the WebObjects {@code _JavaMonitorCoder} / {@code _JavaMonitorDecoder}
 * pair. Originally introduced to consolidate the codebase's use of Apple's XML coding into
 * one place so the dependency could later be lifted; the eventual replacement
 * ({@code x.FoundationCoder}) is now in the tree alongside this class.
 *
 * <h2>API quirks worth knowing about</h2>
 * <ul>
 *   <li><b>{@link #decodeRootObject(String)} loads from a URL.</b> The String argument is
 *       a SAX systemId (URL or filesystem path resolved against the JVM working
 *       directory) — <em>not</em> the XML content. This is inherited from
 *       {@code WOXMLDecoder} and is preserved here so existing call sites (e.g.
 *       {@code MSiteConfig} reading {@code SiteConfig.xml} from disk) continue to work.
 *       To decode an in-memory payload, use {@link #decodeRootObject(byte[])}.</li>
 *   <li><b>The wire format treats Booleans as the strings {@code "YES"} and {@code "NO"}.</b>
 *       The encoder writes them that way and the decoder rewrites those exact tokens back
 *       to {@link Boolean#TRUE} / {@link Boolean#FALSE}. A genuine String containing the
 *       literal word "YES" is therefore indistinguishable from a true boolean once
 *       round-tripped through this coder.</li>
 *   <li><b>NSNumber decoding is lossy.</b> Whole numbers come back as {@link Integer} (or
 *       {@link Double} on overflow); fractional numbers come back as {@link Double}. The
 *       original {@link Number} subtype on the encode side is not preserved.</li>
 *   <li><b>Decoded dictionaries are {@code NSMutableDictionary} and arrays are
 *       {@code NSMutableArray}.</b> Existing call sites cast to {@code NSDictionary} /
 *       {@code NSArray}; that's why those casts work.</li>
 * </ul>
 *
 * <h2>Supported value types</h2>
 * <p>The deployment wire protocol only carries {@code NSDictionary}, {@code NSArray},
 * {@code NSString}, {@code NSNumber}, and the {@code "YES"}/{@code "NO"} pseudo-Booleans.
 * The reference coder also supports {@code NSData}, {@code NSTimestamp}, and
 * {@code EOEnterpriseObject}, but none of those are exercised here.
 */
public class CoderWrapper {

	private _JavaMonitorCoder _wrappedCoder;
	private _JavaMonitorDecoder _wrappedDecoder;

	public CoderWrapper() {
		_wrappedCoder = new _JavaMonitorCoder();
		_wrappedDecoder = new _JavaMonitorDecoder();
	}

	/**
	 * Decodes {@code bytes} as a UTF-8 XML document. Use this overload for in-memory
	 * payloads such as HTTP request/response bodies.
	 */
	public Object decodeRootObject( byte[] bytes ) {
		return _wrappedDecoder.decodeRootObject( new NSData( bytes ) );
	}

	/**
	 * @deprecated will be removed once the deployment code no longer carries
	 *             {@link NSData} around. Prefer {@link #decodeRootObject(byte[])}.
	 */
	@Deprecated
	public Object decodeRootObject( NSData data ) {
		return _wrappedDecoder.decodeRootObject( data );
	}

	/**
	 * Loads an XML document from a SAX systemId (URL or filesystem path) and decodes it.
	 *
	 * <p><b>Reads the argument as a location, not as content.</b> Inherited from the
	 * reference {@code WOXMLDecoder.decodeRootObject(String)}. If you have an in-memory
	 * String of XML, encode it to UTF-8 bytes and pass it through
	 * {@link #decodeRootObject(byte[])} instead.
	 */
	public Object decodeRootObject( String string ) {
		return _wrappedDecoder.decodeRootObject( string );
	}

	/**
	 * Encodes {@code object} as a single-document WebObjects XML fragment under the root
	 * tag {@code key}. {@link Boolean} values are emitted as the strings {@code "YES"} /
	 * {@code "NO"}; see the class Javadoc for the rest of the type rules.
	 */
	public String encodeRootObjectForKey( Object object, String key ) {
		return _wrappedCoder.encodeRootObjectForKey( object, key );
	}
}
