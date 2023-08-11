package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.*;

import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.impl.constructionheuristic.decider.ConstructionHeuristicDecider;
import ai.timefold.solver.core.impl.constructionheuristic.decider.forager.ConstructionHeuristicForager;
import ai.timefold.solver.core.impl.constructionheuristic.placer.Placement;
import ai.timefold.solver.core.impl.constructionheuristic.scope.ConstructionHeuristicMoveScope;
import ai.timefold.solver.core.impl.constructionheuristic.scope.ConstructionHeuristicPhaseScope;
import ai.timefold.solver.core.impl.constructionheuristic.scope.ConstructionHeuristicStepScope;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.solver.scope.SolverScope;
import ai.timefold.solver.core.impl.solver.termination.Termination;
import ai.timefold.solver.core.impl.solver.thread.ThreadUtils;

/**
 * @param <Solution_> the solution type, the class with the {@link PlanningSolution} annotation
 */
final class MultiThreadedConstructionHeuristicDecider<Solution_> extends ConstructionHeuristicDecider<Solution_> {

    private final ThreadFactory threadFactory;
    private final int moveThreadCount;
    private final int selectedMoveBufferSize;

    private boolean assertStepScoreFromScratch = false;
    private boolean assertExpectedStepScore = false;
    private boolean assertShadowVariablesAreNotStaleAfterStep = false;

    private AtomicReference<MoveThreadOperation<Solution_>> nextSynchronizedOperation;
    private AtomicInteger nextSynchronizedOperationIndex;
    private OrderByMoveIndexBlockingQueue<Solution_> resultQueue;
    private AtomicInteger moveIndex;
    private AtomicReferenceArray<NeverEndingMoveGenerator<Solution_>> iteratorReference;
    private ExecutorService executor;
    private List<MoveThreadRunner<Solution_, ?>> moveThreadRunnerList;

    public MultiThreadedConstructionHeuristicDecider(String logIndentation, Termination<Solution_> termination,
            ConstructionHeuristicForager<Solution_> forager, ThreadFactory threadFactory, int moveThreadCount,
            int selectedMoveBufferSize) {
        super(logIndentation, termination, forager);
        this.threadFactory = threadFactory;
        this.moveThreadCount = moveThreadCount;
        this.selectedMoveBufferSize = selectedMoveBufferSize;
    }

    public void setAssertStepScoreFromScratch(boolean assertStepScoreFromScratch) {
        this.assertStepScoreFromScratch = assertStepScoreFromScratch;
    }

    public void setAssertExpectedStepScore(boolean assertExpectedStepScore) {
        this.assertExpectedStepScore = assertExpectedStepScore;
    }

    public void setAssertShadowVariablesAreNotStaleAfterStep(boolean assertShadowVariablesAreNotStaleAfterStep) {
        this.assertShadowVariablesAreNotStaleAfterStep = assertShadowVariablesAreNotStaleAfterStep;
    }

    @Override
    public void phaseStarted(ConstructionHeuristicPhaseScope<Solution_> phaseScope) {
        super.phaseStarted(phaseScope);
        nextSynchronizedOperation = new AtomicReference<>();
        nextSynchronizedOperationIndex = new AtomicInteger(0);
        // Capacity: number of moves in circulation + number of exception handling results
        resultQueue = new OrderByMoveIndexBlockingQueue<>(moveThreadCount, selectedMoveBufferSize + moveThreadCount);
        moveIndex = new AtomicInteger(0);
        iteratorReference = new AtomicReferenceArray<>(moveThreadCount);
        InnerScoreDirector<Solution_, ?> scoreDirector = phaseScope.getScoreDirector();
        executor = createThreadPoolExecutor();
        moveThreadRunnerList = new ArrayList<>(moveThreadCount);

        nextSynchronizedOperation.set(new SetupOperation<>(scoreDirector));
        for (int moveThreadIndex = 0; moveThreadIndex < moveThreadCount; moveThreadIndex++) {
            MoveThreadRunner<Solution_, ?> moveThreadRunner = new MoveThreadRunner<>(
                    logIndentation, moveThreadIndex, false,
                    nextSynchronizedOperation, nextSynchronizedOperationIndex, resultQueue,
                    moveIndex, iteratorReference,
                    assertMoveScoreFromScratch, assertExpectedUndoMoveScore,
                    assertStepScoreFromScratch, assertExpectedStepScore, assertShadowVariablesAreNotStaleAfterStep);
            moveThreadRunnerList.add(moveThreadRunner);
            executor.submit(moveThreadRunner);
        }
    }

