package x;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;

/**
 * Reads and writes the WebObjects-flavoured XML format used by the deployment wire protocol
 * (typed {@code NSDictionary} / {@code NSArray} / {@code NSString} / {@code NSNumber}
 * elements), matching the on-the-wire output of the WebObjects {@code _JavaMonitorCoder} /
 * {@code _JavaMonitorDecoder} pair byte-for-byte for the value subset {@code wotaskd} and
 * {@code JavaMonitor} actually exercise. Unlike the reference, the implementation only
 * leans on the JDK ({@code javax.xml.parsers}); the dependencies on Apple's Foundation /
 * Xerces classes have been removed.
 *
 * <h2>Supported values</h2>
 * <p>On encode the input tree must consist of:
 * <ul>
 *   <li>{@link Map} → emitted as {@code <key type="NSDictionary">…}. Iteration order
 *       on the wire is the {@code Map}'s own iteration order, so a {@link java.util.LinkedHashMap}
 *       (or an {@code NSMutableDictionary}, see below) is the recommended carrier.</li>
 *   <li>{@link List} → emitted as {@code <key type="NSArray">…}.</li>
 *   <li>{@link String} → emitted as {@code <key type="NSString">…} with XML-escaped content.</li>
 *   <li>{@link Number} subclasses ({@link Integer}, {@link Long}, {@link Short}, {@link Byte},
 *       {@link Float}, {@link Double}, {@link java.math.BigDecimal}, {@link java.math.BigInteger})
 *       → emitted as {@code <key type="NSNumber">…}.</li>
 *   <li>{@link Boolean} → emitted lossily as the literal NSString {@code "YES"} / {@code "NO"}.
 *       This matches the reference encoder exactly.</li>
 *   <li>{@code null} → emitted as {@code <key type="?">null</key>}.</li>
 * </ul>
 *
 * <h2>Decode return types — temporary regression</h2>
 * <p>The decoder presently returns {@code NSMutableDictionary} for dictionaries and
 * {@code NSMutableArray} for arrays so it can act as a true drop-in replacement for the
 * reference decoder while existing callers still cast to those Foundation types. The intent
 * is to flip the return types to {@link java.util.LinkedHashMap} and {@link java.util.ArrayList}
 * once the {@code M*} data-model migration off Foundation is done — see the {@code FIXME}
 * markers in {@link #decodeDictionary} and {@link #decodeArray}.
 *
 * <h2>Lossy round-tripping you should know about</h2>
 * <ul>
 *   <li><b>Booleans become Booleans, but not idempotently.</b> The decoder reproduces the
 *       reference behaviour and rewrites every NSString whose text content is exactly
 *       {@code "YES"} or {@code "NO"} as {@link Boolean#TRUE}/{@link Boolean#FALSE}. A
 *       genuine string containing the word "YES" is therefore indistinguishable from a
 *       boolean on the wire.</li>
 *   <li><b>Numbers narrow to {@code Integer}, otherwise widen to {@code Double}.</b> NSNumber
 *       text is parsed as {@code Integer} first, falling back to {@code Double} on failure
 *       (overflow, fractional part, etc.). {@link Long}, {@link Float}, {@link Short},
 *       {@link Byte}, {@link java.math.BigDecimal} round-trip into one of those two — original
 *       Number subtype is not preserved.</li>
 * </ul>
 *
 * <h2>{@code decodeRootObject(String)} is not what you'd expect</h2>
 * <p>For drop-in compatibility with {@code WOXMLDecoder}, {@link #decodeRootObject(String)}
 * treats its argument as a <em>SAX systemId</em> (URL or filesystem path resolved against
 * the working directory), <em>not</em> as the XML payload. Pass {@code byte[]}
 * if you have an in-memory document. This is documented further on the method.
 *
 * <h2>Thread safety</h2>
 * <p>Instances are stateless after construction. Concurrent {@code encode}/{@code decode} calls
 * on a single instance are safe.
 */
public class FoundationCoder {

	private static final String TYPE_DICTIONARY = "NSDictionary";
	private static final String TYPE_ARRAY = "NSArray";
	private static final String TYPE_STRING = "NSString";
	private static final String TYPE_NUMBER = "NSNumber";

