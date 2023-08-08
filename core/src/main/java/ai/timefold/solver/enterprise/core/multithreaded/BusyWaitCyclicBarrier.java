package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class BusyWaitCyclicBarrier {
    private final int parties;
    private final AtomicInteger waitingParties;
    private final AtomicInteger finishedParties;
    private final AtomicBoolean isReset;

    public BusyWaitCyclicBarrier(int parties) {
        this.parties = parties;
        waitingParties = new AtomicInteger(0);
        finishedParties = new AtomicInteger(0);
        isReset = new AtomicBoolean(false);
    }

    public int getParties() {
        return parties;
    }

    public void await() throws InterruptedException, BrokenBarrierException {
        if (waitingParties.incrementAndGet() == parties) {
            finishedParties.incrementAndGet();
            waitingParties.set(0);
            long startTime = System.nanoTime();
            while (finishedParties.get() > 0) {
                if (isReset.get()) {
                    throw new BrokenBarrierException();
                }
                if (System.nanoTime() - startTime > 10_000) {
                    Thread.sleep(0L, 1);
                    startTime = System.nanoTime();
                }
            }
            return;
        }
        // Busy Wait
        long startTime = System.nanoTime();
        while (waitingParties.get() > 0) {
            if (isReset.get()) {
                if (waitingParties.decrementAndGet() == 0) {
                    isReset.set(false);
                }
                throw new BrokenBarrierException();
            }
            if (System.nanoTime() - startTime > 10_000) {
                Thread.sleep(0L, 1);
                startTime = System.nanoTime();
            }
        }
        if (finishedParties.incrementAndGet() == parties) {
            finishedParties.set(0);
            return;
        }
        startTime = System.nanoTime();
        while (finishedParties.get() > 0) {
            if (isReset.get()) {
                throw new BrokenBarrierException();
            }
            if (System.nanoTime() - startTime > 10_000) {
                Thread.sleep(0L, 1);
                startTime = System.nanoTime();
            }
        }
    }

    public void reset() {
        isReset.set(true);
    }
}
