package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.impl.heuristic.move.Move;

final class OrderByMoveIndexBlockingQueue<Solution_> {
    /**
     * The results of evaluated moves, stored as a ring buffer.
     */
    private final AtomicReferenceArray<MoveResult<Solution_>> moveResultRingBuffer;

    // Semaphores are reused/never change, and thus don't need to be in an AtomicReferenceArray
    /**
     * {@link BusyWaitSemaphore}s that has a permit available if and only if
     * {@link #moveResultRingBuffer} does not have a result that still need to be consumed
     * at the same index. Same length as {@link #moveResultRingBuffer}.
     * <p/>
     * Move Threads acquire permits, Solver Thread releases them.
     * <p/>
     * Initial permits: 1, Max permits: 1.
     */
    private final BusyWaitSemaphore[] spaceAvailableInRingBufferSemaphores;

    /**
     * {@link BusyWaitSemaphore}s that has a permit available if and only if
     * {@link #moveResultRingBuffer} has a result waiting to be consumed
     * at the same index. Same length as {@link #moveResultRingBuffer}.
     * <p/>
     * Solver Thread acquires permit, Move Threads releases them.
     * <p/>
     * Initial permits: 0, Max permits: 1.
     */
    private final BusyWaitSemaphore[] resultAvailableInRingBufferSemaphores;

    /**
     * A {@link BusyWaitCyclicBarrier} that blocks both the Solver and Move Threads
     * until all parties reach it. This {@link BusyWaitCyclicBarrier} is entered when one
     * of the following conditions hold:
     *
     * <ul>
     * <li>For Move Threads, when a phase is started</li>
     * <li>For Move Threads, when the solver thread is setting variables used by {@link MoveThreadRunner}</li>
     * <li>For the Solver Thread, when all variables used by Move Threads has been set</li>
     * </ul>
     *
     * This is the second barrier that {@link MoveThreadRunner} enter when a step is chosen,
     * used to block the Move Threads from reading variables when they are being set by either
     * {@link MultiThreadedConstructionHeuristicDecider} or {@link MultiThreadedLocalSearchDecider}.
     */
    private final BusyWaitCyclicBarrier syncDeciderAndMoveThreadsStartBarrier;

    /**
     * A {@link BusyWaitCyclicBarrier} that blocks both the Solver and Move Threads
     * until all parties reach it. This {@link BusyWaitCyclicBarrier} is entered when one
     * of the following conditions hold:
     *
     * <ul>
     * <li>The Solver Thread picked a move</li>
     * <li>All iterators are exhausted</li>
     * </ul>
     *
     * This is the first barrier that {@link MoveThreadRunner} enter when a step is chosen,
     * used to block the Solver Thread from corrupting the state of {@link MoveThreadRunner}
     * by modifying variables when they are still being used.
     */
    private final BusyWaitCyclicBarrier syncDeciderAndMoveThreadsEndBarrier;

    /**
     * If true, calls to {@link #addMove(int, int, int, Move, Score)} and
     * {@link #reportIteratorExhausted(int, int)} should block Move Threads.
     */
    private final AtomicBoolean syncDeciderAndMoveThreads;

    /**
     * A boolean that is true when a step been decided.
     */
    final AtomicBoolean stepDecided;

    /**
     * The index of the step to accept moves from. If the {@link MoveResult#stepIndex}
     * of a {@link MoveResult} does not match it, the move should not be added to
     * the {@link #moveResultRingBuffer}.
     *
     */
    private int filterStepIndex = Integer.MIN_VALUE;

    /**
     * The index of the next move that the Solver Thread should take from
     * {@link #moveResultRingBuffer}.
     * <p/>
     * IMPORTANT NOTE: the range of this index
     * is unbounded, so be sure to take its value modulo the {@link #moveResultRingBuffer}
     * length when getting the slot.
     */
    private int nextMoveIndex = Integer.MIN_VALUE;