    @Override
    public void phaseEnded(ConstructionHeuristicPhaseScope<Solution_> phaseScope) {
        super.phaseEnded(phaseScope);
        DestroyOperation<Solution_> destroyOperation = new DestroyOperation<>();
        // Set to -2, since unless step index overflows, no stepIndex in MoveThreadRunner
        // will be equal to it (it is set to -1 initially, so cannot use that).
        nextSynchronizedOperationIndex.set(-2);
        nextSynchronizedOperation.set(destroyOperation);

        // Unblock the Move Threads, so they will get the DestroyOperation
        resultQueue.endPhase();

        // Wait for all Move Threads to consume the DestroyOperation
        shutdownMoveThreads();

        // Update the scoreCalculationCount
        long childThreadsScoreCalculationCount = 0;
        for (MoveThreadRunner<Solution_, ?> moveThreadRunner : moveThreadRunnerList) {
            childThreadsScoreCalculationCount += moveThreadRunner.getCalculationCount();
        }
        phaseScope.addChildThreadsScoreCalculationCount(childThreadsScoreCalculationCount);
        nextSynchronizedOperation = null;
        nextSynchronizedOperationIndex = null;
        resultQueue = null;
        moveThreadRunnerList = null;
    }

    @Override
    public void solvingError(SolverScope<Solution_> solverScope, Exception exception) {
        super.solvingError(solverScope, exception);
        shutdownMoveThreads();
    }

    private ExecutorService createThreadPoolExecutor() {
        ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(moveThreadCount,
                threadFactory);
        if (threadPoolExecutor.getMaximumPoolSize() < moveThreadCount) {
            throw new IllegalStateException(
                    "The threadPoolExecutor's maximumPoolSize (" + threadPoolExecutor.getMaximumPoolSize()
                            + ") is less than the moveThreadCount (" + moveThreadCount + "), this is unsupported.");
        }
        return threadPoolExecutor;
    }

