package sjip.x;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;

/**
 * Reads and writes the OpenStep ASCII property-list format used by the WebObjects deployment
 * tooling, matching the on-the-wire output of {@code NSPropertyListSerialization} byte-for-byte
 * for the value subset {@code wotaskd} and {@code JavaMonitor} actually exercise. Unlike the
 * reference, this implementation only leans on the JDK; the dependency on Apple's Foundation
 * has been removed.
 *
 * <h2>Format</h2>
 * <p>OpenStep ASCII plists, not the XML {@code <plist version="1.0">} variant. Dictionaries
 * render as {@code { key = value; ... }}; arrays as {@code ( elem, elem )}. Strings are
 * always double-quoted, even simple identifiers, mirroring {@code NSPropertyListSerialization}'s
 * write behaviour.
 *
 * <h2>Supported values</h2>
 * <p>On encode the input tree may contain:
 * <ul>
 *   <li>{@link Map} → {@code { key = value; }} blocks. Iteration order is the {@code Map}'s
 *       own iteration order, so a {@link java.util.LinkedHashMap} is recommended.</li>
 *   <li>{@link List} → {@code ( elem, elem )} blocks.</li>
 *   <li>{@link String} → double-quoted, with {@code \} {@code "} {@code \n} {@code \t}
 *       {@code \r} backslash-escaped.</li>
 *   <li>{@link Number} → rendered via {@link Object#toString()}, then double-quoted (the
 *       reference encoder also stringifies numbers).</li>
 *   <li>{@link Boolean} → emitted lossily as the literal quoted string {@code "YES"} /
 *       {@code "NO"}, matching the reference and matching {@link FoundationCoder}.</li>
 * </ul>
 * <p>{@code null} is rejected — OpenStep plists have no null type.
 *
 * <h2>Decode return types</h2>
 * <p>The parser returns {@link NSMutableDictionary} / {@link NSMutableArray} for dictionaries
 * and arrays so existing call sites that cast to those Foundation types keep working. The
 * intent is to flip these to {@link LinkedHashMap} / {@link ArrayList} once the data-model
 * migration off Foundation is complete — same plan as {@link FoundationCoder}.
 *
 * <h2>Parser permissiveness</h2>
 * <p>Although the writer always quotes, the parser accepts both quoted strings and bare
 * identifier tokens (a-z, A-Z, 0-9, {@code _ . $ / : -}). This is needed because real
 * WebObjects applications emit stats plists with unquoted numeric and identifier tokens.
 *
 * <h2>Lossy round-tripping you should know about</h2>
 * <ul>
 *   <li><b>Everything is a String on the wire.</b> Numbers and booleans both serialize as
 *       quoted strings and decode as {@link String}. Callers that need an {@code int} or
 *       {@code boolean} parse the result themselves — same as with the reference parser.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>All entry points are static. No mutable shared state.
 */
public class FoundationPropertyListSerialization {

	private FoundationPropertyListSerialization() {}

	// ---------------------------------------------------------------------
	// Encoding
	// ---------------------------------------------------------------------

	/**
	 * Renders {@code object} as an OpenStep ASCII property list, matching the output of
	 * {@code NSPropertyListSerialization.stringFromPropertyList} byte-for-byte for the value
	 * subset listed on the class.
	 *
	 * @throws IllegalArgumentException if the input tree contains a value whose runtime type
	 *                                  is not one of the supported types, or contains {@code null}.
	 */
	public static String stringFromPropertyList( Object object ) {
		final StringBuilder buffer = new StringBuilder( 1024 );
		encode( buffer, 0, object );
		return buffer.toString();
	}