    /**
     * Creates an {@link OrderByMoveIndexBlockingQueue} for the given number of Move Threads
     * with the specified capacity in its {@link #moveResultRingBuffer}.
     *
     * @param threadCount The number of Move Threads
     * @param capacity The capacity of {@link #moveResultRingBuffer}. Once the buffer is
     *        filled, it will block, so make sure it large enough to minimize/eliminate
     *        potential blocking. There no performance drawback from having it too large;
     *        only additional memory usage (and an extremely minor time spent resetting
     *        additional {@link BusyWaitSemaphore} when a new step is taken).
     */
    public OrderByMoveIndexBlockingQueue(AtomicBoolean stepDecided, int threadCount, int capacity) {
        this.stepDecided = stepDecided;

        // all move threads + decider
        syncDeciderAndMoveThreadsStartBarrier = new BusyWaitCyclicBarrier(threadCount + 1);
        syncDeciderAndMoveThreadsEndBarrier = new BusyWaitCyclicBarrier(threadCount + 1);
        syncDeciderAndMoveThreads = new AtomicBoolean(false);

        moveResultRingBuffer = new AtomicReferenceArray<>(capacity);
        spaceAvailableInRingBufferSemaphores = new BusyWaitSemaphore[capacity];
        resultAvailableInRingBufferSemaphores = new BusyWaitSemaphore[capacity];
        for (int i = 0; i < capacity; i++) {
            spaceAvailableInRingBufferSemaphores[i] = new BusyWaitSemaphore(1);
            resultAvailableInRingBufferSemaphores[i] = new BusyWaitSemaphore(0);
        }
    }

