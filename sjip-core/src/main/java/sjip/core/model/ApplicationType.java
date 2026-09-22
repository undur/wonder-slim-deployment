package sjip.core.model;

/**
 * The kind of application an instance turned out to be, discovered at runtime by wotaskd probing the
 * instance (see wonder-slim-deployment#56). An attribute of the application really — all of an app's
 * instances are the same type — but obtained per instance, since an instance is what answers.
 *
 * Not persisted: type is a runtime fact about a running instance, re-discovered on registration.
 */
public enum ApplicationType {

	NG_OBJECTS( "ng-objects" ),
	WONDER_SLIM( "wonder-slim" ),
	PROJECT_WONDER( "Project Wonder" ),
	OTHER( "Other" );

	private final String _displayName;

	ApplicationType( final String displayName ) {
		_displayName = displayName;
	}

	public String displayName() {
		return _displayName;
	}

	/**
	 * @return The type for a wire value (the enum constant's name), null for null or an unknown value -
	 *         tolerant, since the value crosses the JavaMonitor/wotaskd version boundary
	 */
	public static ApplicationType fromWireValue( final String wireValue ) {

		if( wireValue == null ) {
			return null;
		}

		try {
			return valueOf( wireValue );
		}
		catch( final IllegalArgumentException e ) {
			return null;
		}
	}
}
