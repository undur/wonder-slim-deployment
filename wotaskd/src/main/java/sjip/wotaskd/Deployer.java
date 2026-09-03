package sjip.wotaskd;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sjip.core.model.MApplication;
import sjip.core.model.MInstance;
import sjip.core.x.FProperties;

/**
 * Deploys a new build of an application on this host: given a .tar.gz
 * containing {@code <App>.woa}, unpacks it beside the current bundle, moves
 * the current bundle aside as {@code x<App>_<timestamp>.woa} (the convention
 * the post_build scripts established), moves the new one into place, prunes
 * moved-aside builds beyond {@code WOTaskd.deploy.retainedBuilds} (default
 * 5, negative keeps all), and bounces every local instance that was running.
 *
 * The swap happens <em>before</em> the instances are touched. A running JVM
 * holds its jars open by descriptor, so renaming the directory under it is
 * harmless for the seconds it keeps running — and it means anything that
 * starts the instance after this point, us or the autoRecover sweep, starts
 * the new build. First iteration: every instance is terminated and started
 * again; graceful and rolling variants come later.
 */
public class Deployer {

	private static final Logger logger = LoggerFactory.getLogger( Deployer.class );

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern( "yyyy_MM_dd_HH_mm_ss" );

	/** How long to wait for terminated instances to actually leave their ports */
	private static final Duration SHUTDOWN_PATIENCE = Duration.ofSeconds( 60 );

	/**
	 * @return A human-readable report, one line per step
	 * @throws IOException if unpacking or the swap fails — nothing has been bounced in that case
	 */
	public static String deploy( final AppTaskd appTaskd, final MApplication application, final InputStream archive ) throws IOException, InterruptedException {
		final String appName = application.name();

		// The configured path is the launcher inside the bundle: /dir/<App>.woa/<App>
		final Path launcher = Path.of( application.unixPath().trim() );
		final Path bundle = launcher.getParent();
		final Path directory = bundle.getParent();
		final String bundleName = bundle.getFileName().toString();
		final String stamp = STAMP.format( LocalDateTime.now() );

		final List<String> report = new ArrayList<>();

		// 1. Unpack into a staging directory beside the bundle (same filesystem, so the moves are atomic renames)
		final Path staging = directory.resolve( ".deploy-" + appName + "-" + stamp );
		final Path unpacked = staging.resolve( bundleName );

		try {
			Files.createDirectories( staging );
			final Path tarball = staging.resolve( bundleName + ".tar.gz" );
			Files.copy( archive, tarball ); // copies the stream to disk as it arrives — the archive never lives in memory
			untar( tarball, staging );

			if( !Files.isRegularFile( unpacked.resolve( launcher.getFileName() ) ) ) {
				throw new IOException( "Archive does not contain " + bundleName + "/" + launcher.getFileName() );
			}

			report.add( "unpacked %s (%d bytes)".formatted( bundleName, Files.size( tarball ) ) );

			// 2. Swap: current bundle aside, new bundle into place
			if( Files.exists( bundle ) ) {
				final Path archived = directory.resolve( "x" + appName + "_" + stamp + ".woa" );
				Files.move( bundle, archived );
				report.add( "previous bundle kept as " + archived.getFileName() );
			}

			Files.move( unpacked, bundle );
			report.add( "installed " + bundle );
		}
		finally {
			deleteRecursively( staging );
		}

		// 2b. Prune: old builds have little value far into the future, unlike logs.
		// Housekeeping only — a build that won't delete (a root-owned relic of the
		// rsync era, say) is reported and left; it must never stop the bounce below.
		final int retained = FProperties.K.DEPLOY_RETAINED_BUILDS.value();

		if( retained >= 0 ) {
			report.addAll( pruneRetainedBuilds( directory, appName, retained ) );
		}

		// 3. Bounce the local instances that were running
		final InstanceController controller = appTaskd.instanceController();
		final List<MInstance> running = application.instanceArray()
				.stream()
				.filter( MInstance::isLocal_W )
				.filter( MInstance::isRunning_W )
				.toList();

		if( running.isEmpty() ) {
			report.add( "no running instances on this host — nothing bounced" );
			return String.join( "\n", report );
		}

		for( final MInstance instance : running ) {
			appTaskd.lock().readLock().lock();
			try {
				controller.terminateInstance( instance );
			}
			catch( final Exception e ) {
				report.add( "terminate %s: %s".formatted( instance.displayName(), e.getMessage() ) );
			}
			finally {
				appTaskd.lock().readLock().unlock();
			}
		}

		final Instant deadline = Instant.now().plus( SHUTDOWN_PATIENCE );

		for( final MInstance instance : running ) {
			while( (instance.isRunning_W() || isPortOpen( instance.port() )) && Instant.now().isBefore( deadline ) ) {
				Thread.sleep( 250 );
			}

			if( isPortOpen( instance.port() ) ) {
				report.add( "%s still holds port %s after %ss — not restarted".formatted( instance.displayName(), instance.port(), SHUTDOWN_PATIENCE.toSeconds() ) );
			}
		}

		for( final MInstance instance : running ) {
			if( isPortOpen( instance.port() ) ) {
				continue;
			}

			appTaskd.lock().readLock().lock();
			try {
				final String error = controller.startInstance( instance );
				report.add( error == null ? "restarted " + instance.displayName() : "start %s: %s".formatted( instance.displayName(), error ) );
			}
			finally {
				appTaskd.lock().readLock().unlock();
			}
		}

		logger.info( "Deployed {}: {}", appName, String.join( "; ", report ) );
		return String.join( "\n", report );
	}