    /**
     * Not thread-safe. Can only be called from the solver thread.
     * Called at the beginning of each step after all variables used
     * by {@link MoveThreadRunner} has been set.
     *
     * @param stepIndex at least 0
     */
    public void startNextStep(int stepIndex) {
        if (filterStepIndex >= stepIndex) {
            throw new IllegalStateException("The old filterStepIndex (" + filterStepIndex
                    + ") must be less than the stepIndex (" + stepIndex + ")");
        }
        filterStepIndex = stepIndex;

        // Check to see if any exceptions were thrown if the last step, and if so, propagate them
        for (int i = 0; i < moveResultRingBuffer.length(); i++) {
            if (moveResultRingBuffer.get(i) != null && moveResultRingBuffer.get(i).hasThrownException()) {
                throw new IllegalStateException("The move thread with moveThreadIndex ("
                        + moveResultRingBuffer.get(i).getMoveThreadIndex() + ") has thrown an exception."
                        + " Relayed here in the parent thread.",
                        moveResultRingBuffer.get(i).getThrowable());
            }
        }

        // Set all prior move results to null and reset all the
        // resultAvailableInRingBufferSemaphores (since no results
        // are available for the current step)
        for (int i = 0; i < moveResultRingBuffer.length(); i++) {
            moveResultRingBuffer.setRelease(i, null);
            resultAvailableInRingBufferSemaphores[i].drainPermits();
        }

        for (BusyWaitSemaphore spaceAvailableInRingBufferSemaphore : spaceAvailableInRingBufferSemaphores) {
            // make each spaceAvailableInRingBufferSemaphore have exactly 1 permit available,
            // since there are no results awaiting to be consumed
            spaceAvailableInRingBufferSemaphore.drainPermits();
            spaceAvailableInRingBufferSemaphore.release();
        }

        // The first move index of a new step is 0
        nextMoveIndex = 0;

        // Unblock the Move Threads
        syncDeciderAndMoveThreads.setRelease(false);
        try {
            syncDeciderAndMoveThreadsStartBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Called by the Solver Thread when a phase ends. Used to
     * unblock the {@link MoveThreadRunner} which are waiting for
     * the next {@link MoveThreadRunner#nextSynchronizedOperation} to
     * be set.
     */
    public void endPhase() {
        try {
            syncDeciderAndMoveThreadsStartBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This method is thread-safe. It can be called from any Move Thread.
     *
     * @param moveThreadIndex {@code 0 <= moveThreadIndex < moveThreadCount}
     * @param stepIndex at least 0
     * @param moveIndex at least 0
     * @param move never null
     * @param score never null
     */
    public void addMove(int moveThreadIndex, int stepIndex, int moveIndex, Move<Solution_> move, Score score) {
        // Take the index modulo the ring buffer size to get the corresponding
        // buffer index
        int ringBufferSlot = moveIndex % moveResultRingBuffer.length();
        MoveResult<Solution_> result = new MoveResult<>(moveThreadIndex, stepIndex, moveIndex, move, true, score);
        if (result.getStepIndex() != filterStepIndex) {
            // Discard element from previous step
            return;
        }

        if (spaceAvailableInRingBufferSemaphores[ringBufferSlot].acquireUntil(stepDecided)) {
            // Do not override old elements if space was not available
            moveResultRingBuffer.setRelease(ringBufferSlot, result);

            // Tell the Solver Thread that a result is available in this slot
            resultAvailableInRingBufferSemaphores[ringBufferSlot].release();
        }
        // All move threads will sync on reportIteratorExhausted
    }

    /**
     * This method is thread-safe. It can be called from any Move Thread.
     *
     * @param stepIndex at least 0, unbounded
     * @param moveIndex at least 0, unbounded
     */
    public void reportIteratorExhausted(int stepIndex, int moveIndex) {
        // Take the index modulo the ring buffer size to get the corresponding
        // buffer index
        int ringBufferSlot = moveIndex % moveResultRingBuffer.length();
        if (stepIndex != filterStepIndex) {
            // Discard element from previous step
            return;
        }

        if (spaceAvailableInRingBufferSemaphores[ringBufferSlot].acquireUntil(stepDecided)) {
            // Do not override old elements if space was not available
            // add null (a sentinel value used to report iterator exhaustion)
            // to the buffer
            moveResultRingBuffer.setRelease(ringBufferSlot, null);

            // Tell the Solver Thread that a result is available in this slot
            resultAvailableInRingBufferSemaphores[ringBufferSlot].release();
        }

        // Check if we should block for an upcoming synchronized operation
        if (syncDeciderAndMoveThreads.getAcquire()) {
            try {
                // First, block here as the Solver Thread is waiting
                // for all Move Threads to be finished using shared variables
                syncDeciderAndMoveThreadsEndBarrier.await();

                // Second, block here until the Solver Thread finished
                // setting all shared variables
                syncDeciderAndMoveThreadsStartBarrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * This method is thread-safe. It can be called from any move thread.
     * Previous results (that haven't been consumed yet), will still be returned during iteration
     * before the iteration throws an exception,
     * unless there's a lower moveIndex that isn't in the queue yet.
     *
     * @param moveIndex the index of the move that generated the exception
     * @param throwable never null
     */
    public void addExceptionThrown(int moveThreadIndex, int moveIndex, Throwable throwable) {
        // Take the index modulo the ring buffer size to get the corresponding
        // buffer index
        MoveResult<Solution_> result = new MoveResult<>(moveThreadIndex, moveIndex, throwable);
        int ringBufferSlot = moveIndex % moveResultRingBuffer.length();

        // Wait for space to be available
        spaceAvailableInRingBufferSemaphores[ringBufferSlot].acquireUntil(stepDecided);

        // Put an exception here regardless if we got a permit or not
        moveResultRingBuffer.setRelease(ringBufferSlot, result);

        // Tell the Solver Thread that a result (in this case, an error) is available in this slot
        resultAvailableInRingBufferSemaphores[ringBufferSlot].release();

        // Reset the syncDeciderAndMoveThreadsEndBarrier in case the Solver Thread is currently
        // waiting for this Thread to join
        syncDeciderAndMoveThreadsEndBarrier.reset();

        // When MoveThreadRunner returns, it will throw a new RuntimeException from throwable.
    }

    /**
     * Not thread-safe. Can only be called from the solver thread.
     *
     * @return Sometimes null, the MoveResult corresponding to {@link #nextMoveIndex}.
     *         Returns null if and only if the iterator corresponding to the given {@link #nextMoveIndex}
     *         is exhausted.
     * @throws InterruptedException if interrupted
     */
    public MoveResult<Solution_> take() throws InterruptedException {
        int moveIndex = nextMoveIndex;
        nextMoveIndex++;

        // Take the index modulo the ring buffer size to get the corresponding
        // buffer index
        int ringBufferIndex = moveIndex % moveResultRingBuffer.length();

        // Wait for a result to be available
        resultAvailableInRingBufferSemaphores[ringBufferIndex].acquire();
        MoveResult<Solution_> result = moveResultRingBuffer.getAcquire(ringBufferIndex);
        moveResultRingBuffer.setRelease(ringBufferIndex, null);

        // Tell the Move Threads that space is available for the given buffer index
        spaceAvailableInRingBufferSemaphores[ringBufferIndex].release();

        if (result == null) {
            // iterator exhausted
            return null;
        }
        if (result.hasThrownException()) {
            throw new IllegalStateException("The move thread with moveThreadIndex ("
                    + result.getMoveThreadIndex() + ") has thrown an exception."
                    + " Relayed here in the parent thread.",
                    result.getThrowable());
        }
        return result;
    }

    /**
     * Called at the beginning of {@link MoveThreadRunner#run()} to block until the Solver Thread finished
     * setting up all variables. Unblocks at the start of the first step.
     */
    public void waitForDecider() {
        try {
            syncDeciderAndMoveThreadsStartBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * When called, all future calls to {@link #addMove(int, int, int, Move, Score)}
     * and {@link #reportIteratorExhausted(int, int)} will block until
     * {@link #startNextStep(int)} is called by the Solver Thread.
     */
    public void blockMoveThreads() {
        syncDeciderAndMoveThreads.setRelease(true);
    }

    /**
     * Called by the Solver Thread at the end of a step. Blocks until
     * all Move Threads reached the {@link #syncDeciderAndMoveThreadsEndBarrier} in
     * either {@link #addMove(int, int, int, Move, Score)} or {@link #reportIteratorExhausted(int, int)}.
     */
    public void deciderSyncOnEnd() {
        try {
            syncDeciderAndMoveThreadsEndBarrier.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (BrokenBarrierException e) {
            for (int i = 0; i < moveResultRingBuffer.length(); i++) {
                MoveResult<Solution_> result = moveResultRingBuffer.getAcquire(i);
                if (result != null && result.hasThrownException()) {
                    throw new IllegalStateException("The move thread with moveThreadIndex ("
                            + result.getMoveThreadIndex() + ") has thrown an exception."
                            + " Relayed here in the parent thread.",
                            result.getThrowable());
                }
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns true if Move Threads will be blocked in {@link #addMove(int, int, int, Move, Score)}
     * or {@link #reportIteratorExhausted(int, int)} calls. Used by the Solver Thread to check
     * if all iterators are exhausted.
     *
     * @return true if and only if Move Threads will be blocked in {@link #addMove(int, int, int, Move, Score)}
     *         or {@link #reportIteratorExhausted(int, int)} calls.
     */
    public boolean checkIfBlocking() {
        return syncDeciderAndMoveThreads.getAcquire();
    }

    public static class MoveResult<Solution_> {

        private final int moveThreadIndex;
        private final int stepIndex;
        private final int moveIndex;
        private final Move<Solution_> move;
        private final boolean moveDoable;
        private final Score score;
        private final Throwable throwable;

        public MoveResult(int moveThreadIndex, int stepIndex, int moveIndex, Move<Solution_> move, boolean moveDoable,
                Score score) {
            this.moveThreadIndex = moveThreadIndex;
            this.stepIndex = stepIndex;
            this.moveIndex = moveIndex;
            this.move = move;
            this.moveDoable = moveDoable;
            this.score = score;
            this.throwable = null;
        }

        public MoveResult(int moveThreadIndex, int moveIndex, Throwable throwable) {
            this.moveThreadIndex = moveThreadIndex;
            this.stepIndex = -1;
            this.moveIndex = moveIndex;
            this.move = null;
            this.moveDoable = false;
            this.score = null;
            this.throwable = throwable;
        }

        private boolean hasThrownException() {
            return throwable != null;
        }

        public int getMoveThreadIndex() {
            return moveThreadIndex;
        }

        public int getStepIndex() {
            return stepIndex;
        }

        public int getMoveIndex() {
            return moveIndex;
        }

        public Move<Solution_> getMove() {
            return move;
        }

        public boolean isMoveDoable() {
            return moveDoable;
        }

        public Score getScore() {
            return score;
        }

        private Throwable getThrowable() {
            return throwable;
        }
    }

}