    @Override
    public void decideNextStep(ConstructionHeuristicStepScope<Solution_> stepScope, Placement<Solution_> placement) {
        int stepIndex = stepScope.getStepIndex();

        int selectMoveIndex = 0;

        // Placement does not have a way to create multiple independent MoveSelectors,
        // so we will use a shared NeverEndingMoveGenerator in the Construction Heuristic
        AtomicLong hasNextRemaining;
        AtomicBoolean hasNextShared;
        Iterator<Move<Solution_>> sharedIterator;

        // 1 distinct generator -> hasNextRemaining = 1
        hasNextRemaining = new AtomicLong(1);
        hasNextShared = new AtomicBoolean(true);
        sharedIterator = placement.iterator();

        // offset 0, increment 1, to generate indices [0, 1, 2, 3, ...]
        // since there is only one NeverEndingMoveGenerator
        NeverEndingMoveGenerator<Solution_> sharedGenerator = new NeverEndingMoveGenerator<>(hasNextRemaining,
                resultQueue, sharedIterator, hasNextShared, 0, 1);
        for (int i = 0; i < moveThreadCount; i++) {
            iteratorReference.set(i, sharedGenerator);
        }
        moveIndex.set(0);

        // All variables set up, unblock move generators
        resultQueue.startNextStep(stepIndex);

        // moveIndex = number of generated moves so far
        // selectMoveIndex = number of moves foraged so far
        // if selectMoveIndex < moveIndex, then we still want to forage
        // even if the resultQueue is blocking (as there are moves waiting
        // in the resultQueue that we haven't consumed).
        // If the resultQueue is blocking, that mean the iterator is exhausted
        // and no more moves will come in
        while (selectMoveIndex < moveIndex.get() || !resultQueue.checkIfBlocking()) {
            // Forage the next move from the result queue
            if (forageResult(stepScope, stepIndex)) {
                break;
            }
            selectMoveIndex++;
        }

        // Do not evaluate the remaining selected moves for this step that haven't started evaluation yet
        resultQueue.blockMoveThreads();

        pickMove(stepScope);

        // Wait for all threads to finish evaluating moves
        resultQueue.deciderSyncOnEnd();

        // Set Random state to a deterministic value
        stepScope.getPhaseScope()
                .getSolverScope()
                .getWorkingRandom()
                .setSeed(((long) stepIndex << 32L) | stepScope.getSelectedMoveCount());

        if (stepScope.getStep() != null) {
            InnerScoreDirector<Solution_, ?> scoreDirector = stepScope.getScoreDirector();
            if (scoreDirector.requiresFlushing() && stepIndex % 100 == 99) {
                // Calculate score to process changes; otherwise they become a memory leak.
                // We only do it occasionally, as score calculation is a performance cost we do not need to incur here.
                scoreDirector.calculateScore();
            }
            // Increase stepIndex by 1, because it's a preliminary action
            ApplyStepOperation<Solution_, ?> stepOperation = new ApplyStepOperation<>(stepIndex + 1,
                    stepScope.getStep(), (Score) stepScope.getScore());
            nextSynchronizedOperationIndex.set(stepIndex + 1);
            nextSynchronizedOperation.set(stepOperation);
        }
    }

    private boolean forageResult(ConstructionHeuristicStepScope<Solution_> stepScope, int stepIndex) {
        OrderByMoveIndexBlockingQueue.MoveResult<Solution_> result;
        try {
            result = resultQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }

        if (result == null) {
            // The iterator is exhausted
            stepScope.getPhaseScope().getSolverScope().checkYielding();
            return termination.isPhaseTerminated(stepScope.getPhaseScope());
        }
        if (stepIndex != result.getStepIndex()) {
            throw new IllegalStateException("Impossible situation: the solverThread's stepIndex (" + stepIndex
                    + ") differs from the result's stepIndex (" + result.getStepIndex() + ").");
        }
        Move<Solution_> foragingMove = result.getMove().rebase(stepScope.getScoreDirector());
        int foragingMoveIndex = result.getMoveIndex();
        ConstructionHeuristicMoveScope<Solution_> moveScope = new ConstructionHeuristicMoveScope<>(stepScope, foragingMoveIndex,
                foragingMove);
        if (!result.isMoveDoable()) {
            throw new IllegalStateException("Impossible situation: Construction Heuristics move is not doable.");
        }
        moveScope.setScore(result.getScore());
        // Every doable move result represents a single score calculation on a move thread.
        moveScope.getScoreDirector().incrementCalculationCount();
        logger.trace("{}        Move index ({}), score ({}), move ({}).",
                logIndentation,
                foragingMoveIndex, moveScope.getScore(), foragingMove);
        forager.addMove(moveScope);
        if (forager.isQuitEarly()) {
            return true;
        }
        stepScope.getPhaseScope().getSolverScope().checkYielding();
        return termination.isPhaseTerminated(stepScope.getPhaseScope());
    }

    private void shutdownMoveThreads() {
        if (executor != null && !executor.isShutdown()) {
            ThreadUtils.shutdownAwaitOrKill(executor, logIndentation, "Multi-threaded Construction Heuristic");
        }
    }
}
