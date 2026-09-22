package sjip.core.model;

import java.util.EnumSet;
import java.util.List;

/**
 * A kind of application an instance turned out to be, discovered at runtime by wotaskd probing the
 * instance (see wonder-slim-deployment#56). An instance can carry several — a hybrid application
 * serves multiple frameworks at once. An attribute of the application really, but obtained per
 * instance, since an instance is what answers.
 *
 * Not persisted: types are a runtime fact about a running instance, re-discovered on registration.
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

	/**
	 * @return The types for a wire value list (each element an enum constant name), enum-ordered and
	 *         deduplicated; null when the value isn't a list or nothing in it survives - tolerant, since
	 *         values cross the JavaMonitor/wotaskd version boundary
	 */
	public static List<ApplicationType> fromWireValues( final Object wireValues ) {

		if( !(wireValues instanceof List<?> list) ) {
			return null;
		}

		final EnumSet<ApplicationType> types = EnumSet.noneOf( ApplicationType.class );

		for( final Object value : list ) {
			final ApplicationType type = value instanceof String string ? fromWireValue( string ) : null;

			if( type != null ) {
				types.add( type );
			}
		}

		return types.isEmpty() ? null : List.copyOf( types );
	}
}
