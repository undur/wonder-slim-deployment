package sjip.wotaskd;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class AppTaskd {

	private final ReentrantReadWriteLock _lock;
	
	public AppTaskd() {
		_lock = new ReentrantReadWriteLock();
	}

	public ReentrantReadWriteLock lock() {
		return _lock;
	}
}