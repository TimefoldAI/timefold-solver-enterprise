package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.impl.heuristic.move.Move;

final class OrderByMoveIndexBlockingQueue<Solution_> {
    private final AtomicReferenceArray<MoveResult<Solution_>> moveResultRingBuffer;

    // Semaphores are reused/never change, and thus don't need to be in an AtomicReferenceArray
    private final Semaphore[] spaceAvailableInRingBufferSemaphores;
    private final Semaphore[] resultAvailableInRingBufferSemaphores;
    private final CyclicBarrier syncDeciderAndMoveThreadsStartBarrier;
    private final CyclicBarrier syncDeciderAndMoveThreadsEndBarrier;
    private final AtomicBoolean syncDeciderAndMoveThreads;

    private int filterStepIndex = Integer.MIN_VALUE;
    private int nextMoveIndex = Integer.MIN_VALUE;

    public OrderByMoveIndexBlockingQueue(int threadCount, int capacity) {
        // all move threads + decider
        syncDeciderAndMoveThreadsStartBarrier = new CyclicBarrier(threadCount + 1);
        syncDeciderAndMoveThreadsEndBarrier = new CyclicBarrier(threadCount + 1);
        syncDeciderAndMoveThreads = new AtomicBoolean(false);

        moveResultRingBuffer = new AtomicReferenceArray<>(capacity);
        spaceAvailableInRingBufferSemaphores = new Semaphore[capacity];
        resultAvailableInRingBufferSemaphores = new Semaphore[capacity];
        for (int i = 0; i < capacity; i++) {
            spaceAvailableInRingBufferSemaphores[i] = new Semaphore(1);
            resultAvailableInRingBufferSemaphores[i] = new Semaphore(0);
        }
    }

    public int getMoveThreadCount() {
        return syncDeciderAndMoveThreadsStartBarrier.getParties() - 1;
    }