	private static void encode( StringBuilder buffer, int indent, Object value ) {
		if( value == null ) {
			throw new IllegalArgumentException( "null is not a legal property-list value" );
		}
		if( value instanceof Map<?, ?> m ) {
			encodeDictionary( buffer, indent, m );
			return;
		}
		if( value instanceof List<?> l ) {
			encodeArray( buffer, indent, l );
			return;
		}
		if( value instanceof Boolean b ) {
			encodeQuotedString( buffer, b ? "YES" : "NO" );
			return;
		}
		if( value instanceof Number n ) {
			encodeQuotedString( buffer, n.toString() );
			return;
		}
		if( value instanceof String s ) {
			encodeQuotedString( buffer, s );
			return;
		}
		throw new IllegalArgumentException(
				value.getClass().getName() + " is not a type that FoundationPropertyListSerialization can encode" );
	}

	private static void encodeDictionary( StringBuilder buffer, int indent, Map<?, ?> map ) {
		if( map.isEmpty() ) {
			buffer.append( "{}" );
			return;
		}
		buffer.append( "{\n" );
		for( Map.Entry<?, ?> entry : map.entrySet() ) {
			appendTabs( buffer, indent + 1 );
			encodeQuotedString( buffer, entry.getKey().toString() );
			buffer.append( " = " );
			encode( buffer, indent + 1, entry.getValue() );
			buffer.append( ";\n" );
		}
		appendTabs( buffer, indent );
		buffer.append( '}' );
	}

	private static void encodeArray( StringBuilder buffer, int indent, List<?> list ) {
		if( list.isEmpty() ) {
			buffer.append( "()" );
			return;
		}
		buffer.append( "(\n" );
		final int last = list.size() - 1;
		for( int i = 0; i < list.size(); i++ ) {
			appendTabs( buffer, indent + 1 );
			encode( buffer, indent + 1, list.get( i ) );
			if( i < last ) {
				buffer.append( ',' );
			}
			buffer.append( '\n' );
		}
		appendTabs( buffer, indent );
		buffer.append( ')' );
	}

	private static void encodeQuotedString( StringBuilder buffer, String s ) {
		buffer.append( '"' );
		final int length = s.length();
		for( int i = 0; i < length; i++ ) {
			final char c = s.charAt( i );
			switch( c ) {
				case '\\' -> buffer.append( "\\\\" );
				case '"' -> buffer.append( "\\\"" );
				case '\n' -> buffer.append( "\\n" );
				case '\r' -> buffer.append( "\\r" );
				case '\t' -> buffer.append( "\\t" );
				default -> buffer.append( c );
			}
		}
		buffer.append( '"' );
	}

	private static void appendTabs( StringBuilder buffer, int indent ) {
		for( int i = 0; i < indent; i++ ) {
			buffer.append( '\t' );
		}
	}

	// ---------------------------------------------------------------------
	// Decoding
	// ---------------------------------------------------------------------

	/**
	 * Parses an OpenStep ASCII property list and returns the resulting tree. Dictionaries
	 * and arrays come back as {@link NSMutableDictionary} / {@link NSMutableArray} (see the
	 * note on the class), strings (quoted or bare) come back as {@link String}.
	 *
	 * @throws IllegalArgumentException if {@code text} is not a well-formed plist.
	 */
	public static Object propertyListFromString( String text ) {
		if( text == null ) {
			return null;
		}
		final Parser parser = new Parser( text );
		parser.skipWhitespaceAndComments();
		final Object result = parser.parseValue();
		parser.skipWhitespaceAndComments();
		if( !parser.atEnd() ) {
			throw new IllegalArgumentException(
					"Trailing content after root value at offset " + parser.position() );
		}
		return result;
	}

	private static final class Parser {

		private final String src;
		private int pos;

		Parser( String src ) {
			this.src = src;
			this.pos = 0;
		}

		boolean atEnd() {
			return pos >= src.length();
		}

		int position() {
			return pos;
		}

		Object parseValue() {
			skipWhitespaceAndComments();
			if( atEnd() ) {
				throw new IllegalArgumentException( "Unexpected end of input" );
			}
			final char c = src.charAt( pos );
			return switch( c ) {
				case '{' -> parseDictionary();
				case '(' -> parseArray();
				case '"' -> parseQuotedString();
				default -> parseBareToken();
			};
		}

