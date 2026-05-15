package sjip.monitor.starter;

import java.util.ArrayList;
import java.util.List;

import sjip.core.model.MApplication;
import sjip.core.model.MHost;
import sjip.core.model.MInstance;

/**
 * Bounces an application using a rolling shutdown. 
 * 
 * It does so by starting at least one inactive instance per active host 
 * (or 10 % of the total active instance count), waiting until they have started, 
 * then forcefully restarting each instance one at a time until they have all 
 * been restarted.
 * 
 * You must have at least one inactive instance in order to perform this bounce.
 * 
 * @author johnthuss
 */
public class RollingShutdownBouncer extends ApplicationStarter {

	public RollingShutdownBouncer( MApplication app ) {
		super( app );
	}

	@Override
	protected void bounce() throws InterruptedException {

		List<MInstance> instances = new ArrayList<>( application().instanceArray() );
		List<MInstance> runningInstances = application().runningInstances_M();
		List<MHost> activeHosts = runningInstances
				.stream()
				.map( MInstance::host )
				.distinct()
				.toList();

		List<MInstance> inactiveInstances = new ArrayList<>( instances );
		inactiveInstances.removeAll( runningInstances );

		if( inactiveInstances.isEmpty() ) {
			addObjectsFromArrayIfAbsentToErrorMessageArray( List.of( "You must have at least one inactive instance to perform a rolling shutdown bounce." ) );
			return;
		}

		int numInstancesToStartPerHost = numInstancesToStartPerHost( runningInstances, activeHosts );
		List<MInstance> startingInstances = instancesToStart( inactiveInstances, activeHosts, numInstancesToStartPerHost );

		boolean useScheduling = doAllRunningInstancesUseScheduling( runningInstances );
		log( "Starting inactive instances" );
		startInstances( startingInstances, activeHosts, useScheduling );

		waitForInactiveInstancesToStart( startingInstances, activeHosts );

		List<MInstance> restartingInstances = new ArrayList<>( runningInstances );
		refuseNewSessions( restartingInstances, activeHosts );

		List<MInstance> stoppingInstances = new ArrayList<>();

		for( int i = numInstancesToStartPerHost; i > 0; i-- ) {
			if( restartingInstances.isEmpty() ) {
				break;
			}
			stoppingInstances.add( restartingInstances.removeLast() );
		}

		restartInstances( restartingInstances, activeHosts, useScheduling );
		stopInstances( stoppingInstances, activeHosts );

		handler().startReading();
		try {
			handler().getInstanceStatusForHosts( activeHosts );
			log( "Finished" );
		}
		finally {
			handler().endReading();
		}
	}

	private int numInstancesToStartPerHost( List<MInstance> runningInstances, List<MHost> activeHosts ) {
		int numToStartPerHost = 1;

		if( activeHosts.size() > 0 ) {
			numToStartPerHost = (int)(runningInstances.size() / activeHosts.size() * .1);
		}

		if( numToStartPerHost < 1 ) {
			numToStartPerHost = 1;
		}

		return numToStartPerHost;
	}

	private List<MInstance> instancesToStart( List<MInstance> inactiveInstances, List<MHost> activeHosts, int numInstancesToStartPerHost ) {

		final List<MInstance> startingInstances = new ArrayList<>();

		for( int i = 0; i < numInstancesToStartPerHost; i++ ) {
			for( MHost host : activeHosts ) {
				final List<MInstance> inactiveInstancesForHost = inactiveInstances
						.stream()
						.filter( m-> m.host().equals( host ) )
						.toList();

				if( inactiveInstancesForHost != null && inactiveInstancesForHost.size() >= i ) {
					MInstance instance = inactiveInstancesForHost.get( i );
					log( "Starting inactive instance " + instance.displayName() + " on host " + host.addressAsString() );
					startingInstances.add( instance );
				}
				else {
					log( "Not enough inactive instances on host: " + host.addressAsString() );
				}
			}
		}

		return startingInstances;
	}

	private boolean doAllRunningInstancesUseScheduling( List<MInstance> runningInstances ) {
		boolean useScheduling = true;

		for( MInstance instance : runningInstances ) {
			useScheduling &= instance.schedulingEnabled() != null && instance.schedulingEnabled().booleanValue();
		}

		return useScheduling;
	}

	private void startInstances( List<MInstance> startingInstances, List<MHost> activeHosts, boolean useScheduling ) {

		for( MInstance instance : startingInstances ) {
			if( useScheduling ) {
				instance.setSchedulingEnabled( Boolean.TRUE );
			}

			instance.setAutoRecover( Boolean.TRUE );
		}

		handler().sendUpdateInstancesToWotaskds( startingInstances, activeHosts );
		handler().sendStartInstancesToWotaskds( startingInstances, activeHosts );
	}

	private void waitForInactiveInstancesToStart( List<MInstance> startingInstances, List<MHost> activeHosts ) throws InterruptedException {
		boolean waiting = true;

		// wait until apps have started
		while( waiting ) {
			handler().startReading();
			try {
				log( "Checking to see if inactive instances have started" );
				handler().getInstanceStatusForHosts( activeHosts );
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
		log( "Started inactive instances sucessfully" );
	}

	private void refuseNewSessions( List<MInstance> restartingInstances, List<MHost> activeHosts ) {
		for( MInstance instance : restartingInstances ) {
			instance.setRefusingNewSessions( true );
		}
		handler().sendRefuseSessionToWotaskds( restartingInstances, activeHosts, true );
	}

	private void restartInstances( List<MInstance> runningInstances, List<MHost> activeHosts, boolean useScheduling ) throws InterruptedException {

		for( MInstance instance : runningInstances ) {
			List<MInstance> instanceInArray = new ArrayList<>( List.of( instance ) );
			handler().sendStopInstancesToWotaskds( instanceInArray, activeHosts );

			sleep( 10 * 1000 );

			handler().sendUpdateInstancesToWotaskds( instanceInArray, activeHosts );

			startInstances( instanceInArray, activeHosts, useScheduling );
			waitForInactiveInstancesToStart( instanceInArray, activeHosts );
			log( "Restarted instance " + instance.displayName() + " sucessfully" );
		}
	}

	private void stopInstances( List<MInstance> stoppingInstances, List<MHost> activeHosts ) {

		for( MInstance instance : stoppingInstances ) {
			instance.setSchedulingEnabled( Boolean.FALSE );
			instance.setAutoRecover( Boolean.FALSE );
		}

		handler().sendUpdateInstancesToWotaskds( stoppingInstances, activeHosts );
		handler().sendStopInstancesToWotaskds( stoppingInstances, activeHosts );
		log( "Stopped instances " + stoppingInstances.toString() + " sucessfully" );
	}
}