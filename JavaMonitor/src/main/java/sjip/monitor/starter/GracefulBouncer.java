package sjip.monitor.starter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sjip.core.model.MApplication;
import sjip.core.model.MHost;
import sjip.core.model.MInstance;

/**
 * Bounces an application gracefully. It does so by starting at least one inactive instance
 * per active host (or 10 % of the total active instance count), waiting
 * until they have started, then refusing sessions for all old instances and
 * turning scheduling on for all but the number of instances we started
 * originally. The next effect should be that the new users get the new app,
 * old instances die in due time and then restart when the sessions stop.
 * 
 * You must have at least one inactive instance in order to perform a graceful bounce.
 * 
 * You may or may not need to set ERKillTimer to prevent totally
 * long-running sessions to keep the app from dying.
 *
 * @author ak
 */
public class GracefulBouncer extends ApplicationStarter {

	public GracefulBouncer( MApplication app ) {
		super( app );
	}

	@Override
	protected void bounce() throws InterruptedException {

		List<MInstance> instances = List.copyOf( application().instanceArray() );
		List<MInstance> runningInstances = new ArrayList<>();
		Set<MHost> activeHosts = new HashSet<>();
		Map<MHost, List<MInstance>> inactiveInstancesByHost = new HashMap<MHost, List<MInstance>>();
		Map<MHost, List<MInstance>> activeInstancesByHost = new HashMap<MHost, List<MInstance>>();

		for( MInstance instance : instances ) {
			MHost host = instance.host();
			if( instance.isRunning_M() ) {
				runningInstances.add( instance );
				activeHosts.add( host );
				List<MInstance> currentInstances = activeInstancesByHost.get( host );

				if( currentInstances == null ) {
					currentInstances = new ArrayList<>();
					activeInstancesByHost.put( host, currentInstances );
				}

				currentInstances.add( instance );
			}
			else {
				List<MInstance> currentInstances = inactiveInstancesByHost.get( host );

				if( currentInstances == null ) {
					currentInstances = new ArrayList<>();
					inactiveInstancesByHost.put( host, currentInstances );
				}

				currentInstances.add( instance );
			}
		}

		if( inactiveInstancesByHost.isEmpty() ) {
			addObjectsFromArrayIfAbsentToErrorMessageArray( List.of( "You must have at least one inactive instance to perform a graceful bounce." ) );
			return;
		}

		int numToStartPerHost = 1;

		if( activeHosts.size() > 0 ) {
			numToStartPerHost = (int)(runningInstances.size() / activeHosts.size() * .1);
		}

		if( numToStartPerHost < 1 ) {
			numToStartPerHost = 1;
		}

		boolean useScheduling = true;

		for( MInstance instance : runningInstances ) {
			useScheduling &= instance.schedulingEnabled() != null && instance.schedulingEnabled().booleanValue();
		}

		List<MInstance> startingInstances = new ArrayList<>();

		for( int i = 0; i < numToStartPerHost; i++ ) {
			for( MHost host : activeHosts ) {
				List<MInstance> inactiveInstances = inactiveInstancesByHost.get( host );

				if( inactiveInstances != null && inactiveInstances.size() >= i ) {
					MInstance instance = inactiveInstances.get( i );
					log( "Starting inactive instance " + instance.displayName() + " on host " + host.addressAsString() );
					startingInstances.add( instance );
				}
				else {
					log( "Not enough inactive instances on host: " + host.addressAsString() );
				}
			}
		}
		for( MInstance instance : startingInstances ) {
			if( useScheduling ) {
				instance.setSchedulingEnabled( Boolean.TRUE );
			}
			instance.setAutoRecover( Boolean.TRUE );
		}
		handler().sendUpdateInstancesToWotaskds( startingInstances, new ArrayList<>( activeHosts ) );
		handler().sendStartInstancesToWotaskds( startingInstances, new ArrayList<>( activeHosts ) );
		boolean waiting = true;

		// wait until apps have started
		while( waiting ) {
			handler().startReading();
			try {
				log( "Checking for started instances" );
				handler().getInstanceStatusForHosts( new ArrayList<>( activeHosts ) );
				boolean allStarted = true;
				for( MInstance instance : startingInstances ) {
					allStarted &= instance.isRunning_M();
				}
				if( allStarted ) {
					waiting = false;
				}
				else {
					sleep( 10 * 1000 );
				}
			}
			finally {
				handler().endReading();
			}
		}
		log( "Started instances sucessfully" );

		// turn scheduling off
		for( MHost host : activeHosts ) {
			List<MInstance> currentInstances = activeInstancesByHost.get( host );

			for( MInstance instance : currentInstances ) {
				if( useScheduling ) {
					instance.setSchedulingEnabled( Boolean.FALSE );
				}
				instance.setAutoRecover( Boolean.FALSE );
			}
		}

		handler().sendUpdateInstancesToWotaskds( runningInstances, new ArrayList<>( activeHosts ) );

		// then start to refuse new sessions
		for( MHost host : activeHosts ) {
			List<MInstance> currentInstances = activeInstancesByHost.get( host );
			for( MInstance instance : currentInstances ) {
				instance.setRefusingNewSessions( true );
			}
		}
		handler().sendRefuseSessionToWotaskds( runningInstances, new ArrayList<>( activeHosts ), true );
		log( "Refused new sessions: " + runningInstances );

		// turn scheduling on again, but only
		List<MInstance> restarting = new ArrayList<>();

		for( MHost host : activeHosts ) {
			List<MInstance> currentInstances = activeInstancesByHost.get( host );

			for( int i = 0; i < currentInstances.size() - numToStartPerHost; i++ ) {
				MInstance instance = currentInstances.get( i );

				if( useScheduling ) {
					instance.setSchedulingEnabled( Boolean.TRUE );
				}

				instance.setAutoRecover( Boolean.TRUE );
				restarting.add( instance );
			}
		}

		handler().sendUpdateInstancesToWotaskds( restarting, new ArrayList<>( activeHosts ) );
		log( "Started scheduling again: " + restarting );

		handler().startReading();
		try {
			handler().getInstanceStatusForHosts( new ArrayList<>( activeHosts ) );
			log( "Finished" );
		}
		finally {
			handler().endReading();
		}
	}
}