package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ai.timefold.solver.core.impl.heuristic.move.Move;

/**
 * A class used to wrap an iterator so that instead of ending when
 * the iterator has no more elements, it will return null.
 * Not thread-safe.
 */
final class NeverEndingMoveGenerator<Solution_> {
    /**
     * The number of {@link NeverEndingMoveGenerator} that still have remaining elements
     */
    final AtomicLong hasNextRemaining;

    /**
     * The move index of the next move returned by this {@link NeverEndingMoveGenerator}'s
     * {@link #generateNextMove()} method.
     */
    final AtomicInteger nextMoveIndex;

    /**
     * The {@link Iterator} that generates new move. Not thread-safe.
     */
    final Iterator<Move<Solution_>> delegateIterator;

    /**
     * The {@link OrderByMoveIndexBlockingQueue} where results will be
     * added. Used so the {@link NeverEndingMoveGenerator} can give it a signal
     * when all {@link NeverEndingMoveGenerator} are exhausted.
     */
    final OrderByMoveIndexBlockingQueue<Solution_> resultQueue;

    /**
     * True if {@link #delegateIterator} potentially have more elements.
     */
    final AtomicBoolean hasNext;

    /**
     * A semaphore that keep track of how many iterator.next() calls are allowed.
     */
    final BusyWaitSemaphore nextCallsAllowedSemaphore;

    /**
     * A boolean that is true when a step been decided.
     */
    final AtomicBoolean stepDecided;

    /**
     * How many units should {@link #nextMoveIndex} should increase by for
     * each move.
     */
    final int increment;

    /**
     * Creates a {@link NeverEndingMoveGenerator} for a given Iterator.
     *
     * @param hasNextRemaining A shared {@link AtomicLong} that keep track
     *        of the number of not exhausted
     *        {@link NeverEndingMoveGenerator} remaining in
     *        a step. Should be set to the number of distinct
     *        {@link NeverEndingMoveGenerator} in a step.
     * @param resultQueue The {@link OrderByMoveIndexBlockingQueue} where results are stored
     * @param delegateIterator The {@link Iterator} that generate new {@link Move}s.
     * @param hasNext A unique {@link AtomicBoolean} to store if the iterator has remaining elements
     * @param stepDecided A shared {@link AtomicBoolean} that tracks if a step has been decided
     * @param offset The initial value of {@link #nextMoveIndex}. At least 0.
     * @param increment How much {@link #nextMoveIndex} increments by for each generated move. At least 1.
     * @param bufferSize How many iterator.next() calls should be buffered. At least 1.
     */
    NeverEndingMoveGenerator(AtomicLong hasNextRemaining,
            OrderByMoveIndexBlockingQueue<Solution_> resultQueue,
            Iterator<Move<Solution_>> delegateIterator,
            AtomicBoolean hasNext,
            AtomicBoolean stepDecided,
            int offset,
            int increment,
            int bufferSize) {
        this.hasNextRemaining = hasNextRemaining;
        this.resultQueue = resultQueue;
        this.delegateIterator = delegateIterator;
        this.hasNext = hasNext;
        this.stepDecided = stepDecided;
        this.nextCallsAllowedSemaphore = new BusyWaitSemaphore(bufferSize);
        this.nextMoveIndex = new AtomicInteger(offset);
        this.increment = increment;
    }

    /**
     * Generate the next move, returning null if the iterator is exhausted. Also
     * signals {@link #resultQueue} when all {@link NeverEndingMoveGenerator} for the
     * current step are exhausted (as calculated from the shared {@link #hasNextRemaining}).
     *
     * @return Sometimes null, the next move generated by {@link #delegateIterator}.
     */
    public Move<Solution_> generateNextMove() {
        boolean shouldCallNext = nextCallsAllowedSemaphore.acquireUntil(stepDecided);

        // Acquire a permit before this, so an exhausted iterator does not
        // override a long eval
        if (!hasNext.getAcquire()) {
            // A previous delegateIterator.hasNext() has return false,
            // so all future calls should return null
            return null;
        }
        if (shouldCallNext && delegateIterator.hasNext()) {
            // There a next element in the iterator, so we should return that
            return delegateIterator.next();
        } else {
            // The iterator has no more elements
            hasNext.setRelease(false);
            if (hasNextRemaining.decrementAndGet() == 0) {
                // If this was the last iterator that had remaining elements,
                // signal the resultQueue that all future calls to addMove
                // and reportIteratorExhausted should block
                resultQueue.blockMoveThreads();
            }
            return null;
        }
    }

    public void incrementPermits() {
        nextCallsAllowedSemaphore.release();
    }

    /**
     * Gets the move index of the next move generated by this {@link NeverEndingMoveGenerator}.
     *
     * @return The move index corresponding to the next {@link #generateNextMove()} call.
     */
    public int getNextMoveIndex() {
        return nextMoveIndex.getAndAdd(increment);
    }
}
