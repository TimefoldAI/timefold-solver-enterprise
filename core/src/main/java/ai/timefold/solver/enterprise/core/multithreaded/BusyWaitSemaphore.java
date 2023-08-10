package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.atomic.AtomicInteger;

final class BusyWaitSemaphore {
    final AtomicInteger availablePermits;

    public BusyWaitSemaphore(int availablePermits) {
        this.availablePermits = new AtomicInteger(availablePermits);
    }

    /**
     * Not thread-safe; can only be called when it is guaranteed no
     * threads are waiting on the Semaphore. Set permits to 0
     */
    public void drainPermits() {
        availablePermits.set(0);
    }

    /**
     * Thread-safe; acquire one permit if it available, otherwise busy waits until one is available
     */
    public void acquire() throws InterruptedException {
        int current = availablePermits.getAcquire();
        while (current == 0 || !availablePermits.compareAndSet(current, current - 1)) {
            current = availablePermits.getAcquire();
            Thread.onSpinWait();
        }
    }

    /**
     * Thread-safe; release one permit
     */
    public void release() {
        availablePermits.incrementAndGet();
    }

    /**
     * Thread-safe; release count permits
     */
    public void release(int count) {
        availablePermits.addAndGet(count);
    }
}