	/**
	 * Encodes {@code object} as a single-document WebObjects XML fragment under the root
	 * tag {@code key}.
	 *
	 * <p>No {@code <?xml…?>} declaration is emitted (the reference encoder also omits it
	 * by default). Indentation is one tab per level, lines terminated with {@code \n}.
	 * The output is <em>not</em> wrapped in a CDATA section — string content is escaped
	 * using the standard five XML entities ({@code & < > ' "}).
	 *
	 * @param object the value to encode. See the class Javadoc for the supported types.
	 *               {@code null} is permitted and returns {@code null} (matching the
	 *               reference encoder's behaviour for null roots).
	 * @param key the tag name of the root element. Caller is responsible for choosing a
	 *            valid XML tag name; no validation is performed.
	 * @return the serialized document, or {@code null} if {@code object} was {@code null}.
	 * @throws IllegalArgumentException if the input tree contains a value whose runtime
	 *                                  type is not one of the supported types listed on
	 *                                  the class.
	 */
	public String encodeRootObjectForKey( Object object, String key ) {
		if( object == null ) {
			return null;
		}
		final StringBuilder buffer = new StringBuilder( 1024 );
		encodeObjectForKey( buffer, 0, object, key );
		return buffer.toString();
	}

	/**
	 * Loads an XML document from a SAX systemId (URL or filesystem path) and decodes it.
	 *
	 * <p><b>Reads the argument as a location, not as content.</b> This mirrors the
	 * reference {@code WOXMLDecoder.decodeRootObject(String)}, which forwards the String
	 * to {@code new org.xml.sax.InputSource(String)} — itself documented as accepting a
	 * systemId, i.e. a URL. Relative paths are resolved against the JVM working
	 * directory; absolute paths and {@code file:} / {@code http:} URLs all work.
	 *
	 * <p>If you have the document in memory as a String, route it through
	 * {@link #decodeRootObject(byte[])} after a UTF-8 encode rather than through this
	 * overload.
	 *
	 * @param systemId URL or filesystem path of the document to decode.
	 * @return the decoded tree (see the class Javadoc for return types).
	 * @throws RuntimeException wrapping any {@link IOException} / {@link SAXException} /
	 *         {@link ParserConfigurationException} raised during the load or parse.
	 */
	public Object decodeRootObject( String systemId ) {
		try {
			return decode( new InputSource( systemId ) );
		}
		catch( SAXException | IOException | ParserConfigurationException e ) {
			throw new RuntimeException( "Failed to decode XML", e );
		}
	}

	/**
	 * Decodes {@code bytes} as a UTF-8 (or BOM-detected) XML document and returns the
	 * resulting tree. See the class Javadoc for the return types.
	 *
	 * <p>This is the overload to use for in-memory payloads — HTTP request/response bodies,
	 * messages over the wire, etc. — since {@link #decodeRootObject(String)} would treat
	 * the same content as a path and fail.
	 *
	 * @throws RuntimeException wrapping any parser failure (malformed XML, unsupported
	 *         {@code type} attribute, DOCTYPE present, etc.).
	 */
	public Object decodeRootObject( byte[] bytes ) {
		try {
			return decode( new InputSource( new ByteArrayInputStream( bytes ) ) );
		}
		catch( SAXException | IOException | ParserConfigurationException e ) {
			throw new RuntimeException( "Failed to decode XML", e );
		}
	}

	private void encodeObjectForKey( StringBuilder buffer, int tabCount, Object value, String key ) {
		if( value == null ) {
			encodeStringInTag( buffer, tabCount, "null", key, "?" );
			return;
		}
		if( value instanceof String s ) {
			encodeStringInTag( buffer, tabCount, escape( s ), key, TYPE_STRING );
			return;
		}
		if( value instanceof Map<?, ?> m ) {
			encodeDictionaryForKey( buffer, tabCount, m, key );
			return;
		}
		if( value instanceof List<?> l ) {
			encodeArrayForKey( buffer, tabCount, l, key );
			return;
		}
		if( value instanceof Boolean b ) {
			encodeStringInTag( buffer, tabCount, b ? "YES" : "NO", key, TYPE_STRING );
			return;
		}
		if( value instanceof Number n ) {
			encodeStringInTag( buffer, tabCount, n.toString(), key, TYPE_NUMBER );
			return;
		}
		throw new IllegalArgumentException(
				value.getClass().getName() + " is not a type that FoundationCoder can encode" );
	}

	private void encodeDictionaryForKey( StringBuilder buffer, int tabCount, Map<?, ?> map, String key ) {
		appendTabs( buffer, tabCount );
		buffer.append( '<' ).append( key ).append( " type=\"" ).append( TYPE_DICTIONARY ).append( "\">\n" );
		// Emit dictionary entries in alphabetical key order so the on-disk output is
		// stable across runs/JVMs/Map implementations and diffs cleanly.
		final List<String> sortedKeys = new ArrayList<>( map.size() );
		for( Object k : map.keySet() ) {
			sortedKeys.add( k.toString() );
		}
		Collections.sort( sortedKeys );
		for( String entryKey : sortedKeys ) {
			encodeObjectForKey( buffer, tabCount + 1, map.get( entryKey ), entryKey );
		}
		appendTabs( buffer, tabCount );
		buffer.append( "</" ).append( key ).append( ">\n" );
	}

