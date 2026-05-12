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
 * writes the rendered Markdown to {@code src/test/resources/reports/<ClassName>/<methodName>.md}
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
 * <p>Reports are checked in alongside snapshots; they're documentation of what each test
 * does, reviewable on PR diff. A diff in a report file means the test's <em>narrative</em>
 * changed (which is usually a signal worth eyeballing) even if all assertions still pass.
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
		final Path output = Paths.get( "src", "test", "resources", "reports", context.getRequiredTestClass().getSimpleName(), context.getRequiredTestMethod().getName() + ".md" );
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