	/**
	 * The system tar, rather than a Java implementation: it preserves the
	 * launcher's execute bit and symlinks without any help, and every host we
	 * deploy to has it.
	 */
	private static void untar( final Path tarball, final Path into ) throws IOException, InterruptedException {
		final Process tar = new ProcessBuilder( "tar", "-xzf", tarball.toString(), "-C", into.toString() )
				.redirectErrorStream( true )
				.start();

		final String output = new String( tar.getInputStream().readAllBytes() );

		if( tar.waitFor() != 0 ) {
			throw new IOException( "tar failed: " + output.strip() );
		}
	}

	private static boolean isPortOpen( final Integer port ) {
		if( port == null ) {
			return false;
		}

		try( Socket socket = new Socket() ) {
			socket.connect( new InetSocketAddress( "localhost", port ), 300 );
			return true;
		}
		catch( final IOException e ) {
			return false;
		}
	}

	/**
	 * Deletes the oldest {@code x<App>_<timestamp>.woa} directories beside the
	 * bundle until at most {@code retained} remain. The timestamp format sorts
	 * chronologically as text, so the name order is the age order. Never throws:
	 * a directory that won't delete is reported and skipped.
	 *
	 * @return Report lines — one summary for what was pruned, one per failure
	 */
	static List<String> pruneRetainedBuilds( final Path directory, final String appName, final int retained ) {
		// Exactly the names this class writes — x<App>_yyyy_MM_dd_HH_mm_ss.woa — and
		// nothing looser: this may run as root, so the match is the only safety net.
		final Pattern movedAside = Pattern.compile( "x" + Pattern.quote( appName ) + "_\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}\\.woa" );
		final List<String> report = new ArrayList<>();
		final List<Path> builds;

		try( Stream<Path> entries = Files.list( directory ) ) {
			builds = entries
					.filter( p -> Files.isDirectory( p, LinkOption.NOFOLLOW_LINKS ) ) // a symlink is never ours to delete through
					.filter( p -> movedAside.matcher( p.getFileName().toString() ).matches() )
					.sorted( Comparator.comparing( p -> p.getFileName().toString() ) )
					.toList();
		}
		catch( final IOException e ) {
			logger.warn( "Could not list {} for pruning", directory, e );
			report.add( "pruning skipped: " + e );
			return report;
		}

		int pruned = 0;

		for( int i = 0; i < builds.size() - retained; i++ ) {
			final Path build = builds.get( i );

			try {
				deleteRecursively( build );
				pruned++;
				logger.info( "Pruned old build {}", build );
			}
			catch( final IOException e ) {
				logger.warn( "Could not prune old build {}", build, e );
				report.add( "could not prune %s: %s".formatted( build.getFileName(), e ) );
			}
		}

		if( pruned > 0 ) {
			report.add( "pruned %d older build%s, keeping %d (%s)".formatted( pruned, pruned == 1 ? "" : "s", retained, FProperties.K.DEPLOY_RETAINED_BUILDS.name() ) );
		}

		return report;
	}

	private static void deleteRecursively( final Path path ) throws IOException {
		if( !Files.exists( path, LinkOption.NOFOLLOW_LINKS ) ) {
			return;
		}

		// Files.walk doesn't follow links, but a symlinked root would still be
		// descended through; refuse outright rather than delete through it.
		if( Files.isSymbolicLink( path ) ) {
			throw new IOException( path + " is a symbolic link — refusing to delete through it" );
		}

		try( Stream<Path> walk = Files.walk( path ) ) {
			walk.sorted( Comparator.reverseOrder() ).forEach( p -> {
				try {
					Files.delete( p );
				}
				catch( final IOException e ) {
					throw new UncheckedIOException( e );
				}
			} );
		}
	}
}
