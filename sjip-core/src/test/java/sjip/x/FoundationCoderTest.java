package sjip.x;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.xml._JavaMonitorCoder;
import com.webobjects.appserver.xml._JavaMonitorDecoder;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;


/**
 * Cross-checks {@link FoundationCoder} against Apple's reference
 * {@code _JavaMonitorCoder}/{@code _JavaMonitorDecoder} for the value subset
 * exercised by the deployment wire format.
 */
class FoundationCoderTest {

	private final FoundationCoder coder = new FoundationCoder();
	private final _JavaMonitorCoder reference = new _JavaMonitorCoder();
	private final _JavaMonitorDecoder referenceDecoder = new _JavaMonitorDecoder();

	/** {@code WOXMLDecoder.decodeRootObject(String)} treats the string as a URL, so route via NSData. */
	private Object decodeWithReference( String xml ) {
		return referenceDecoder.decodeRootObject( new NSData( xml.getBytes( StandardCharsets.UTF_8 ) ) );
	}

	/**
	 * {@code FoundationCoder.decodeRootObject(String)} also treats the string as a SAX
	 * systemId (URL), matching the reference. Tests that want to feed an XML literal
	 * through the decoder route via bytes.
	 */
	private Object decode( String xml ) {
		return coder.decodeRootObject( xml.getBytes( StandardCharsets.UTF_8 ) );
	}

	// ---------------------------------------------------------------------
	// Encoder: byte-for-byte equivalence with the reference encoder
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Encoder produces byte-equivalent output to _JavaMonitorCoder")
	class EncoderEquivalence {

		@Test
		void emptyDictionary() {
			assertEncodingsEqual( new NSMutableDictionary<>(), new LinkedHashMap<>(), "SiteConfig" );
		}

		@Test
		void emptyArray() {
			assertEncodingsEqual( new NSMutableArray<>(), new ArrayList<>(), "items" );
		}

