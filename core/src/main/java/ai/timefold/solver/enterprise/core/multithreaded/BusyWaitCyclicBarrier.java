package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A variant of {@link CyclicBarrier} that
 * <a href="https://en.wikipedia.org/wiki/Spinlock">busy waits</a> for all threads
 * to enter {@link #await()} instead of blocking the thread for better
 * performance. When score calculation speed is high (such as in Cloud Balancing),
 * the few hundred nanoseconds lost by using a blocking call significantly impacts
 * performance.
 */
final class BusyWaitCyclicBarrier {
    /**
     * The number of threads required to enter await for it to unblock
     */
    private final int parties;

    /**
     * The number of threads currently blocked in the critical section.
     * Resets to 0 when it is equal to {@link #parties}.
     */
    private final AtomicInteger waitingParties;

    /**
     * The number of threads that left the critical section.
     * Resets to 0 when it is equal to {@link #parties}.
     */
    private final AtomicInteger finishedParties;

    /**
     * True if and only if this barrier was reset.
     */
    private final AtomicBoolean isReset;

    /**
     * Create a {@link BusyWaitCyclicBarrier} that blocks {@link #await()} until the given number of
     * parties enter it.
     *
     * @param parties The number of threads that need to enter {@link #await()} for it to unblock.
     */
    public BusyWaitCyclicBarrier(int parties) {
        this.parties = parties;
        waitingParties = new AtomicInteger(0);
        finishedParties = new AtomicInteger(0);
        isReset = new AtomicBoolean(false);
    }

    public int getParties() {
        return parties;
    }

    /**
     * Block the current thread until one of the following conditions is true:
     *
     * <ul>
     * <li>
     * The number of threads blocked by this {@link BusyWaitCyclicBarrier} await
     * is equal to {@link #parties}.
     * </li>
     * <li>
     * The {@link BusyWaitCyclicBarrier} is reset by a {@link #reset()} call.
     * </li>
     * </ul>
     *
     * @throws InterruptedException Never, declared to match the signature of {@link CyclicBarrier#await()}
     * @throws BrokenBarrierException If the {@link BusyWaitCyclicBarrier} is reset by a {@link #reset()} call
     */
    public void await() throws InterruptedException, BrokenBarrierException {
        if (waitingParties.incrementAndGet() == parties) {
            // All threads are in the barrier; set waiting parties to 0
            // (after incrementing finished parties to guarantee it will not
            //  be the last party to leave) to trigger threads in the
            //  critical section to exit the loop
            finishedParties.incrementAndGet();
            waitingParties.set(0);

            // Wait for all threads to exit the critical section
            while (finishedParties.get() > 0) {
                if (isReset.get()) {
                    throw new BrokenBarrierException();
                }
                Thread.onSpinWait();
            }
            return;
        }

        // Critical section starts; threads from different
        // "generations" of await cannot be inside this loop
        // at the same time without corrupting the barrier
        while (waitingParties.get() > 0) {
            if (isReset.get()) {
                throw new BrokenBarrierException();
            }
            Thread.onSpinWait();
        }
        // Critical section ends
        if (finishedParties.incrementAndGet() == parties) {
            // All threads left the critical section; set
            // finishedParties to 0 to allow them to exit
            // the loop (and potentially enter another await
            // call, starting a new "generation")
            finishedParties.set(0);
            return;
        }

        // Wait for all threads to exit the critical section
        while (finishedParties.get() > 0) {
            if (isReset.get()) {
                throw new BrokenBarrierException();
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Resets the current barrier, causing all current and future calls to
     * {@link #await()} to throw {@link BrokenBarrierException}.
     */
    public void reset() {
        isReset.set(true);
    }
}
