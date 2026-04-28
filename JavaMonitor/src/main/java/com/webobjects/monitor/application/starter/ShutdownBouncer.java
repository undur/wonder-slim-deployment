package com.webobjects.monitor.application.starter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.webobjects.monitor._private.model.MApplication;
import com.webobjects.monitor._private.model.MHost;
import com.webobjects.monitor._private.model.MInstance;

/**
 * Bounces an application by refusing new sessions, waiting a while, shutting down all instances, then starting the same instances again.
 * 
 * @author ak
 */
public class ShutdownBouncer extends ApplicationStarter {

	private long _time;

	public ShutdownBouncer( MApplication app, int seconds ) {
		super( app );
		_time = seconds * 1000;
	}

	@Override
	protected void bounce() throws InterruptedException {

		List<MInstance> instances = new ArrayList<>( application().instanceArray() );
		List<MInstance> runningInstances = new ArrayList<>();
		Set<MHost> activeHosts = new HashSet<>();

		for( MInstance instance : instances ) {
			MHost host = instance.host();
			if( instance.isRunning_M() ) {
				runningInstances.add( instance );
				activeHosts.add( host );
			}
		}

		handler().sendRefuseSessionToWotaskds( runningInstances, new ArrayList<>( activeHosts ), true );
		boolean waiting = true;

		long startTime = System.currentTimeMillis();
		// wait until apps have started
		while( waiting && (_time + startTime > System.currentTimeMillis()) ) {
			handler().startReading();
			try {
				log( "Checking for started instances" );
				handler().getInstanceStatusForHosts( new ArrayList<>( activeHosts ) );
				boolean allStopped = false;
				for( MInstance instance : runningInstances ) {
					allStopped &= !instance.isRunning_M();
				}
				if( allStopped ) {
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

		handler().sendStopInstancesToWotaskds( runningInstances, new ArrayList<>( activeHosts ) );
		log( "Stopped instances sucessfully" );

		handler().sendRefuseSessionToWotaskds( runningInstances, new ArrayList<>( activeHosts ), false );
		handler().sendStartInstancesToWotaskds( runningInstances, new ArrayList<>( activeHosts ) );

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