		Map<String, Object> parseDictionary() {
			expect( '{' );
			final Map<String, Object> map = new NSMutableDictionary<>();
			skipWhitespaceAndComments();
			while( !atEnd() && src.charAt( pos ) != '}' ) {
				final Object keyObj = parseValue();
				if( !(keyObj instanceof String) ) {
					throw new IllegalArgumentException(
							"Dictionary key must be a string at offset " + pos );
				}
				final String key = (String)keyObj;
				skipWhitespaceAndComments();
				expect( '=' );
				skipWhitespaceAndComments();
				final Object value = parseValue();
				map.put( key, value );
				skipWhitespaceAndComments();
				if( !atEnd() && src.charAt( pos ) == ';' ) {
					pos++;
					skipWhitespaceAndComments();
				}
				else {
					// Some emitters omit the trailing semicolon on the last entry.
					break;
				}
			}
			expect( '}' );
			return map;
		}

		List<Object> parseArray() {
			expect( '(' );
			final List<Object> list = new NSMutableArray<>();
			skipWhitespaceAndComments();
			while( !atEnd() && src.charAt( pos ) != ')' ) {
				list.add( parseValue() );
				skipWhitespaceAndComments();
				if( !atEnd() && src.charAt( pos ) == ',' ) {
					pos++;
					skipWhitespaceAndComments();
				}
				else {
					break;
				}
			}
			expect( ')' );
			return list;
		}

		String parseQuotedString() {
			expect( '"' );
			final StringBuilder out = new StringBuilder();
			while( !atEnd() ) {
				final char c = src.charAt( pos++ );
				if( c == '"' ) {
					return out.toString();
				}
				if( c == '\\' ) {
					if( atEnd() ) {
						throw new IllegalArgumentException(
								"Trailing backslash in quoted string at offset " + pos );
					}
					final char esc = src.charAt( pos++ );
					switch( esc ) {
						case 'n' -> out.append( '\n' );
						case 'r' -> out.append( '\r' );
						case 't' -> out.append( '\t' );
						case '\\' -> out.append( '\\' );
						case '"' -> out.append( '"' );
						default -> out.append( esc );
					}
					continue;
				}
				out.append( c );
			}
			throw new IllegalArgumentException( "Unterminated quoted string" );
		}

		String parseBareToken() {
			final int start = pos;
			while( !atEnd() && isBareTokenChar( src.charAt( pos ) ) ) {
				pos++;
			}
			if( pos == start ) {
				throw new IllegalArgumentException(
						"Unexpected character '" + src.charAt( pos ) + "' at offset " + pos );
			}
			return src.substring( start, pos );
		}

		private static boolean isBareTokenChar( char c ) {
			return (c >= 'a' && c <= 'z')
					|| (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9')
					|| c == '_' || c == '.' || c == '$' || c == '/' || c == ':' || c == '-' || c == '+';
		}

		void expect( char c ) {
			if( atEnd() || src.charAt( pos ) != c ) {
				throw new IllegalArgumentException(
						"Expected '" + c + "' at offset " + pos
								+ (atEnd() ? " (end of input)" : " but found '" + src.charAt( pos ) + "'") );
			}
			pos++;
		}

		void skipWhitespaceAndComments() {
			while( !atEnd() ) {
				final char c = src.charAt( pos );
				if( Character.isWhitespace( c ) ) {
					pos++;
					continue;
				}
				if( c == '/' && pos + 1 < src.length() ) {
					final char next = src.charAt( pos + 1 );
					if( next == '/' ) {
						pos += 2;
						while( !atEnd() && src.charAt( pos ) != '\n' ) {
							pos++;
						}
						continue;
					}
					if( next == '*' ) {
						pos += 2;
						while( pos + 1 < src.length()
								&& !(src.charAt( pos ) == '*' && src.charAt( pos + 1 ) == '/') ) {
							pos++;
						}
						if( pos + 1 < src.length() ) {
							pos += 2;
						}
						continue;
					}
				}
				return;
			}
		}
	}
}
