package sjip.x;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSPropertyListSerialization;

import sjip.core.x.FoundationPropertyListSerialization;


/**
 * Cross-checks {@link FoundationPropertyListSerialization} against Apple's reference
 * {@code NSPropertyListSerialization} for the value subset exercised by the deployment
 * stats wire format (JavaMonitor's {@code /stats} action and wotaskd's stats parser).
 */
class FoundationPropertyListSerializationTest {

	// ---------------------------------------------------------------------
	// Encoder: byte-for-byte equivalence with NSPropertyListSerialization
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Encoder produces byte-equivalent output to NSPropertyListSerialization")
	class EncoderEquivalence {

		// We feed the *same* NSMutableDictionary / NSMutableArray instance to both encoders
		// to factor out iteration-order differences (NSMutableDictionary is hash-ordered;
		// LinkedHashMap is insertion-ordered). The byte-for-byte parity these tests check
		// is "given identical iteration, the encoders agree on every formatting detail."

		@Test
		void emptyDictionary() {
			assertEncodingsEqual( new NSMutableDictionary<>() );
		}

		@Test
		void emptyArray() {
			assertEncodingsEqual( new NSMutableArray<>() );
		}

		@Test
		void singleStringEntry() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "name", "Hugi" );
			assertEncodingsEqual( input );
		}

		@Test
		void integerValueIsQuoted() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "configuredInstances", Integer.valueOf( 1 ) );
			assertEncodingsEqual( input );
		}

		@Test
		void doubleValueIsQuoted() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "avgIdle", Double.valueOf( 35.882 ) );
			assertEncodingsEqual( input );
		}

		@Test
		void emptyStringValue() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "k", "" );
			assertEncodingsEqual( input );
		}

		@Test
		void singletonArray() {
			final NSMutableArray<Object> input = new NSMutableArray<>();
			input.add( "only" );
			assertEncodingsEqual( input );
		}

		@Test
		void multiElementArray() {
			final NSMutableArray<Object> input = new NSMutableArray<>();
			input.add( "a" );
			input.add( "b" );
			input.add( "c" );
			assertEncodingsEqual( input );
		}

		@Test
		void nestedDictInArray() {
			final NSMutableArray<Object> input = new NSMutableArray<>();
			final NSMutableDictionary<String, Object> entry = new NSMutableDictionary<>();
			entry.put( "applicationName", "Hugi" );
			entry.put( "configuredInstances", Integer.valueOf( 1 ) );
			input.add( entry );
			assertEncodingsEqual( input );
		}

		@Test
		void stringWithDoubleQuoteEscapes() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "k", "a\"b" );
			assertEncodingsEqual( input );
		}

		@Test
		void stringWithBackslashEscapes() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "k", "a\\b" );
			assertEncodingsEqual( input );
		}

		@Test
		void stringWithNewline() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "k", "a\nb" );
			assertEncodingsEqual( input );
		}

		@Test
		void dictNestedInsideDict() {
			// Mirrors the shape wotaskd parses: top-level dict containing sub-dicts
			// keyed by "Sessions" / "Transactions" etc.
			final NSMutableDictionary<String, Object> inner = new NSMutableDictionary<>();
			inner.put( "Transactions", Integer.valueOf( 1593 ) );
			inner.put( "Avg. Transaction Time", Double.valueOf( 0.011 ) );
			final NSMutableDictionary<String, Object> outer = new NSMutableDictionary<>();
			outer.put( "Transactions", inner );
			outer.put( "StartedAt", "2026-05-01" );
			assertEncodingsEqual( outer );
		}

		@Test
		void arrayNestedInsideArray() {
			final NSMutableArray<Object> inner1 = new NSMutableArray<>();
			inner1.add( "a" );
			inner1.add( "b" );
			final NSMutableArray<Object> inner2 = new NSMutableArray<>();
			inner2.add( "c" );
			final NSMutableArray<Object> outer = new NSMutableArray<>();
			outer.add( inner1 );
			outer.add( inner2 );
			assertEncodingsEqual( outer );
		}

		@Test
		void deeplyNestedMixedTree() {
			// Realistic shape for the wotaskd ↔ WOApp protocol: dict of dicts of arrays of strings.
			final NSMutableArray<Object> errorList = new NSMutableArray<>();
			errorList.add( "first error" );
			errorList.add( "second error" );

			final NSMutableDictionary<String, Object> sessions = new NSMutableDictionary<>();
			sessions.put( "Current Active Sessions", Integer.valueOf( 6 ) );
			sessions.put( "Recent Errors", errorList );

			final NSMutableDictionary<String, Object> transactions = new NSMutableDictionary<>();
			transactions.put( "Transactions", Integer.valueOf( 1593 ) );
			transactions.put( "Avg. Transaction Time", Double.valueOf( 0.011 ) );

			final NSMutableDictionary<String, Object> root = new NSMutableDictionary<>();
			root.put( "StartedAt", "2026-05-01T10:00:00" );
			root.put( "Sessions", sessions );
			root.put( "Transactions", transactions );

			assertEncodingsEqual( root );
		}
	}

	// ---------------------------------------------------------------------
	// Decoder: reads back what NSPropertyListSerialization wrote
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Decoder accepts what NSPropertyListSerialization emits")
	class DecoderEquivalence {

		@Test
		void roundTripSimpleDict() {
			final NSMutableDictionary<String, Object> input = new NSMutableDictionary<>();
			input.put( "name", "Hugi" );
			input.put( "count", "3" );
			final String text = NSPropertyListSerialization.stringFromPropertyList( input );

			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( text );
			assertInstanceOf( Map.class, parsed );
			final Map<?, ?> map = (Map<?, ?>)parsed;
			assertEquals( "Hugi", map.get( "name" ) );
			assertEquals( "3", map.get( "count" ) );
		}

		@Test
		void roundTripNestedArrayOfDicts() {
			final NSMutableArray<Object> input = new NSMutableArray<>();
			final NSMutableDictionary<String, Object> first = new NSMutableDictionary<>();
			first.put( "app", "Hugi" );
			first.put( "instances", Integer.valueOf( 1 ) );
			input.add( first );
			final NSMutableDictionary<String, Object> second = new NSMutableDictionary<>();
			second.put( "app", "Rebbi" );
			second.put( "instances", Integer.valueOf( 2 ) );
			input.add( second );

			final String text = NSPropertyListSerialization.stringFromPropertyList( input );
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( text );
			assertInstanceOf( List.class, parsed );
			final List<?> list = (List<?>)parsed;
			assertEquals( 2, list.size() );
			assertEquals( "Hugi", ((Map<?, ?>)list.get( 0 )).get( "app" ) );
			assertEquals( "Rebbi", ((Map<?, ?>)list.get( 1 )).get( "app" ) );
		}

		@Test
		void parsesUnquotedBareTokens() {
			// Real WOApp stats responses include unquoted numeric tokens. The reference
			// parser accepts both — ours must too.
			final String input = "{ count = 42; name = Hugi; }";
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( input );
			assertInstanceOf( Map.class, parsed );
			final Map<?, ?> map = (Map<?, ?>)parsed;
			assertEquals( "42", map.get( "count" ) );
			assertEquals( "Hugi", map.get( "name" ) );
		}

		@Test
		void parsesKeysWithSpaces() {
			// wotaskd stats parsing relies on quoted keys with spaces:
			// "Avg. Transaction Time", "Current Active Sessions", etc.
			final String input = "{ \"Avg. Transaction Time\" = \"0.011\"; }";
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( input );
			final Map<?, ?> map = (Map<?, ?>)parsed;
			assertEquals( "0.011", map.get( "Avg. Transaction Time" ) );
		}

		@Test
		void parsesEscapedQuotesInsideStrings() {
			final String input = "{ k = \"a\\\"b\"; }";
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( input );
			final Map<?, ?> map = (Map<?, ?>)parsed;
			assertEquals( "a\"b", map.get( "k" ) );
		}

		@Test
		void decoderReturnsLinkedHashMap() {
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( "{ k = v; }" );
			assertInstanceOf( LinkedHashMap.class, parsed );
		}

		@Test
		void decoderReturnsArrayList() {
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( "( a, b )" );
			assertInstanceOf( ArrayList.class, parsed );
		}

		@Test
		void nullInputReturnsNull() {
			assertNull( FoundationPropertyListSerialization.propertyListFromString( null ) );
		}

		@Test
		void parsesDeeplyNestedTree() {
			// Verify the parser handles arbitrary recursive nesting, not just the
			// list-of-flat-dicts shape from the production fixture.
			final String input = """
					{
						"Sessions" = {
							"Current Active Sessions" = "6";
							"Recent Errors" = (
								"first error",
								"second error"
							);
						};
						"Transactions" = {
							"Transactions" = "1593";
							"Avg. Transaction Time" = "0.011";
						};
					}""";
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( input );
			final Map<?, ?> root = (Map<?, ?>)parsed;
			final Map<?, ?> sessions = (Map<?, ?>)root.get( "Sessions" );
			assertEquals( "6", sessions.get( "Current Active Sessions" ) );
			final List<?> errors = (List<?>)sessions.get( "Recent Errors" );
			assertEquals( 2, errors.size() );
			assertEquals( "first error", errors.get( 0 ) );
			final Map<?, ?> txs = (Map<?, ?>)root.get( "Transactions" );
			assertEquals( "0.011", txs.get( "Avg. Transaction Time" ) );
		}

		@Test
		void parsesArrayOfArrays() {
			final String input = "( ( a, b ), ( c ), () )";
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( input );
			final List<?> outer = (List<?>)parsed;
			assertEquals( 3, outer.size() );
			assertEquals( 2, ((List<?>)outer.get( 0 )).size() );
			assertEquals( 1, ((List<?>)outer.get( 1 )).size() );
			assertEquals( 0, ((List<?>)outer.get( 2 )).size() );
		}
	}

	// ---------------------------------------------------------------------
	// Real-world fixture: captured /stats output from production JavaMonitor
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Real-world statistics.plist fixture")
	class RealWorldFixture {

		private final String statisticsPlist = readResource( "statistics.plist" );

		@Test
		void parsesProductionStatsFixture() {
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( statisticsPlist );
			assertInstanceOf( List.class, parsed );
			final List<?> apps = (List<?>)parsed;
			assertEquals( 10, apps.size(), "fixture lists 10 apps" );

			final Map<?, ?> first = (Map<?, ?>)apps.get( 0 );
			assertEquals( "Hugi", first.get( "applicationName" ) );
			assertEquals( "1", first.get( "configuredInstances" ) );
		}

		@Test
		void referenceParserAndOursAgree() {
			final Object ours = FoundationPropertyListSerialization.propertyListFromString( statisticsPlist );
			final Object theirs = NSPropertyListSerialization.propertyListFromString( statisticsPlist );
			assertNotNull( ours );
			assertNotNull( theirs );
			assertEquals( asJavaMaps( theirs ), asJavaMaps( ours ),
					"our parser produces the same logical tree as NSPropertyListSerialization" );
		}

		@Test
		void ourParserOutputRoundTripsThroughOurEncoder() {
			// Parse, re-encode, re-parse — all with ours. Confirms the tree shape we
			// produce is one our encoder accepts and that the round-trip is lossless.
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( statisticsPlist );
			final String reEncoded = FoundationPropertyListSerialization.stringFromPropertyList( parsed );
			final Object reParsed = FoundationPropertyListSerialization.propertyListFromString( reEncoded );
			assertEquals( asJavaMaps( reParsed ), asJavaMaps( parsed ) );
		}

		@Test
		void roundTripThroughReferenceIsStableUnderOurs() {
			// And the inverse: parse with the reference, re-encode with ours, parse
			// with ours. Confirms our encoder produces output our parser accepts
			// (and that the reference's parsed tree shape is one our encoder handles).
			final Object parsed = NSPropertyListSerialization.propertyListFromString( statisticsPlist );
			final String reEncoded = FoundationPropertyListSerialization.stringFromPropertyList( parsed );
			final Object reParsed = FoundationPropertyListSerialization.propertyListFromString( reEncoded );
			assertEquals( asJavaMaps( reParsed ), asJavaMaps( parsed ) );
		}
	}

	// ---------------------------------------------------------------------
	// Edges
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Edge cases")
	class Edges {

		@Test
		void encoderRejectsNullValue() {
			final Map<String, Object> input = new LinkedHashMap<>();
			input.put( "k", null );
			assertThrows( IllegalArgumentException.class,
					() -> FoundationPropertyListSerialization.stringFromPropertyList( input ) );
		}

		@Test
		void encoderRejectsUnsupportedType() {
			final Map<String, Object> input = new LinkedHashMap<>();
			input.put( "k", new Object() );
			assertThrows( IllegalArgumentException.class,
					() -> FoundationPropertyListSerialization.stringFromPropertyList( input ) );
		}

		@Test
		void parserRejectsTrailingGarbage() {
			assertThrows( IllegalArgumentException.class,
					() -> FoundationPropertyListSerialization.propertyListFromString( "{ k = v; } extra" ) );
		}

		@Test
		void parserRejectsUnterminatedString() {
			assertThrows( IllegalArgumentException.class,
					() -> FoundationPropertyListSerialization.propertyListFromString( "{ k = \"unterminated" ) );
		}

		@Test
		void parserSkipsLineComments() {
			final String input = "// header comment\n{ k = v; }";
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( input );
			assertEquals( "v", ((Map<?, ?>)parsed).get( "k" ) );
		}

		@Test
		void parserSkipsBlockComments() {
			final String input = "/* comment */ { k = v; }";
			final Object parsed = FoundationPropertyListSerialization.propertyListFromString( input );
			assertEquals( "v", ((Map<?, ?>)parsed).get( "k" ) );
		}

		@Test
		void encoderHandlesBoolean() {
			// Mirrors FoundationCoder: booleans become quoted "YES"/"NO" strings.
			final Map<String, Object> input = new LinkedHashMap<>();
			input.put( "on", Boolean.TRUE );
			input.put( "off", Boolean.FALSE );
			final String out = FoundationPropertyListSerialization.stringFromPropertyList( input );
			assertTrue( out.contains( "\"on\" = \"YES\"" ), "true → YES, got: " + out );
			assertTrue( out.contains( "\"off\" = \"NO\"" ), "false → NO, got: " + out );
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	/**
	 * Encodes the same input with both serializers and asserts byte-for-byte equality.
	 * Feeding identical input ensures iteration order matches, so any divergence is a
	 * real formatting bug rather than a Map-ordering artifact.
	 */
	private static void assertEncodingsEqual( Object input ) {
		final String referenceOut = NSPropertyListSerialization.stringFromPropertyList( input );
		final String ourOut = FoundationPropertyListSerialization.stringFromPropertyList( input );
		assertEquals( referenceOut, ourOut );
	}

	/**
	 * Collapses {@code NSDictionary}/{@code NSArray} to {@code LinkedHashMap}/{@code ArrayList}
	 * so {@code equals} compares logically, not by Foundation identity.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object asJavaMaps( Object value ) {
		if( value instanceof Map<?, ?> m ) {
			final Map<Object, Object> out = new LinkedHashMap<>();
			for( Map.Entry e : m.entrySet() ) {
				out.put( e.getKey(), asJavaMaps( e.getValue() ) );
			}
			return out;
		}
		if( value instanceof List<?> l ) {
			final List<Object> out = new ArrayList<>();
			for( Object o : l ) {
				out.add( asJavaMaps( o ) );
			}
			return out;
		}
		return value;
	}

	private static String readResource( String name ) {
		final String path = "/x/" + name;
		try( InputStream in = FoundationPropertyListSerializationTest.class.getResourceAsStream( path ) ) {
			if( in == null ) {
				throw new IllegalStateException( "resource not found: " + path );
			}
			return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
		}
		catch( IOException e ) {
			throw new RuntimeException( e );
		}
	}
}
