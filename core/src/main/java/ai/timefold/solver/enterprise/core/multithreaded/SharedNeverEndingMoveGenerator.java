package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.heuristic.move.NoChangeMove;

public final class SharedNeverEndingMoveGenerator<Solution_> implements NeverEndingMoveGenerator<Solution_> {
    final AtomicLong hasNextRemaining;
    final AtomicInteger nextMoveIndex;
    final Iterator<Move<Solution_>> delegateIterator;
    final OrderByMoveIndexBlockingQueue<Solution_> resultQueue;
    final Semaphore waitForDeciderSemaphore;
    final static NoChangeMove<?> NO_CHANGE_MOVE = new NoChangeMove<>();
    final AtomicBoolean hasNext;

    SharedNeverEndingMoveGenerator(AtomicLong hasNextRemaining,
            OrderByMoveIndexBlockingQueue<Solution_> resultQueue,
            Iterator<Move<Solution_>> delegateIterator,
            Semaphore waitForDeciderSemaphore,
            AtomicBoolean hasNext) {
        this.hasNextRemaining = hasNextRemaining;
        this.resultQueue = resultQueue;
        this.delegateIterator = delegateIterator;
        this.waitForDeciderSemaphore = waitForDeciderSemaphore;
        this.hasNext = hasNext;
        this.nextMoveIndex = new AtomicInteger(0);
    }

    @SuppressWarnings("unchecked")
    public Move<Solution_> generateNextMove() {
        try {
            waitForDeciderSemaphore.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (!hasNext.getAcquire()) {
            return (NoChangeMove<Solution_>) NO_CHANGE_MOVE;
        }
        if (delegateIterator.hasNext()) {
            return delegateIterator.next();
        } else {
            hasNext.setRelease(false);
            if (hasNextRemaining.decrementAndGet() == 0) {
                resultQueue.blockMoveThreads();
            }
            return (NoChangeMove<Solution_>) NO_CHANGE_MOVE;
        }
    }

    @Override
    public int getNextMoveIndex() {
        return nextMoveIndex.getAndIncrement();
    }
}
