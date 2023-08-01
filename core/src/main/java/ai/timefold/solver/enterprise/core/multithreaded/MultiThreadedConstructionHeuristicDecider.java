package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

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

    private BlockingQueue<MoveThreadOperation<Solution_>> operationQueue;
    private OrderByMoveIndexBlockingQueue<Solution_> resultQueue;
    private CyclicBarrier moveThreadBarrier;
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
        // Capacity: number of moves in circulation + number of setup xor step operations + number of destroy operations
        operationQueue = new ArrayBlockingQueue<>(selectedMoveBufferSize + moveThreadCount + moveThreadCount);
        // Capacity: number of moves in circulation + number of exception handling results
        resultQueue = new OrderByMoveIndexBlockingQueue<>(moveThreadCount, selectedMoveBufferSize + moveThreadCount);
        moveThreadBarrier = new CyclicBarrier(moveThreadCount);
        moveIndex = new AtomicInteger(0);
        iteratorReference = new AtomicReferenceArray<>(moveThreadCount);
        InnerScoreDirector<Solution_, ?> scoreDirector = phaseScope.getScoreDirector();
        executor = createThreadPoolExecutor();
        moveThreadRunnerList = new ArrayList<>(moveThreadCount);

        for (int moveThreadIndex = 0; moveThreadIndex < moveThreadCount; moveThreadIndex++) {
            MoveThreadRunner<Solution_, ?> moveThreadRunner = new MoveThreadRunner<>(
                    logIndentation, moveThreadIndex, false,
                    operationQueue, resultQueue, moveThreadBarrier, moveIndex, iteratorReference,
                    assertMoveScoreFromScratch, assertExpectedUndoMoveScore,
                    assertStepScoreFromScratch, assertExpectedStepScore, assertShadowVariablesAreNotStaleAfterStep);
            moveThreadRunnerList.add(moveThreadRunner);
            executor.submit(moveThreadRunner);
            operationQueue.add(new SetupOperation<>(scoreDirector));
        }
    }

    @Override
    public void phaseEnded(ConstructionHeuristicPhaseScope<Solution_> phaseScope) {
        super.phaseEnded(phaseScope);
        // Tell the move thread runners to stop
        // Don't clear the operationsQueue to avoid moveThreadBarrier deadlock:
        // The MoveEvaluationOperations are already cleared and the new ApplyStepOperation isn't added yet.
        DestroyOperation<Solution_> destroyOperation = new DestroyOperation<>();
        for (int i = 0; i < moveThreadCount; i++) {
            operationQueue.add(destroyOperation);
        }
        resultQueue.endPhase();
        shutdownMoveThreads();
        long childThreadsScoreCalculationCount = 0;
        for (MoveThreadRunner<Solution_, ?> moveThreadRunner : moveThreadRunnerList) {
            childThreadsScoreCalculationCount += moveThreadRunner.getCalculationCount();
        }
        phaseScope.addChildThreadsScoreCalculationCount(childThreadsScoreCalculationCount);
        operationQueue = null;
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
        AtomicLong hasNextRemaining;
        AtomicBoolean hasNextShared;
        Iterator<Move<Solution_>> sharedIterator;
        hasNextRemaining = new AtomicLong(1);
        hasNextShared = new AtomicBoolean(true);
        sharedIterator = placement.iterator();
        SharedNeverEndingMoveGenerator<Solution_> sharedGenerator = new SharedNeverEndingMoveGenerator<>(hasNextRemaining,
                resultQueue, sharedIterator, hasNextShared);
        for (int i = 0; i < moveThreadCount; i++) {
            iteratorReference.set(i, sharedGenerator);
        }
        moveIndex.set(0);
        resultQueue.startNextStep(stepIndex);
        while (selectMoveIndex < moveIndex.get() || !resultQueue.checkIfBlocking()) {
            if (forageResult(stepScope, stepIndex)) {
                break;
            }
            selectMoveIndex++;
        }

        // Do not evaluate the remaining selected moves for this step that haven't started evaluation yet
        resultQueue.blockMoveThreads();

        pickMove(stepScope);
        // Start doing the step on every move thread. Don't wait for the stepEnded() event.
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
            for (int i = 0; i < moveThreadCount; i++) {
                operationQueue.add(stepOperation);
            }
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
