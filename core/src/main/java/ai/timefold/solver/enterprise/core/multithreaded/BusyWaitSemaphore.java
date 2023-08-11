package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A variant of {@link java.util.concurrent.Semaphore} that
 * <a href="https://en.wikipedia.org/wiki/Spinlock">busy waits</a> for
 * a permit to be available instead of blocking the thread for better performance.
 * When score calculation speed is high (such as in Cloud Balancing),
 * the few hundred nanoseconds lost by using a blocking call significantly impacts
 * performance.
 */
final class BusyWaitSemaphore {
    /**
     * The number of permits current available for this {@link BusyWaitSemaphore}.
     * Always at least 0.
     */
    final AtomicInteger availablePermits;

    /**
     * Creates a {@link BusyWaitSemaphore} with the given number of initial permits.
     *
     * @param availablePermits The initial amount of available permits.
     */
    public BusyWaitSemaphore(int availablePermits) {
        this.availablePermits = new AtomicInteger(availablePermits);
    }

    /**
     * Not thread-safe; can only be called when it is guaranteed no
     * threads are waiting on the Semaphore. Set permits to 0.
     */
    public void drainPermits() {
        availablePermits.set(0);
    }

    /**
     * Thread-safe; acquire one permit if it available,
     * otherwise busy waits until one is available.
     */
    public void acquire() throws InterruptedException {
        // The thread will repeatedly get the current number of available permits,
        // and if the number of available permits is greater than 0, it will attempt
        // to decrement it by 1. The decrement will fail if the number of permits changes
        // during the decrement. If it fails, it reacquires the number of available permits
        // and try again. If it succeeds, it exits the loop.
        int current = availablePermits.getAcquire();
        while (current == 0 || !availablePermits.compareAndSet(current, current - 1)) {
            current = availablePermits.getAcquire();
            Thread.onSpinWait();
        }
    }

    /**
     * Thread-safe; release one permit.
     */
    public void release() {
        availablePermits.incrementAndGet();
    }

    /**
     * Thread-safe; release count permits.
     *
     * @param count The number of permits to release
     */
    public void release(int count) {
        availablePermits.addAndGet(count);
    }
}