    /**
     * Not thread-safe. Can only be called from the solver thread.
     *
     * @param stepIndex at least 0
     */
    public void startNextStep(int stepIndex) {
        if (filterStepIndex >= stepIndex) {
            throw new IllegalStateException("The old filterStepIndex (" + filterStepIndex
                    + ") must be less than the stepIndex (" + stepIndex + ")");
        }
        filterStepIndex = stepIndex;
        MoveResult<Solution_> exceptionResult = null;
        for (int i = 0; i < moveResultRingBuffer.length(); i++) {
            exceptionResult = moveResultRingBuffer.get(i);
            if (exceptionResult != null && exceptionResult.hasThrownException()) {
                break;
            } else {
                exceptionResult = null;
            }
        }
        if (exceptionResult != null) {
            throw new IllegalStateException("The move thread with moveThreadIndex ("
                    + exceptionResult.getMoveThreadIndex() + ") has thrown an exception."
                    + " Relayed here in the parent thread.",
                    exceptionResult.getThrowable());
        }
        for (int i = 0; i < moveResultRingBuffer.length(); i++) {
            moveResultRingBuffer.setRelease(i, null);
            resultAvailableInRingBufferSemaphores[i].drainPermits();
        }

        for (Semaphore spaceAvailableInRingBufferSemaphore : spaceAvailableInRingBufferSemaphores) {
            // make each semaphore have exactly 1 permit available
            spaceAvailableInRingBufferSemaphore.drainPermits();
            spaceAvailableInRingBufferSemaphore.release(1);
        }
        nextMoveIndex = 0;
        syncDeciderAndMoveThreads.setRelease(false);
        try {
            syncDeciderAndMoveThreadsStartBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    public void endPhase() {
        try {
            syncDeciderAndMoveThreadsStartBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    public void reserveSpaceForMove(int index) {
        int ringBufferSlot = index % moveResultRingBuffer.length();
        try {
            spaceAvailableInRingBufferSemaphores[ringBufferSlot].acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This method is thread-safe. It can be called from any move thread.
     *
     * @param moveThreadIndex {@code 0 <= moveThreadIndex < moveThreadCount}
     * @param stepIndex at least 0
     * @param moveIndex at least 0
     * @param move never null
     * @see BlockingQueue#add(Object)
     */
    public void addUndoableMove(int moveThreadIndex, int stepIndex, int moveIndex, Move<Solution_> move) {
        int ringBufferSlot = moveIndex % moveResultRingBuffer.length();
        MoveResult<Solution_> result = new MoveResult<>(moveThreadIndex, stepIndex, moveIndex, move, false, null);
        if (result.getStepIndex() != filterStepIndex) {
            // Discard element from previous step
            spaceAvailableInRingBufferSemaphores[ringBufferSlot].release();
            return;
        }
        moveResultRingBuffer.setRelease(ringBufferSlot, result);
        resultAvailableInRingBufferSemaphores[ringBufferSlot].release();
        if (syncDeciderAndMoveThreads.getAcquire()) {
            try {
                syncDeciderAndMoveThreadsEndBarrier.await();
                syncDeciderAndMoveThreadsStartBarrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * This method is thread-safe. It can be called from any move thread.
     *
     * @param moveThreadIndex {@code 0 <= moveThreadIndex < moveThreadCount}
     * @param stepIndex at least 0
     * @param moveIndex at least 0
     * @param move never null
     * @param score never null
     * @see BlockingQueue#add(Object)
     */
    public void addMove(int moveThreadIndex, int stepIndex, int moveIndex, Move<Solution_> move, Score score) {
        int ringBufferSlot = moveIndex % moveResultRingBuffer.length();
        MoveResult<Solution_> result = new MoveResult<>(moveThreadIndex, stepIndex, moveIndex, move, true, score);
        if (result.getStepIndex() != filterStepIndex) {
            // Discard element from previous step
            spaceAvailableInRingBufferSemaphores[ringBufferSlot].release();
            return;
        }
        moveResultRingBuffer.setRelease(ringBufferSlot, result);
        resultAvailableInRingBufferSemaphores[ringBufferSlot].release();
        if (syncDeciderAndMoveThreads.getAcquire()) {
            try {
                syncDeciderAndMoveThreadsEndBarrier.await();
                syncDeciderAndMoveThreadsStartBarrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void reportIteratorExhausted(int stepIndex, int moveIndex) {
        int ringBufferSlot = moveIndex % moveResultRingBuffer.length();
        if (stepIndex != filterStepIndex) {
            // Discard element from previous step
            spaceAvailableInRingBufferSemaphores[ringBufferSlot].release();
            return;
        }
        moveResultRingBuffer.setRelease(ringBufferSlot, null);
        resultAvailableInRingBufferSemaphores[ringBufferSlot].release();
        if (syncDeciderAndMoveThreads.getAcquire()) {
            try {
                syncDeciderAndMoveThreadsEndBarrier.await();
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
        MoveResult<Solution_> result = new MoveResult<>(moveThreadIndex, moveIndex, throwable);
        int ringBufferSlot = moveIndex % moveResultRingBuffer.length();
        moveResultRingBuffer.setRelease(ringBufferSlot, result);
        resultAvailableInRingBufferSemaphores[ringBufferSlot].release();
        syncDeciderAndMoveThreadsEndBarrier.reset();
    }

    /**
     * Not thread-safe. Can only be called from the solver thread.
     *
     * @return never null
     * @throws InterruptedException if interrupted
     * @see BlockingQueue#take()
     */
    public MoveResult<Solution_> take() throws InterruptedException {
        int moveIndex = nextMoveIndex;
        nextMoveIndex++;
        int ringBufferIndex = moveIndex % moveResultRingBuffer.length();
        resultAvailableInRingBufferSemaphores[ringBufferIndex].acquire();
        MoveResult<Solution_> result = moveResultRingBuffer.getAcquire(ringBufferIndex);
        moveResultRingBuffer.setRelease(ringBufferIndex, null);
        spaceAvailableInRingBufferSemaphores[ringBufferIndex].release();
        if (result == null) {
            // iterator exhausted
            return null;
        }
        // If 2 exceptions are added from different threads concurrently, either one could end up first.
        // This is a known deviation from 100% reproducibility, that never occurs in a success scenario.
        if (result.hasThrownException()) {
            throw new IllegalStateException("The move thread with moveThreadIndex ("
                    + result.getMoveThreadIndex() + ") has thrown an exception."
                    + " Relayed here in the parent thread.",
                    result.getThrowable());
        }
        return result;
    }

    public void waitForDecider() {
        try {
            syncDeciderAndMoveThreadsStartBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    public void blockMoveThreads() {
        syncDeciderAndMoveThreads.setRelease(true);
    }

    public void deciderSyncOnEnd() {
        try {
            int threadCount = syncDeciderAndMoveThreadsEndBarrier.getParties();
            // Need to release permits for results we have not consumed so all threads
            // will reach barrier
            for (Semaphore semaphore : spaceAvailableInRingBufferSemaphores) {
                semaphore.release(threadCount);
            }
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
