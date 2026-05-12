package sjip.systests.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot-based assertion helper. Compares an actual string (typically wire XML
 * or {@code SiteConfig.xml} content) against a committed reference file. The first
 * run for a given snapshot writes the reference; subsequent runs assert equivalence.
 *
 * <p>To regenerate snapshots after a deliberate behavioural change:
 * {@snippet :
 * UPDATE_SNAPSHOTS=true mvn verify
 * }
 *
 * <p>Snapshots live under {@code src/test/resources/snapshots/<group>/<name>.xml}.
 * Each test class typically uses its own group directory.
 */
public final class SnapshotAsserter {

	private static final Logger logger = LoggerFactory.getLogger( SnapshotAsserter.class );

	private static final boolean UPDATE_MODE = Boolean.parseBoolean( System.getenv().getOrDefault( "UPDATE_SNAPSHOTS", "false" ) );

	private final Path _snapshotsRoot;

	public SnapshotAsserter( final String group ) {
		_snapshotsRoot = Paths.get( "src", "test", "resources", "snapshots", group );
	}

	/**
	 * Compares {@code actual} against the snapshot at {@code <group>/<name>.<extension>}.
	 *
	 * <p>If the snapshot doesn't exist: writes it (first-run capture) and logs a notice.
	 * The test passes — the captured artifact is the new reference. <strong>Review the
	 * captured file in the commit before merging.</strong>
	 *
	 * <p>If the snapshot exists and {@code actual} matches: pass.
	 * If they differ and {@code UPDATE_SNAPSHOTS=true}: overwrite the snapshot, log a notice.
	 * If they differ otherwise: fail with the diff.
	 */
	public void assertMatches( final String name, final String extension, final String actual ) {
		Objects.requireNonNull( actual, "snapshot input must not be null" );

		final Path snapshotFile = _snapshotsRoot.resolve( name + "." + extension );

		try {
			if( !Files.exists( snapshotFile ) ) {
				Files.createDirectories( snapshotFile.getParent() );
				Files.writeString( snapshotFile, actual, StandardCharsets.UTF_8 );
				logger.warn( "CAPTURED new snapshot at {} — review before committing", snapshotFile );
				return;
			}

			final String expected = Files.readString( snapshotFile, StandardCharsets.UTF_8 );

			if( expected.equals( actual ) ) {
				return;
			}

			if( UPDATE_MODE ) {
				Files.writeString( snapshotFile, actual, StandardCharsets.UTF_8 );
				logger.warn( "UPDATED snapshot {} — review the diff before committing", snapshotFile );
				return;
			}

			Assertions.fail( "Snapshot mismatch for " + snapshotFile + "\n\nExpected:\n" + expected + "\n\nActual:\n" + actual + "\n\nIf the new shape is correct, re-run with UPDATE_SNAPSHOTS=true to update." );
		}
		catch( final IOException e ) {
			throw new RuntimeException( "Failed to access snapshot " + snapshotFile, e );
		}
	}
}
