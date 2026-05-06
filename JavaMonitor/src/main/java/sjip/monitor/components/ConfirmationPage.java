package sjip.monitor.components;

import java.util.Objects;
import java.util.function.Supplier;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;

import er.extensions.appserver.ERXApplication;
import sjip.monitor.MonitorComponent;

public class ConfirmationPage extends MonitorComponent {

	public record ConfirmationDelegate(
			int pageType,
			String question,
			String explaination,
			Supplier<WOActionResults> confirmSupplier,
			Supplier<WOActionResults> cancelSupplier) {

		public WOActionResults confirm() {
			return confirmSupplier().get();
		}
		
		public WOActionResults cancel() {
			return cancelSupplier().get();
		}
	}

	private ConfirmationDelegate _delegate;

	public ConfirmationPage( WOContext context ) {
		super( context );
	}

	public ConfirmationDelegate delegate() {
		return _delegate;
	}

	public static ConfirmationPage create( WOContext context, ConfirmationDelegate delegate ) {
		Objects.requireNonNull( context );
		Objects.requireNonNull( delegate );

		final ConfirmationPage page = ERXApplication.erxApplication().pageWithName( ConfirmationPage.class );
		page._delegate = delegate;
		return page;
	}
}