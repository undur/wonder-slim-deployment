package sjip.wotaskd;

import java.util.TimerTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;

import sjip.core.SjipException;
import sjip.core.model.MInstance;

public abstract class MInstanceTask extends TimerTask {

	private static final Logger logger = LoggerFactory.getLogger( MInstanceTask.class );

	protected final MInstance _instance;

	public MInstanceTask( MInstance instance ) {
		_instance = instance;
	}

	public static class ForceQuit extends MInstanceTask {

		public ForceQuit( MInstance instance ) {
			super( instance );
		}

		@Override
		public void run() {
			Application app = (Application)WOApplication.application();
			app.appTaskd().lock().readLock().lock();
			try {
				_instance.setShouldDie( true );
				_instance.setForceQuitTask( null );
				cancel();
			}
			finally {
				app.appTaskd().lock().readLock().unlock();
			}
		}

	}

	public static class Refuse extends MInstanceTask {

		private int _numberOfRetriesBeforeForceQuit;
		private int retries = 0;

		public Refuse( MInstance instance, int numberOfRetriesBeforeForceQuit ) {
			super( instance );
			_numberOfRetriesBeforeForceQuit = numberOfRetriesBeforeForceQuit;
		}

		@Override
		public void run() {
			Application app = (Application)WOApplication.application();
			app.appTaskd().lock().readLock().lock();
			InstanceController instanceController = app.appTaskd().instanceController();
			try {

				if( retries >= _numberOfRetriesBeforeForceQuit ) {
					//we only send a force quit if the instance is still running 
					if( _instance.isRunning_W() )
						_instance.setShouldDie( true );

					_instance.setForceQuitTask( null );
					//stop this task from starting again
					cancel();
				}
				else if( _instance.isRefusingNewSessions() == false ) {
					//resend the REFUSE command
					if( instanceController.stopInstance( _instance ) != null ) {
						//we got a response, let's reset the retry
						//if retries reaches the max (WOTaskd.refuseNumRetries), force quit the instance
						retries = 0;
					}
				}

			}
			catch( SjipException e ) {
				logger.error( "Exception while scheduling forceQuit: " + e.getMessage() );
			}
			finally {
				++retries;
				app.appTaskd().lock().readLock().unlock();
			}
		}
	}
}