	private void encodeArrayForKey( StringBuilder buffer, int tabCount, List<?> list, String key ) {
		appendTabs( buffer, tabCount );
		buffer.append( '<' ).append( key ).append( " type=\"" ).append( TYPE_ARRAY ).append( "\">\n" );
		for( Object element : list ) {
			encodeObjectForKey( buffer, tabCount + 1, element, "element" );
		}
		appendTabs( buffer, tabCount );
		buffer.append( "</" ).append( key ).append( ">\n" );
	}

	private void encodeStringInTag( StringBuilder buffer, int tabCount, String content, String key, String type ) {
		appendTabs( buffer, tabCount );
		buffer.append( '<' ).append( key ).append( " type=\"" ).append( type ).append( "\">" );
		buffer.append( content );
		buffer.append( "</" ).append( key ).append( ">\n" );
	}

	private static void appendTabs( StringBuilder buffer, int tabCount ) {
		for( int i = 0; i < tabCount; i++ ) {
			buffer.append( '\t' );
		}
	}

	private static String escape( String s ) {
		final int length = s.length();
		final StringBuilder out = new StringBuilder( length );
		for( int i = 0; i < length; i++ ) {
			final char c = s.charAt( i );
			switch( c ) {
				case '&' -> out.append( "&amp;" );
				case '<' -> out.append( "&lt;" );
				case '>' -> out.append( "&gt;" );
				case '\'' -> out.append( "&apos;" );
				case '"' -> out.append( "&quot;" );
				default -> out.append( c );
			}
		}
		return out.toString();
	}

	private Object decode( InputSource source ) throws ParserConfigurationException, SAXException, IOException {
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware( false );
		factory.setExpandEntityReferences( false );
		factory.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
		factory.setFeature( "http://xml.org/sax/features/external-general-entities", false );
		factory.setFeature( "http://xml.org/sax/features/external-parameter-entities", false );
		factory.setXIncludeAware( false );

		final DocumentBuilder builder = factory.newDocumentBuilder();
		final Element root = builder.parse( source ).getDocumentElement();
		return decodeElement( root );
	}

	private Object decodeElement( Element element ) {
		final String type = element.getAttribute( "type" );
		return switch( type ) {
			case TYPE_DICTIONARY -> decodeDictionary( element );
			case TYPE_ARRAY -> decodeArray( element );
			case TYPE_STRING -> decodeString( element );
			case TYPE_NUMBER -> decodeNumber( element );
			default -> throw new IllegalArgumentException(
					"Unsupported type '" + type + "' on element <" + element.getTagName() + ">" );
		};
	}

	private Map<String, Object> decodeDictionary( Element element ) {
		// FIXME: The decoder should return a plain LinkedHashMap once the M* data model
		// no longer relies on NSMutableDictionary. Until that migration is done we hand
		// back an NSMutableDictionary so existing (NSDictionary) casts on decoded values
		// continue to work as a drop-in replacement for _JavaMonitorDecoder.
		// final Map<String,Object> map = new LinkedHashMap<>();
		final Map<String, Object> map = new NSMutableDictionary<>();
		final NodeList children = element.getChildNodes();
		for( int i = 0; i < children.getLength(); i++ ) {
			final Node child = children.item( i );
			if( child.getNodeType() != Node.ELEMENT_NODE ) {
				continue;
			}
			final Element childElement = (Element)child;
			map.put( childElement.getTagName(), decodeElement( childElement ) );
		}
		return map;
	}

	private List<Object> decodeArray( Element element ) {
		// FIXME: The decoder should return a plain ArrayList once the M* data model
		// no longer relies on NSMutableArray. See the matching note on decodeDictionary.
		// final List<Object> list = new ArrayList<>();
		final List<Object> list = new NSMutableArray<>();
		final NodeList children = element.getChildNodes();
		for( int i = 0; i < children.getLength(); i++ ) {
			final Node child = children.item( i );
			if( child.getNodeType() != Node.ELEMENT_NODE ) {
				continue;
			}
			list.add( decodeElement( (Element)child ) );
		}
		return list;
	}

	private static Object decodeString( Element element ) {
		final String text = textContent( element );
		if( "YES".equals( text ) ) {
			return Boolean.TRUE;
		}
		if( "NO".equals( text ) ) {
			return Boolean.FALSE;
		}
		return text;
	}

	private static Object decodeNumber( Element element ) {
		final String text = textContent( element ).trim();
		try {
			return Integer.valueOf( text );
		}
		catch( NumberFormatException e ) {
			return Double.valueOf( text );
		}
	}

	private static String textContent( Element element ) {
		final NodeList children = element.getChildNodes();
		if( children.getLength() == 0 ) {
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for( int i = 0; i < children.getLength(); i++ ) {
			final Node child = children.item( i );
			if( child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE ) {
				out.append( child.getNodeValue() );
			}
		}
		return out.toString();
	}
}
