package sjip.monitor;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WODynamicElement;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSDictionary;

import sjip.monitor.util.Icon;

/**
 * For displaying the icons defined in the Icon enum.
 * 
 * The element has two bindings, [icon] for passing in an Icon enum constant, [name] for using a member constant of the Icon enum by name.
 */
public class JMIcon extends WODynamicElement {

	private final WOAssociation _iconAssociation;

	private final WOAssociation _nameAssociation;
	
	public JMIcon( String aName, NSDictionary<String, WOAssociation> associations, WOElement template ) {
		super( aName, associations, template );
		_iconAssociation = associations.get( "icon" );
		_nameAssociation = associations.get( "name" );
		
		if( _iconAssociation == null && _nameAssociation == null ) {
			throw new IllegalArgumentException( "You must bind either the icon or the icon name" );
		}
		
		if( _iconAssociation != null && _nameAssociation != null ) {
			throw new IllegalArgumentException( "You can't bind both the icon and the icon name" );
		}
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {
		Icon icon;
		
		if( _iconAssociation != null ) {
			icon = (Icon)_iconAssociation.valueInComponent( context.component() );
		}
		else if( _nameAssociation != null ) {
			final String iconName = (String)_nameAssociation.valueInComponent( context.component() );
			icon = Icon.valueOf( iconName );
		}
		else {
			throw new IllegalArgumentException( "You must bind either the icon or the icon name" );
		}
		
		response.appendContentString( icon.svg() );
	}
}
