package com.webobjects.monitor.application.admin;

import java.util.ArrayList;
import java.util.List;

import com.webobjects.appserver.WOContext;
import com.webobjects.monitor._private.MUtil;
import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MHost;
import com.webobjects.monitor._private.model.MInstance;
import com.webobjects.monitor.application.components.AppDetailPage;
import com.webobjects.monitor.application.components.ApplicationsPage;

public class AdminApplicationsPage extends ApplicationsPage {

	private List<MHost> processedHosts;
	private List<MInstance> processedInstances;

	public AdminApplicationsPage( WOContext context ) {
		super( context );
		processedHosts = new ArrayList();
		processedInstances = new ArrayList();
	}

	private void processedInstance( MInstance minstance ) {
		processedInstances.add( minstance );
		processedHosts.add( minstance.host() );
	}

	private void cleanup() {
		processedInstances.clear();
		processedHosts.clear();
	}

	private void sendUpdateInstancesToWotaskds() {
		if( processedInstances.size() > 0 ) {
			handler().sendUpdateInstancesToWotaskds( processedInstances, processedHosts );
		}
		cleanup();
	}

	private void sendCommandInstancesToWotaskds( String s ) {
		if( processedInstances.size() > 0 ) {
			handler().sendCommandInstancesToWotaskds( s, processedInstances, processedHosts );
		}
		cleanup();
	}

	public void clearDeaths( List<MInstance> nsarray ) {

		for( MInstance minstance : nsarray ) {
			processedInstance( minstance );
		}

		sendCommandInstancesToWotaskds( "CLEAR" );
	}

	public void scheduleType( List<MInstance> nsarray, String scheduleType ) {
		// Should be one of "HOURLY", "DAILY", "WEEKLY"
		for( MInstance minstance : nsarray ) {
			minstance.setSchedulingType( scheduleType );
			processedInstance( minstance );
		}
		sendUpdateInstancesToWotaskds();
	}

	public void hourlyStartHours( List<MInstance> nsarray, int beginScheduleWindow, int endScheduleWindow, int interval ) {
		int hour = beginScheduleWindow;
		for( MInstance minstance : nsarray ) {
			if( hour > endScheduleWindow )
				hour = beginScheduleWindow;
			minstance.setSchedulingHourlyStartTime( Integer.valueOf( hour ) );
			minstance.setSchedulingInterval( Integer.valueOf( interval ) );
			processedInstance( minstance );
			hour++;
		}
		sendUpdateInstancesToWotaskds();
	}

	public void dailyStartHours( List<MInstance> nsarray, int beginScheduleWindow, int endScheduleWindow ) {
		int hour = beginScheduleWindow;
		for( MInstance minstance : nsarray ) {
			if( hour > endScheduleWindow )
				hour = beginScheduleWindow;
			minstance.setSchedulingDailyStartTime( Integer.valueOf( hour ) );
			processedInstance( minstance );
			hour++;
		}
		sendUpdateInstancesToWotaskds();
	}

	public void weeklyStartHours( List<MInstance> nsarray, int beginScheduleWindow, int endScheduleWindow, int startDay ) {
		int hour = beginScheduleWindow;
		for( MInstance minstance : nsarray ) {
			if( hour > endScheduleWindow )
				hour = beginScheduleWindow;
			minstance.setSchedulingWeeklyStartTime( Integer.valueOf( hour ) );
			minstance.setSchedulingStartDay( Integer.valueOf( startDay ) );
			processedInstance( minstance );
			hour++;
		}
		sendUpdateInstancesToWotaskds();
	}

	public void turnScheduledOn( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( !minstance.isScheduled() ) {
				minstance.setSchedulingEnabled( Boolean.TRUE );
				processedInstance( minstance );
			}
		}
		sendUpdateInstancesToWotaskds();
	}

	public void turnScheduledOff( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( minstance.isScheduled() ) {
				minstance.setSchedulingEnabled( Boolean.FALSE );
				processedInstance( minstance );
			}
		}
		sendUpdateInstancesToWotaskds();
	}

	public void setAdditionalArgs( List<MInstance> instances, String arguments ) {
		for( MInstance instance : instances ) {
			String instArgs = instance.additionalArgs();
			if( instArgs == null || !arguments.equals( instArgs ) ) {
				instance.setAdditionalArgs( arguments );
				processedInstance( instance );
			}
		}
		sendUpdateInstancesToWotaskds();
	}

	public void turnRefuseNewSessionsOn( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( !minstance.isRefusingNewSessions() ) {
				minstance.setRefusingNewSessions( true );
				processedInstance( minstance );
			}
		}
		sendCommandInstancesToWotaskds( "REFUSE" );
	}

	public void turnRefuseNewSessionsOff( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( minstance.isRefusingNewSessions() ) {
				minstance.setRefusingNewSessions( false );
				processedInstance( minstance );
			}
		}
		sendCommandInstancesToWotaskds( "ACCEPT" );
	}

	public void turnAutoRecoverOn( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( minstance.autoRecover() == null || !minstance.autoRecover().booleanValue() ) {
				minstance.setAutoRecover( Boolean.TRUE );
				processedInstance( minstance );
			}
		}
		sendUpdateInstancesToWotaskds();
	}

	public void turnAutoRecoverOff( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( minstance.autoRecover() != null && minstance.autoRecover().booleanValue() ) {
				minstance.setAutoRecover( Boolean.FALSE );
				processedInstance( minstance );
			}
		}
		sendUpdateInstancesToWotaskds();
	}

	public void forceQuit( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			minstance.state = MUtil.STOPPING;
			processedInstance( minstance );
		}
		sendCommandInstancesToWotaskds( "QUIT" );
	}

	public void stop( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( minstance.state == MUtil.ALIVE || minstance.state == MUtil.STARTING ) {
				minstance.state = MUtil.STOPPING;
				processedInstance( minstance );
			}
		}
		sendCommandInstancesToWotaskds( "STOP" );
	}

	public void start( List<MInstance> nsarray ) {
		for( MInstance minstance : nsarray ) {
			if( minstance.state == MUtil.DEAD || minstance.state == MUtil.STOPPING || minstance.state == MUtil.CRASHING || minstance.state == MUtil.UNKNOWN ) {
				minstance.state = MUtil.STARTING;
				processedInstance( minstance );
			}
		}
		sendCommandInstancesToWotaskds( "START" );
	}

	public void bounce( List<MApplication> applications ) {
		bounceGraceful( applications );
	}

	public void bounceGraceful( List<MApplication> applications ) {
		for( MApplication application : applications ) {
			AppDetailPage page = AppDetailPage.create( context(), application );
			page = (AppDetailPage)page.bounceClickedWithGracefulBouncer();
		}
	}

	public void bounceShutdown( List<MApplication> applications, int maxwait ) {
		for( MApplication application : applications ) {
			AppDetailPage page = AppDetailPage.create( context(), application );
			page = (AppDetailPage)page.bounceClickedWithShutdownBouncer( maxwait );
		}
	}

	public void bounceRolling( List<MApplication> applications ) {
		for( MApplication application : applications ) {
			AppDetailPage page = AppDetailPage.create( context(), application );
			page = (AppDetailPage)page.bounceClickedWithRollingBouncer();
		}
	}
}