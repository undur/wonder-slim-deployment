package x;

import com.webobjects.foundation.NSData;

/**
 * Thin façade around {@link FoundationCoder}. Originally introduced to consolidate the
 * codebase's use of Apple's {@code _JavaMonitorCoder} / {@code _JavaMonitorDecoder} into
 * one place so the dependency could later be lifted; now retained only as a stable name
 * for the call sites that still reach for it. New code should construct
 * {@link FoundationCoder} directly.
 *
 * <h2>API quirks worth knowing about</h2>
 * <ul>
 *   <li><b>{@link #decodeRootObject(String)} loads from a URL.</b> The String argument is
 *       a SAX systemId (URL or filesystem path resolved against the JVM working
 *       directory) — <em>not</em> the XML content. Inherited from {@code WOXMLDecoder}
 *       and preserved here so existing call sites (e.g. {@code MSiteConfig} reading
 *       {@code SiteConfig.xml} from disk) keep working. Pass {@code byte[]} or
 *       {@link NSData} for in-memory payloads.</li>
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
 *       {@code NSArray}; that's why those casts work. This is a temporary stopgap while
 *       the {@code M*} data model is migrated off Foundation — see the FIXME notes in
 *       {@link FoundationCoder}.</li>
 * </ul>
 *
 * <h2>Supported value types</h2>
 * <p>The deployment wire protocol only carries {@code NSDictionary}, {@code NSArray},
 * {@code NSString}, {@code NSNumber}, and the {@code "YES"}/{@code "NO"} pseudo-Booleans.
 * The reference coder also supported {@code NSData}, {@code NSTimestamp}, and
 * {@code EOEnterpriseObject}, but none of those are exercised here.
 */
public class CoderWrapper {

	private final FoundationCoder _coder = new FoundationCoder();

	/**
	 * Decodes {@code bytes} as a UTF-8 XML document. Use this overload for in-memory
	 * payloads such as HTTP request/response bodies.
	 */
	public Object decodeRootObject( byte[] bytes ) {
		return _coder.decodeRootObject( bytes );
	}

	/**
	 * @deprecated will be removed once the deployment code no longer carries
	 *             {@link NSData} around. Prefer {@link #decodeRootObject(byte[])}.
	 */
	@Deprecated
	public Object decodeRootObject( NSData data ) {
		return _coder.decodeRootObject( data );
	}

	/**
	 * Loads an XML document from a SAX systemId (URL or filesystem path) and decodes it.
	 *
	 * <p><b>Reads the argument as a location, not as content.</b> Inherited from the
	 * reference {@code WOXMLDecoder.decodeRootObject(String)}. If you have an in-memory
	 * String of XML, encode it to UTF-8 bytes and pass it through
	 * {@link #decodeRootObject(byte[])} instead.
	 */
	public Object decodeRootObject( String systemId ) {
		return _coder.decodeRootObject( systemId );
	}

	/**
	 * Encodes {@code object} as a single-document WebObjects XML fragment under the root
	 * tag {@code key}. {@link Boolean} values are emitted as the strings {@code "YES"} /
	 * {@code "NO"}; see the class Javadoc for the rest of the type rules.
	 */
	public String encodeRootObjectForKey( Object object, String key ) {
		return _coder.encodeRootObjectForKey( object, key );
	}
}
