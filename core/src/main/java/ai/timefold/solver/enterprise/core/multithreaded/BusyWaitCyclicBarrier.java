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
            while (finishedParties.get() > 0) {
                if (isReset.get()) {
                    throw new BrokenBarrierException();
                }
                Thread.onSpinWait();
            }
            return;
        }
        while (waitingParties.get() > 0) {
            if (isReset.get()) {
                if (waitingParties.decrementAndGet() == 0) {
                    isReset.set(false);
                }
                throw new BrokenBarrierException();
            }
            Thread.onSpinWait();
        }
        if (finishedParties.incrementAndGet() == parties) {
            finishedParties.set(0);
            return;
        }
        while (finishedParties.get() > 0) {
            if (isReset.get()) {
                throw new BrokenBarrierException();
            }
            Thread.onSpinWait();
        }
    }

    public void reset() {
        isReset.set(true);
    }
}