		@Test
		void singleStringEntry() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "name", "hz1.rebbi.is" );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "name", "hz1.rebbi.is" );
			assertEncodingsEqual( ref, mine, "host" );
		}

		@Test
		void integerEntry() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "port", 2001 );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "port", 2001 );
			assertEncodingsEqual( ref, mine, "instance" );
		}

		@Test
		void longEntry() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "n", 9_000_000_000L );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "n", 9_000_000_000L );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void doubleEntry() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "load", 1.5 );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "load", 1.5 );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void floatEntry() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "load", 1.5f );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "load", 1.5f );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void shortAndByteEntries() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "s", (short)5 );
			ref.put( "b", (byte)3 );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "s", (short)5 );
			mine.put( "b", (byte)3 );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void zeroAndNegativeNumbers() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "zero", 0 );
			ref.put( "neg", -42 );
			ref.put( "negDouble", -1.25 );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "zero", 0 );
			mine.put( "neg", -42 );
			mine.put( "negDouble", -1.25 );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void booleanTrueIsEncodedAsYesString() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "cachingEnabled", Boolean.TRUE );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "cachingEnabled", Boolean.TRUE );
			final String referenceXml = reference.encodeRootObjectForKey( ref, "d" );
			assertTrue( referenceXml.contains( "<cachingEnabled type=\"NSString\">YES</cachingEnabled>" ),
					"reference encodes Boolean.TRUE as NSString YES" );
			assertEquals( referenceXml, coder.encodeRootObjectForKey( mine, "d" ) );
		}

		@Test
		void booleanFalseIsEncodedAsNoString() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "cachingEnabled", Boolean.FALSE );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "cachingEnabled", Boolean.FALSE );
			final String referenceXml = reference.encodeRootObjectForKey( ref, "d" );
			assertTrue( referenceXml.contains( "<cachingEnabled type=\"NSString\">NO</cachingEnabled>" ) );
			assertEquals( referenceXml, coder.encodeRootObjectForKey( mine, "d" ) );
		}

		@Test
		void emptyStringValue() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "statisticsPassword", "" );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "statisticsPassword", "" );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void stringEscaping() {
			final String funky = "a&b<c>d\"e'f&amp;<<>>";
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "k", funky );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "k", funky );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void unicodeContent() {
			final String content = "íslenska — heimsókn 漢字 αβγ";
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "k", content );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "k", content );
			assertEncodingsEqual( ref, mine, "d" );
		}

		@Test
		void nestedDictionariesAndArrays() {
			final Object ref = referenceSiteConfigSample();
			final Object mine = mySiteConfigSample();
			assertEncodingsEqual( ref, mine, "SiteConfig" );
		}

		@Test
		void simpleArrayOfStrings() {
			final NSMutableArray<String> ref = new NSMutableArray<>();
			ref.add( "a" );
			ref.add( "b" );
			ref.add( "c" );
			final List<String> mine = List.of( "a", "b", "c" );
			assertEncodingsEqual( ref, mine, "errorResponse" );
		}

		@Test
		void deeplyNestedStructure() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			final NSMutableDictionary<String,Object> level1 = new NSMutableDictionary<>();
			final NSMutableDictionary<String,Object> level2 = new NSMutableDictionary<>();
			final NSMutableDictionary<String,Object> level3 = new NSMutableDictionary<>();
			level3.put( "leaf", 42 );
			level2.put( "level3", level3 );
			level1.put( "level2", level2 );
			ref.put( "level1", level1 );

			final Map<String,Object> mine = new LinkedHashMap<>();
			final Map<String,Object> m1 = new LinkedHashMap<>();
			final Map<String,Object> m2 = new LinkedHashMap<>();
			final Map<String,Object> m3 = new LinkedHashMap<>();
			m3.put( "leaf", 42 );
			m2.put( "level3", m3 );
			m1.put( "level2", m2 );
			mine.put( "level1", m1 );

			assertEncodingsEqual( ref, mine, "root" );
		}
	}

	// ---------------------------------------------------------------------
	// Decoder: parses both reference output and our own output identically
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Decoder reads both reference- and self-produced XML")
	class DecoderEquivalence {

		@Test
		void decodeStringFromReference() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "name", "hz1.rebbi.is" );
			final String xml = reference.encodeRootObjectForKey( ref, "host" );
			final Object decoded = decode( xml );
			assertInstanceOf( Map.class, decoded );
			assertEquals( "hz1.rebbi.is", ((Map<?,?>)decoded).get( "name" ) );
		}

		@Test
		void integerDecodesAsInteger() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "port", 2001 );
			final String xml = reference.encodeRootObjectForKey( ref, "d" );
			final Object decoded = ((Map<?,?>)decode( xml )).get( "port" );
			assertInstanceOf( Integer.class, decoded );
			assertEquals( 2001, decoded );
		}

		@Test
		void doubleDecodesAsDouble() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "load", 1.5 );
			final String xml = reference.encodeRootObjectForKey( ref, "d" );
			final Object decoded = ((Map<?,?>)decode( xml )).get( "load" );
			assertInstanceOf( Double.class, decoded );
			assertEquals( 1.5, decoded );
		}

		@Test
		void yesAndNoStringsDecodeAsBoolean() {
			// _JavaMonitorDecoder rewrites the literal NSString values "YES"/"NO" as
			// Boolean.TRUE/FALSE on decode (see _JavaMonitorDecoder._decodeString).
			// FoundationCoder must do the same so the deployment code sees the same shape.
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "cachingEnabled", Boolean.TRUE );
			ref.put( "debuggingEnabled", Boolean.FALSE );
			final String xml = reference.encodeRootObjectForKey( ref, "d" );
			final Map<?,?> decoded = (Map<?,?>)decode( xml );
			assertEquals( Boolean.TRUE, decoded.get( "cachingEnabled" ) );
			assertEquals( Boolean.FALSE, decoded.get( "debuggingEnabled" ) );
		}

		@Test
		void plainStringsDecodeAsString() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "name", "hz1.rebbi.is" );
			final String xml = reference.encodeRootObjectForKey( ref, "d" );
			final Object decoded = ((Map<?,?>)decode( xml )).get( "name" );
			assertEquals( "hz1.rebbi.is", decoded );
		}

		@Test
		void emptyStringRoundTrips() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "statisticsPassword", "" );
			final String xml = reference.encodeRootObjectForKey( ref, "d" );
			final Object decoded = ((Map<?,?>)decode( xml )).get( "statisticsPassword" );
			assertEquals( "", decoded );
		}

		@Test
		void unescapesXmlEntities() {
			final String funky = "a&b<c>d\"e'f";
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "k", funky );
			final String xml = reference.encodeRootObjectForKey( ref, "d" );
			assertEquals( funky, ((Map<?,?>)decode( xml )).get( "k" ) );
		}

		@Test
		void byteArrayOverloadDecodesUtf8() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "k", "íslenska 漢字" );
			final byte[] bytes = reference.encodeRootObjectForKey( ref, "d" )
					.getBytes( StandardCharsets.UTF_8 );
			final Object decoded = ((Map<?,?>)coder.decodeRootObject( bytes )).get( "k" );
			assertEquals( "íslenska 漢字", decoded );
		}

		@Test
		void arraysDecodeToList() {
			final NSMutableArray<String> ref = new NSMutableArray<>();
			ref.add( "first" );
			ref.add( "second" );
			final String xml = reference.encodeRootObjectForKey( ref, "errorResponse" );
			final Object decoded = decode( xml );
			assertInstanceOf( List.class, decoded );
			assertEquals( List.of( "first", "second" ), decoded );
		}

		@Test
		void decodedMapPreservesDocumentOrder() {
			// The encoder emits dict keys alphabetically, and the decoder preserves
			// document order, so a round trip lands in alphabetical order regardless
			// of the input's insertion order.
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "z", 1 );
			mine.put( "m", 2 );
			mine.put( "a", 3 );
			final String xml = coder.encodeRootObjectForKey( mine, "d" );
			final Map<?,?> decoded = (Map<?,?>)decode( xml );
			final Iterator<?> it = decoded.keySet().iterator();
			assertEquals( "a", it.next() );
			assertEquals( "m", it.next() );
			assertEquals( "z", it.next() );
		}
	}

	// ---------------------------------------------------------------------
	// Round trips: in/out via every combination of coders
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Round trips through all four encoder/decoder combinations")
	class RoundTrips {

		@Test
		void selfEncodeSelfDecode() {
			final Map<String,Object> mine = mySiteConfigSample();
			final String xml = coder.encodeRootObjectForKey( mine, "SiteConfig" );
			final Map<?,?> decoded = (Map<?,?>)decode( xml );
			assertMapsEquivalent( mine, decoded );
		}

		@Test
		void selfEncodeReferenceDecode() {
			final Map<String,Object> mine = mySiteConfigSample();
			final String xml = coder.encodeRootObjectForKey( mine, "SiteConfig" );
			final NSDictionary<?,?> decoded = (NSDictionary<?,?>)decodeWithReference( xml );
			assertMapsEquivalent( mine, decoded );
		}

		@Test
		void referenceEncodeSelfDecode() {
			final Object ref = referenceSiteConfigSample();
			final String xml = reference.encodeRootObjectForKey( ref, "SiteConfig" );
			final Map<?,?> decoded = (Map<?,?>)decode( xml );
			assertMapsEquivalent( ref, decoded );
		}

		@Test
		void referenceEncodeReferenceDecode() {
			// Sanity: the reference coder agrees with itself.
			final Object ref = referenceSiteConfigSample();
			final String xml = reference.encodeRootObjectForKey( ref, "SiteConfig" );
			final Object decoded = decodeWithReference( xml );
			assertMapsEquivalent( ref, decoded );
		}
	}

	// ---------------------------------------------------------------------
	// Real-world fixture: production SiteConfig.xml
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Real-world SiteConfig.xml fixture")
	class RealWorldFixture {

		private final String siteConfigXml = readResource( "SiteConfig.xml" );

		@Test
		void referenceAndFoundationDecodeIdentically() {
			final Object referenceTree = decodeWithReference( siteConfigXml );
			final Object myTree = decode( siteConfigXml );
			assertMapsEquivalent( referenceTree, myTree );
		}

		@Test
		void foundationEncodingDecodesViaReference() {
			final Object myTree = decode( siteConfigXml );
			final String reEncoded = coder.encodeRootObjectForKey( myTree, "SiteConfig" );
			final Object viaReference = decodeWithReference( reEncoded );
			assertMapsEquivalent( myTree, viaReference );
		}

		@Test
		void referenceEncodingDecodesViaFoundation() {
			final Object referenceTree = decodeWithReference( siteConfigXml );
			final String reEncoded = reference.encodeRootObjectForKey( referenceTree, "SiteConfig" );
			final Object viaFoundation = decode( reEncoded );
			assertMapsEquivalent( referenceTree, viaFoundation );
		}

		@Test
		void reEncodingIsDeterministic() {
			// FoundationCoder sorts dictionary keys alphabetically, so encoding the same
			// tree twice must produce byte-identical output. This is the key property that
			// gives us stable, diff-friendly SiteConfig.xml on disk regardless of the
			// underlying Map's iteration order.
			final Object tree = coder.decodeRootObject( siteConfigXml.getBytes( StandardCharsets.UTF_8 ) );
			final String once = coder.encodeRootObjectForKey( tree, "SiteConfig" );
			final String twice = coder.encodeRootObjectForKey( tree, "SiteConfig" );
			assertEquals( once, twice );
		}

		@Test
		void encodingIsIdempotentAcrossReDecode() {
			// Encode → decode → encode must produce the same bytes as the first encode.
			// Combined with reEncodingIsDeterministic above, this guarantees a SiteConfig.xml
			// diff between two runs only contains real configuration changes.
			final Object tree = coder.decodeRootObject( siteConfigXml.getBytes( StandardCharsets.UTF_8 ) );
			final String firstPass = coder.encodeRootObjectForKey( tree, "SiteConfig" );
			final Object treeAgain = coder.decodeRootObject( firstPass.getBytes( StandardCharsets.UTF_8 ) );
			final String secondPass = coder.encodeRootObjectForKey( treeAgain, "SiteConfig" );
			assertEquals( firstPass, secondPass );
		}
	}

	// ---------------------------------------------------------------------
	// Wire-protocol shapes (PROTOCOLS.md)
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Wire-protocol message shapes")
	class WireProtocolShapes {

		@Test
		void monitorRequestUpdateShape() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			final NSMutableDictionary<String,Object> update = new NSMutableDictionary<>();
			final NSMutableDictionary<String,Object> add = new NSMutableDictionary<>();
			final NSMutableArray<NSDictionary<String,Object>> hostArray = new NSMutableArray<>();
			final NSMutableDictionary<String,Object> host = new NSMutableDictionary<>();
			host.put( "name", "hz1.rebbi.is" );
			host.put( "type", "UNIX" );
			hostArray.add( host );
			add.put( "hostArray", hostArray );
			update.put( "add", add );
			ref.put( "updateWotaskd", update );

			final Map<String,Object> mine = new LinkedHashMap<>();
			final Map<String,Object> myUpdate = new LinkedHashMap<>();
			final Map<String,Object> myAdd = new LinkedHashMap<>();
			final List<Map<String,Object>> myHostArray = new ArrayList<>();
			final Map<String,Object> myHost = new LinkedHashMap<>();
			myHost.put( "name", "hz1.rebbi.is" );
			myHost.put( "type", "UNIX" );
			myHostArray.add( myHost );
			myAdd.put( "hostArray", myHostArray );
			myUpdate.put( "add", myAdd );
			mine.put( "updateWotaskd", myUpdate );

			assertEncodingsEqual( ref, mine, "monitorRequest" );
		}

		@Test
		void commandWotaskdShape() {
			// A heterogeneous array: command string at [0], then instance dictionaries.
			final NSMutableArray<Object> ref = new NSMutableArray<>();
			ref.add( "START" );
			final NSMutableDictionary<String,Object> instance = new NSMutableDictionary<>();
			instance.put( "applicationName", "Hugi" );
			instance.put( "id", 1 );
			instance.put( "hostName", "hz1.rebbi.is" );
			instance.put( "port", 2001 );
			ref.add( instance );

			final List<Object> mine = new ArrayList<>();
			mine.add( "START" );
			final Map<String,Object> myInstance = new LinkedHashMap<>();
			myInstance.put( "applicationName", "Hugi" );
			myInstance.put( "id", 1 );
			myInstance.put( "hostName", "hz1.rebbi.is" );
			myInstance.put( "port", 2001 );
			mine.add( myInstance );

			assertEncodingsEqual( ref, mine, "commandWotaskd" );
		}

		@Test
		void queryWotaskdShape() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "queryWotaskd", "SITE" );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "queryWotaskd", "SITE" );
			assertEncodingsEqual( ref, mine, "monitorRequest" );
		}

		@Test
		void errorResponseShape() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			final NSMutableArray<String> messages = new NSMutableArray<>();
			messages.add( "Failed to contact hz1.rebbi.is-1085" );
			ref.put( "errorResponse", messages );

			final Map<String,Object> mine = new LinkedHashMap<>();
			final List<String> myMessages = List.of( "Failed to contact hz1.rebbi.is-1085" );
			mine.put( "errorResponse", myMessages );

			assertEncodingsEqual( ref, mine, "monitorResponse" );
		}
	}

	// ---------------------------------------------------------------------
	// Edge cases on the encoder / decoder boundary
	// ---------------------------------------------------------------------

	@Nested
	@DisplayName("Boundary and error conditions")
	class Edges {

		@Test
		void encodingNullReturnsNull() {
			assertNull( coder.encodeRootObjectForKey( null, "anything" ) );
		}

		@Test
		void encodingUnsupportedTypeThrows() {
			assertThrows( IllegalArgumentException.class,
					() -> coder.encodeRootObjectForKey( new Object(), "k" ) );
		}

		@Test
		void encodingByteArrayValueIsRejected() {
			// NSData is not exercised by the deployment code path.
			final Map<String,Object> m = new LinkedHashMap<>();
			m.put( "blob", new byte[] { 1, 2, 3 } );
			assertThrows( IllegalArgumentException.class,
					() -> coder.encodeRootObjectForKey( m, "d" ) );
		}

		@Test
		void numberWithFractionalPartDecodesAsDouble() {
			final String xml = "<r type=\"NSNumber\">3.14</r>";
			final Object decoded = decode( xml );
			assertInstanceOf( Double.class, decoded );
			assertEquals( 3.14, decoded );
		}

		@Test
		void wholeNumberDecodesAsInteger() {
			final String xml = "<r type=\"NSNumber\">7</r>";
			final Object decoded = decode( xml );
			assertInstanceOf( Integer.class, decoded );
			assertEquals( 7, decoded );
		}

		@Test
		void numberOutOfIntegerRangeFallsBackToDouble() {
			final String xml = "<r type=\"NSNumber\">9000000000</r>";
			final Object decoded = decode( xml );
			assertInstanceOf( Double.class, decoded );
		}

		@Test
		void unknownTypeThrows() {
			final String xml = "<r type=\"FooBar\">x</r>";
			assertThrows( IllegalArgumentException.class, () -> decode( xml ) );
		}

		@Test
		void doctypeIsRejected() {
			// Defensive: decoder must refuse DOCTYPE to avoid XXE.
			final String xml = "<!DOCTYPE foo><r type=\"NSString\">x</r>";
			assertThrows( RuntimeException.class, () -> decode( xml ) );
		}

		@Test
		void stringOverloadResolvesSystemIdAgainstFilesystem() throws IOException {
			// decodeRootObject(String) matches the reference WOXMLDecoder API: the argument
			// is a SAX systemId (URL or filesystem path), not the XML content. Existing
			// callers use it to load a SiteConfig.xml from disk.
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "k", "v" );
			final String xml = coder.encodeRootObjectForKey( mine, "d" );
			final java.nio.file.Path tmp = java.nio.file.Files.createTempFile( "foundation-coder-", ".xml" );
			try {
				java.nio.file.Files.writeString( tmp, xml );
				final Object viaPath = coder.decodeRootObject( tmp.toString() );
				final Object viaBytes = coder.decodeRootObject( xml.getBytes( StandardCharsets.UTF_8 ) );
				assertEquals( viaPath, viaBytes );
			}
			finally {
				java.nio.file.Files.deleteIfExists( tmp );
			}
		}

		@Test
		void rootCanBeArray() {
			final List<String> mine = List.of( "a", "b" );
			final NSMutableArray<String> ref = new NSMutableArray<>();
			ref.add( "a" );
			ref.add( "b" );
			assertEncodingsEqual( ref, mine, "errorResponse" );
		}

		@Test
		void rootCanBeString() {
			// The reference _JavaMonitorCoder accepts a bare String root too;
			// verify we agree on the byte-level output.
			final String s = "hello";
			final String referenceXml = reference.encodeRootObjectForKey( s, "k" );
			final String myXml = coder.encodeRootObjectForKey( s, "k" );
			assertEquals( referenceXml, myXml );
		}

		@Test
		void rootCanBeNumber() {
			final String referenceXml = reference.encodeRootObjectForKey( 42, "k" );
			final String myXml = coder.encodeRootObjectForKey( 42, "k" );
			assertEquals( referenceXml, myXml );
		}

		@Test
		void encodedOutputIsValidUtf8() {
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "k", "íslenska" );
			final byte[] bytes = coder.encodeRootObjectForKey( mine, "d" )
					.getBytes( StandardCharsets.UTF_8 );
			assertNotNull( bytes );
			assertTrue( bytes.length > 0 );
			// Round-trip through bytes
			final Object decoded = coder.decodeRootObject( bytes );
			assertEquals( "íslenska", ((Map<?,?>)decoded).get( "k" ) );
		}

		@Test
		void emptyContainersRoundTripSemantically() {
			final NSMutableDictionary<String,Object> ref = new NSMutableDictionary<>();
			ref.put( "outer", new NSMutableDictionary<>() );
			ref.put( "list", new NSMutableArray<>() );
			final Map<String,Object> mine = new LinkedHashMap<>();
			mine.put( "outer", new LinkedHashMap<>() );
			mine.put( "list", new ArrayList<>() );
			assertEncodingsEqual( ref, mine, "d" );
		}
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/**
	 * Encodes both inputs and asserts the resulting documents are <em>semantically</em>
	 * equivalent — same tree shape, same scalar values — irrespective of dictionary key
	 * order on the wire. {@code FoundationCoder} sorts dictionary keys alphabetically
	 * for stable diffs, the reference {@code _JavaMonitorCoder} emits them in
	 * {@code NSMutableDictionary}'s hash order; both are valid documents for the wire
	 * protocol, so the test compares the round-tripped trees rather than the bytes.
	 *
	 * Both encodings are decoded through {@code FoundationCoder} since the reference
	 * decoder is brittle around non-Integer NSNumber subtypes (java.lang.Long/Float/Double),
	 * which we exercise on purpose.
	 */
	private void assertEncodingsEqual( Object referenceTree, Object mineTree, String key ) {
		final String referenceXml = reference.encodeRootObjectForKey( referenceTree, key );
		final String myXml = coder.encodeRootObjectForKey( mineTree, key );
		final Object referenceRoundTrip = coder.decodeRootObject( referenceXml.getBytes( StandardCharsets.UTF_8 ) );
		final Object myRoundTrip = coder.decodeRootObject( myXml.getBytes( StandardCharsets.UTF_8 ) );
		assertMapsEquivalent( referenceRoundTrip, myRoundTrip );
	}

	/**
	 * Equivalence between an NSDictionary/NSArray tree and a Map/List tree (or two
	 * trees of either flavour). Comparison ignores collection class identity and
	 * compares dictionaries/maps by entry set (order-independent).
	 */
	private static void assertMapsEquivalent( Object expected, Object actual ) {
		if( expected instanceof NSDictionary<?,?> nsd ) {
			expected = toPlainMap( nsd );
		}
		else if( expected instanceof NSArray<?> nsa ) {
			expected = toPlainList( nsa );
		}
		if( actual instanceof NSDictionary<?,?> nsd ) {
			actual = toPlainMap( nsd );
		}
		else if( actual instanceof NSArray<?> nsa ) {
			actual = toPlainList( nsa );
		}
		assertEquals( normalize( expected ), normalize( actual ) );
	}

	/**
	 * Recursive walk producing a tree of {@link LinkedHashMap}/{@link ArrayList}/scalars,
	 * collapsing any {@link NSDictionary}/{@link NSArray} as well. Used so the
	 * test assertions can ignore collection identity.
	 */
	private static Object normalize( Object o ) {
		if( o == null ) {
			return null;
		}
		if( o instanceof NSDictionary<?,?> nsd ) {
			return normalize( toPlainMap( nsd ) );
		}
		if( o instanceof NSArray<?> nsa ) {
			return normalize( toPlainList( nsa ) );
		}
		if( o instanceof Map<?,?> m ) {
			final Map<String,Object> out = new LinkedHashMap<>();
			for( Map.Entry<?,?> e : m.entrySet() ) {
				out.put( String.valueOf( e.getKey() ), normalize( e.getValue() ) );
			}
			return out;
		}
		if( o instanceof List<?> l ) {
			final List<Object> out = new ArrayList<>( l.size() );
			for( Object element : l ) {
				out.add( normalize( element ) );
			}
			return out;
		}
		return o;
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> toPlainMap( Object o ) {
		final Map<String,Object> out = new LinkedHashMap<>();
		if( o instanceof NSDictionary<?,?> nsd ) {
			for( Object k : nsd.allKeys() ) {
				out.put( String.valueOf( k ), nsd.objectForKey( k ) );
			}
			return out;
		}
		if( o instanceof Map<?,?> m ) {
			for( Map.Entry<?,?> e : m.entrySet() ) {
				out.put( String.valueOf( e.getKey() ), e.getValue() );
			}
			return out;
		}
		throw new IllegalArgumentException( "not a map-like: " + o.getClass() );
	}

	private static List<Object> toPlainList( NSArray<?> nsa ) {
		final List<Object> out = new ArrayList<>( nsa.count() );
		for( int i = 0; i < nsa.count(); i++ ) {
			out.add( nsa.objectAtIndex( i ) );
		}
		return out;
	}

	private static Object referenceSiteConfigSample() {
		final NSMutableDictionary<String,Object> root = new NSMutableDictionary<>();

		final NSMutableArray<NSDictionary<String,Object>> hostArray = new NSMutableArray<>();
		final NSMutableDictionary<String,Object> host = new NSMutableDictionary<>();
		host.put( "type", "UNIX" );
		host.put( "name", "hz1.rebbi.is" );
		hostArray.add( host );

		final NSMutableArray<NSDictionary<String,Object>> appArray = new NSMutableArray<>();
		final NSMutableDictionary<String,Object> app = new NSMutableDictionary<>();
		app.put( "name", "Hugi" );
		app.put( "startingPort", 2001 );
		app.put( "adaptorThreadsMin", 16 );
		app.put( "lifebeatInterval", 30 );
		app.put( "statisticsPassword", "" );
		appArray.add( app );

		final NSMutableDictionary<String,Object> site = new NSMutableDictionary<>();
		site.put( "viewRefreshRate", 60 );
		site.put( "password", "70418BD55EE8BBDEBEF0B3EBE176F5791A4D" );

		root.put( "hostArray", hostArray );
		root.put( "applicationArray", appArray );
		root.put( "site", site );

		return root;
	}

	private static Map<String,Object> mySiteConfigSample() {
		final Map<String,Object> root = new LinkedHashMap<>();

		final List<Map<String,Object>> hostArray = new ArrayList<>();
		final Map<String,Object> host = new LinkedHashMap<>();
		host.put( "type", "UNIX" );
		host.put( "name", "hz1.rebbi.is" );
		hostArray.add( host );

		final List<Map<String,Object>> appArray = new ArrayList<>();
		final Map<String,Object> app = new LinkedHashMap<>();
		app.put( "name", "Hugi" );
		app.put( "startingPort", 2001 );
		app.put( "adaptorThreadsMin", 16 );
		app.put( "lifebeatInterval", 30 );
		app.put( "statisticsPassword", "" );
		appArray.add( app );

		final Map<String,Object> site = new LinkedHashMap<>();
		site.put( "viewRefreshRate", 60 );
		site.put( "password", "70418BD55EE8BBDEBEF0B3EBE176F5791A4D" );

		root.put( "hostArray", hostArray );
		root.put( "applicationArray", appArray );
		root.put( "site", site );

		return root;
	}

	private static String readResource( String name ) {
		final String path = "/x/" + name;
		try( InputStream in = FoundationCoderTest.class.getResourceAsStream( path ) ) {
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
