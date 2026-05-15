package sjip.monitor.components;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import com.webobjects.appserver.WOActionResults;
/*
 © Copyright 2006- 2007 Apple Computer, Inc. All rights reserved.

 IMPORTANT:  This Apple software is supplied to you by Apple Computer, Inc. ("Apple") in consideration of your agreement to the following terms, and your use, installation, modification or redistribution of this Apple software constitutes acceptance of these terms.  If you do not agree with these terms, please do not use, install, modify or redistribute this Apple software.

 In consideration of your agreement to abide by the following terms, and subject to these terms, Apple grants you a personal, non-exclusive license, under Apple's copyrights in this original Apple software (the "Apple Software"), to use, reproduce, modify and redistribute the Apple Software, with or without modifications, in source and/or binary forms; provided that if you redistribute the Apple Software in its entirety and without modifications, you must retain this notice and the following text and disclaimers in all such redistributions of the Apple Software.  Neither the name, trademarks, service marks or logos of Apple Computer, Inc. may be used to endorse or promote products derived from the Apple Software without specific prior written permission from Apple.  Except as expressly stated in this notice, no other rights or licenses, express or implied, are granted by Apple herein, including but not limited to any patent rights that may be infringed by your derivative works or by other works in which the Apple Software may be incorporated.

 The Apple Software is provided by Apple on an "AS IS" basis.  APPLE MAKES NO WARRANTIES, EXPRESS OR IMPLIED, INCLUDING WITHOUT LIMITATION THE IMPLIED WARRANTIES OF NON-INFRINGEMENT, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE, REGARDING THE APPLE SOFTWARE OR ITS USE AND OPERATION ALONE OR IN COMBINATION WITH YOUR PRODUCTS. 

 IN NO EVENT SHALL APPLE BE LIABLE FOR ANY SPECIAL, INDIRECT, INCIDENTAL OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF THE USE, REPRODUCTION, MODIFICATION AND/OR DISTRIBUTION OF THE APPLE SOFTWARE, HOWEVER CAUSED AND WHETHER UNDER THEORY OF CONTRACT, TORT (INCLUDING NEGLIGENCE), STRICT LIABILITY OR OTHERWISE, EVEN IF APPLE HAS BEEN  ADVISED OF THE POSSIBILITY OF 
 SUCH DAMAGE.
 */
import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;

import sjip.core.model.MApplication;
import sjip.core.x.XUtil;
import sjip.monitor.MonitorComponent;
import sjip.monitor.components.ConfirmationPage.ConfirmationDelegate;

public class ApplicationsPage extends MonitorComponent {

	/**
	 * Application currently being iterated over in the UI
	 */
	public MApplication currentApplication;
	
	/**
	 * Name of new application (when adding an application)
	 */
	public String newApplicationName;

	public ApplicationsPage( WOContext aWocontext ) {
		super( aWocontext );
		handler().updateForPage( getClass() );
	}

	/**
	 * @return A sorted list of all configured applications
	 */
	public List<MApplication> applications() {
		final List<MApplication> applications = new ArrayList<>( siteConfig().applicationArray() );
		Collections.sort( applications, Comparator.comparing( MApplication::name, String.CASE_INSENSITIVE_ORDER ) );
		return applications;
	}

	/**
	 * @return a link to the application, through the adaptor
	 */
	public String hrefToApp() {
		String url = siteConfig().woAdaptor();

		if( url == null ) {
			return null;
		}

		return url + "/" + currentApplication.name();
	}

	public WOComponent appDetailsClicked() {
		return AppDetailPage.create( context(), currentApplication );
	}

	public WOComponent addApplicationClicked() {
		if( XUtil.isValidXMLString( newApplicationName ) ) {
			handler().startReading();
			try {
				if( siteConfig().applicationWithName( newApplicationName ) == null ) {
					MApplication newApplication = new MApplication( newApplicationName, siteConfig() );
					siteConfig().addApplication_M( newApplication );

					if( siteConfig().hostArray().size() != 0 ) {
						handler().sendAddApplicationToWotaskds( newApplication, siteConfig().hostArray() );
					}

					AppConfigurePage aPage = AppConfigurePage.create( context(), newApplication );
					aPage.isNewInstanceSectionVisible = true;

					// endReading in the finally block below
					return aPage;
				}
			}
			finally {
				handler().endReading();
			}
		}
		newApplicationName = null;
		return ApplicationsPage.create( context() );
	}

	public static WOComponent create( WOContext context ) {
		return context.page().pageWithName( ApplicationsPage.class.getName() );
	}

	public WOComponent deleteClicked() {

		final MApplication application = currentApplication;

		final Supplier<WOActionResults> confirm = () -> {
			
			handler().whileWriting( () -> {
				siteConfig().removeApplication_M( application );

				if( siteConfig().hostArray().size() != 0 ) {
					handler().sendRemoveApplicationToWotaskds( application, siteConfig().hostArray() );
				}
			});

			return ApplicationsPage.create( context() );
		};

		final Supplier<WOActionResults> cancel = () -> {
			return ApplicationsPage.create( context() );
		};

		return ConfirmationPage.create( context(), new ConfirmationDelegate(
				APP_PAGE,
				"Are you sure you want to delete the <I>" + application.name() + "</I> Application?",
				"Selecting 'Yes' will shutdown any running instances of this application, delete all instance configurations, and remove this application from the Application page.",
				confirm,
				cancel
				) );
	}

	public WOComponent bounceClicked() {
		AppDetailPage page = AppDetailPage.create( context(), currentApplication );
		page = (AppDetailPage)page.bounceClicked();
		return page;
	}

	public WOComponent configureClicked() {
		AppConfigurePage aPage = AppConfigurePage.create( context(), currentApplication );
		aPage.isNewInstanceSectionVisible = true;
		return aPage;
	}

	/**
	 * @return the total number of configured instances for all applications
	 */
	public int totalInstancesConfigured() {
		int total = 0;

		for( MApplication mApplication : applications() ) {
			total += mApplication.instanceArray().size();
		}

		return total;
	}

	/**
	 * @return the total number of running instances for all applications
	 */
	public int totalInstancesRunning() {
		int total = 0;

		for( MApplication mApplication : applications() ) {
			total += mApplication.runningInstancesCount();
		}

		return total;
	}
}