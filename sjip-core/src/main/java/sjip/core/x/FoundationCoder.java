package sjip.core.x;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 *   <li>{@link Map} → emitted as {@code <key type="NSDictionary">…}. Wire order is the
 *       {@code Map}'s own iteration order, so a {@link java.util.LinkedHashMap} is the
 *       recommended carrier.</li>
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
 * <h2>Decode return types</h2>
 * <p>Dictionaries decode to {@link java.util.LinkedHashMap}, arrays to
 * {@link java.util.ArrayList}. Insertion order matches document order.
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

	public Object decodeRootObjectFromString( String string ) {
		return decodeRootObject( string.getBytes( StandardCharsets.UTF_8 ) );
	}

	/**
	 * Decodes the given dict-shaped value into an instance of {@code recordClass}. The
	 * record's component names are matched against the dict keys; component values come
	 * straight from the dict. Components whose key is absent from the dict are populated
	 * with {@code null} (so {@code recordClass}'s components should all be reference types
	 * — boxed {@code Integer}, {@code Boolean}, {@code String}, etc., not primitives).
	 *
	 * <p>List-of-record components are decoded element-by-element: a component of type
	 * {@code List<MHostDto>} pulls each element of the dict's inner list through
	 * {@code MHostDto}'s decoder.
	 *
	 * @param dict        decoded dict (as produced by {@code decodeRootObject*}). Must be a
	 *                    {@link Map}; pass the result of decoding the wire XML.
	 * @param recordClass target record type. The class must declare a canonical constructor
	 *                    whose parameter list matches the record components.
	 * @throws IllegalArgumentException if {@code recordClass} isn't a record, if a value in
	 *                                  the dict can't be coerced to the component type, or
	 *                                  if the canonical constructor isn't accessible.
	 */
	public <T> T decodeRecord( final Map<String, Object> dict, final Class<T> recordClass ) {
		if( !recordClass.isRecord() ) {
			throw new IllegalArgumentException( recordClass.getName() + " is not a record" );
		}
		final RecordComponent[] components = recordClass.getRecordComponents();
		final Object[] args = new Object[components.length];
		final Class<?>[] paramTypes = new Class<?>[components.length];

		for( int i = 0; i < components.length; i++ ) {
			final RecordComponent component = components[i];
			paramTypes[i] = component.getType();
			final Object raw = dict.get( component.getName() );
			args[i] = coerce( raw, component.getType(), component.getGenericType() );
		}

		try {
			final Constructor<T> canonical = recordClass.getDeclaredConstructor( paramTypes );
			canonical.setAccessible( true );
			return canonical.newInstance( args );
		}
		catch( final ReflectiveOperationException e ) {
			throw new IllegalArgumentException( "Failed to instantiate " + recordClass.getName(), e );
		}
	}

	/**
	 * Coerces a value pulled out of the decoded dict into the type expected by a record
	 * component. Most values pass through as-is (decoder already returns
	 * {@code String}/{@code Integer}/{@code Boolean}); the interesting cases are nested
	 * records (decode a dict to that record type) and lists of records (decode each
	 * element).
	 */
	private Object coerce( final Object raw, final Class<?> targetType, final Type targetGenericType ) {
		if( raw == null ) {
			return null;
		}
		// Nested record.
		if( targetType.isRecord() && raw instanceof Map<?, ?> nested ) {
			@SuppressWarnings("unchecked")
			final Map<String, Object> typedNested = (Map<String, Object>)nested;
			return decodeRecord( typedNested, targetType );
		}
		// List<RecordType> — peel the parameterized element type and decode each entry.
		if( List.class.isAssignableFrom( targetType ) && raw instanceof List<?> rawList ) {
			final Class<?> elementType = listElementClass( targetGenericType );
			if( elementType != null && elementType.isRecord() ) {
				final List<Object> out = new ArrayList<>( rawList.size() );
				for( final Object element : rawList ) {
					if( element instanceof Map<?, ?> elementMap ) {
						@SuppressWarnings("unchecked")
						final Map<String, Object> typedElementMap = (Map<String, Object>)elementMap;
						out.add( decodeRecord( typedElementMap, elementType ) );
					}
					else {
						out.add( element );
					}
				}
				return out;
			}
			return rawList;
		}
		return raw;
	}

	/**
	 * Returns the element class for a {@code List<X>} generic type, or {@code null} if the
	 * target type isn't a parameterized list with a concrete element class.
	 */
	private static Class<?> listElementClass( final Type listType ) {
		if( !(listType instanceof ParameterizedType pt) ) {
			return null;
		}
		final Type[] args = pt.getActualTypeArguments();
		if( args.length != 1 ) {
			return null;
		}
		if( args[0] instanceof Class<?> c ) {
			return c;
		}
		return null;
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
		if( value.getClass().isRecord() ) {
			encodeRecordForKey( buffer, tabCount, value, key );
			return;
		}
		throw new IllegalArgumentException(
				value.getClass().getName() + " is not a type that FoundationCoder can encode" );
	}

	/**
	 * Encodes a record as an {@code NSDictionary} whose entries are the record's components.
	 * Component name becomes the dict key; the component's value is encoded recursively via
	 * the same rules as any other value. {@code null}-valued components are omitted, matching
	 * the "only non-null fields appear in the dict" contract the M-classes already follow
	 * in {@code dictionaryForArchive()}.
	 *
	 * <p>This is the encode side of the record↔XML bridge that lets M-classes hand a typed
	 * DTO to the codec instead of building dictionaries themselves.
	 */
	private void encodeRecordForKey( StringBuilder buffer, int tabCount, Object record, String key ) {
		appendTabs( buffer, tabCount );
		buffer.append( '<' ).append( key ).append( " type=\"" ).append( TYPE_DICTIONARY ).append( "\">\n" );

		// Sort components alphabetically by name so output is stable independent of source
		// order — same contract as encodeDictionaryForKey above.
		final RecordComponent[] components = record.getClass().getRecordComponents();
		final RecordComponent[] sorted = components.clone();
		java.util.Arrays.sort( sorted, ( a, b ) -> a.getName().compareTo( b.getName() ) );

		for( final RecordComponent component : sorted ) {
			final Object componentValue;
			try {
				componentValue = component.getAccessor().invoke( record );
			}
			catch( final ReflectiveOperationException e ) {
				throw new IllegalArgumentException( "Failed to read component " + component.getName() + " of " + record.getClass().getName(), e );
			}
			if( componentValue == null ) {
				continue;
			}
			encodeObjectForKey( buffer, tabCount + 1, componentValue, component.getName() );
		}

		appendTabs( buffer, tabCount );
		buffer.append( "</" ).append( key ).append( ">\n" );
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
		final Map<String, Object> map = new LinkedHashMap<>();
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
		final List<Object> list = new ArrayList<>();
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
