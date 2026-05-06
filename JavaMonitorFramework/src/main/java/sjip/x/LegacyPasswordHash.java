package sjip.x;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;

public class LegacyPasswordHash {

	/********** Password Methods **********/
	private static long myrand() {
		long nextLong = ThreadLocalRandom.current().nextLong();
		while( nextLong == Long.MIN_VALUE ) {
			nextLong = ThreadLocalRandom.current().nextLong();
		}
		return Math.abs( nextLong );
	}

	public static String encryptStringWithKey( String to_be_encrypted, String aKey ) {
		String encrypted_value = "";
		final char xdigit[] = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };
		final MessageDigest messageDigest;

		try {
			messageDigest = MessageDigest.getInstance( "MD5" );
		}
		catch( final NoSuchAlgorithmException exc ) {
			throw new AssertionError( "MD5 is mandatory in the JDK; this cannot happen", exc );
		}

		if( to_be_encrypted != null ) {
			byte digest[];
			byte fudge_constant[] = "X#@!".getBytes( StandardCharsets.UTF_8 );

			byte fudgetoo_part[] = {
					(byte)xdigit[(int)(myrand() % 16)],
					(byte)xdigit[(int)(myrand() % 16)],
					(byte)xdigit[(int)(myrand() % 16)],
					(byte)xdigit[(int)(myrand() % 16)]
			};

			int i = 0;

			if( aKey != null ) {
				fudgetoo_part = aKey.getBytes( StandardCharsets.UTF_8 );
			}

			messageDigest.update( fudge_constant );
			messageDigest.update( to_be_encrypted.getBytes( StandardCharsets.UTF_8 ) );

			messageDigest.update( fudgetoo_part );
			digest = messageDigest.digest();
			encrypted_value = new String( fudgetoo_part );

			for( i = 0; i < digest.length; i++ ) {
				int mashed;
				final char temp[] = new char[2];
				if( digest[i] < 0 ) {
					mashed = 127 + (-1 * digest[i]);
				}
				else {
					mashed = digest[i];
				}
				temp[0] = xdigit[mashed / 16];
				temp[1] = xdigit[mashed % 16];
				encrypted_value = encrypted_value + (new String( temp ));
			}
		}

		return encrypted_value;
	}
}