package sjip.systests.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit Jupiter extension that gives every test method a fresh {@link TestReport} and
 * writes the rendered Markdown to {@code target/test-reports/<ClassName>/<methodName>.md}
 * once the test finishes.
 *
 * <p>Test methods that want to record steps declare a {@code TestReport} parameter:
 * {@snippet :
 * @Test void scenario( TestReport report ) {
 *     report.state( "before", currentSiteConfigXml() );
 *     // ...
 * }
 * }
 *
 * <p>Reports are generated build artifacts — they live under {@code target/} and aren't
 * committed. The on-disk artifact is for live inspection while iterating: open the
 * latest run's report to see the narrative of what each test did, including the
 * captured wire bytes and any failure that aborted it. The committed contract is in
 * the snapshot files under {@code src/test/resources/snapshots/}.
 */
public final class TestReportExtension implements ParameterResolver, BeforeTestExecutionCallback, AfterTestExecutionCallback {

	private static final Namespace NAMESPACE = Namespace.create( TestReportExtension.class );
	private static final String REPORT_KEY = "report";

	@Override
	public boolean supportsParameter( final ParameterContext parameterContext, final ExtensionContext extensionContext ) {
		return parameterContext.getParameter().getType().equals( TestReport.class );
	}

	@Override
	public Object resolveParameter( final ParameterContext parameterContext, final ExtensionContext extensionContext ) {
		return reportFor( extensionContext );
	}

	@Override
	public void beforeTestExecution( final ExtensionContext context ) {
		// Eagerly create the report so test code resolving it from setup methods gets the
		// same instance the parameter resolver will later return.
		reportFor( context );
	}

	@Override
	public void afterTestExecution( final ExtensionContext context ) throws IOException {
		final TestReport report = (TestReport)context.getStore( NAMESPACE ).get( REPORT_KEY );
		if( report == null ) {
			return;
		}

		// If the test threw, append the failure to the report so the on-disk artifact
		// shows the narrative *and* the assertion that aborted it. Without this, a failed
		// test's report ends at whatever step ran last before the throw, with no
		// indication that anything went wrong.
		context.getExecutionException().ifPresent( throwable -> {
			report.heading( "Test FAILED" );
			report.state( throwable.getClass().getSimpleName(), throwable.getMessage() != null ? throwable.getMessage() : "(no message)" );
		} );

		final Path output = Paths.get( "target", "test-reports", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName() + ".md" );
		Files.createDirectories( output.getParent() );
		Files.writeString( output, TestReportRenderer.render( report ), StandardCharsets.UTF_8 );
	}

	private static TestReport reportFor( final ExtensionContext context ) {
		return context.getStore( NAMESPACE ).getOrComputeIfAbsent(
				REPORT_KEY,
				key -> new TestReport( context.getRequiredTestMethod().getName() ),
				TestReport.class );
	}
}
