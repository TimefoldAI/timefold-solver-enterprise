package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.*;

import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.heuristic.selector.move.MoveSelector;
import ai.timefold.solver.core.impl.localsearch.decider.LocalSearchDecider;
import ai.timefold.solver.core.impl.localsearch.decider.acceptor.Acceptor;
import ai.timefold.solver.core.impl.localsearch.decider.forager.LocalSearchForager;
import ai.timefold.solver.core.impl.localsearch.scope.LocalSearchMoveScope;
import ai.timefold.solver.core.impl.localsearch.scope.LocalSearchPhaseScope;
import ai.timefold.solver.core.impl.localsearch.scope.LocalSearchStepScope;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.solver.scope.SolverScope;
import ai.timefold.solver.core.impl.solver.termination.Termination;
import ai.timefold.solver.core.impl.solver.thread.ThreadUtils;

/**
 * @param <Solution_> the solution type, the class with the {@link PlanningSolution} annotation
 */
final class MultiThreadedLocalSearchDecider<Solution_> extends LocalSearchDecider<Solution_> {

    private final ThreadFactory threadFactory;
    private final int moveThreadCount;
    private final int selectedMoveBufferSize;
    private final List<MoveSelector<Solution_>> moveSelectorList;
    private final List<Random> workingRandomList;

    private boolean assertStepScoreFromScratch = false;
    private boolean assertExpectedStepScore = false;
    private boolean assertShadowVariablesAreNotStaleAfterStep = false;

    private AtomicReference<MoveThreadOperation<Solution_>> nextSynchronizedOperation;
    private AtomicInteger nextSynchronizedOperationIndex;
    private OrderByMoveIndexBlockingQueue<Solution_> resultQueue;
    private AtomicInteger moveIndex;
    private AtomicReferenceArray<NeverEndingMoveGenerator<Solution_>> iteratorReference;
    private AtomicBoolean stepDecided;
    private ExecutorService executor;
    private List<MoveThreadRunner<Solution_, ?>> moveThreadRunnerList;

    public MultiThreadedLocalSearchDecider(String logIndentation, Termination<Solution_> termination,
            List<MoveSelector<Solution_>> moveSelectorList, List<Random> workingRandomList,
            Acceptor<Solution_> acceptor, LocalSearchForager<Solution_> forager,
            ThreadFactory threadFactory, int moveThreadCount, int selectedMoveBufferSize) {
        super(logIndentation, termination,
                new MoveSelectorInListProxy<>(moveSelectorList, workingRandomList),
                acceptor,
                forager);
        this.threadFactory = threadFactory;
        this.moveThreadCount = moveThreadCount;
        this.selectedMoveBufferSize = selectedMoveBufferSize;
        this.moveSelectorList = moveSelectorList;
        this.workingRandomList = workingRandomList;
        if (moveSelectorList.size() != moveThreadCount) {
            throw new IllegalArgumentException("The moveSelectorList (" + moveSelectorList
                    + ") does not have exactly move thread count (" + moveThreadCount + ") elements");
        }
        if (moveSelectorList.size() != workingRandomList.size()) {
            throw new IllegalArgumentException("The workingRandomList (" + workingRandomList
                    + ") does not have the same size as moveSelectorList (" + moveSelectorList + ").");
        }
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
    public void phaseStarted(LocalSearchPhaseScope<Solution_> phaseScope) {
        super.phaseStarted(phaseScope);
        nextSynchronizedOperation = new AtomicReference<>();
        nextSynchronizedOperationIndex = new AtomicInteger(0);
        stepDecided = new AtomicBoolean(false);
        // Capacity: number of moves in circulation + number of exception handling results
        resultQueue =
                new OrderByMoveIndexBlockingQueue<>(stepDecided, moveThreadCount, selectedMoveBufferSize + moveThreadCount);
        moveIndex = new AtomicInteger(0);
        iteratorReference = new AtomicReferenceArray<>(moveThreadCount);
        InnerScoreDirector<Solution_, ?> scoreDirector = phaseScope.getScoreDirector();
        executor = createThreadPoolExecutor();
        moveThreadRunnerList = new ArrayList<>(moveThreadCount);

        nextSynchronizedOperation.set(new SetupOperation<>(scoreDirector));
        for (int moveThreadIndex = 0; moveThreadIndex < moveThreadCount; moveThreadIndex++) {
            MoveThreadRunner<Solution_, ?> moveThreadRunner = new MoveThreadRunner<>(
                    logIndentation, moveThreadIndex, true,
                    nextSynchronizedOperation, nextSynchronizedOperationIndex, resultQueue,
                    moveIndex, iteratorReference, stepDecided,
                    assertMoveScoreFromScratch, assertExpectedUndoMoveScore,
                    assertStepScoreFromScratch, assertExpectedStepScore, assertShadowVariablesAreNotStaleAfterStep);
            moveThreadRunnerList.add(moveThreadRunner);
            executor.submit(moveThreadRunner);
        }
    }

    @Override
    public void phaseEnded(LocalSearchPhaseScope<Solution_> phaseScope) {
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
    public void decideNextStep(LocalSearchStepScope<Solution_> stepScope) {
        int stepIndex = stepScope.getStepIndex();

        int selectMoveIndex = 0;

        // We will have moveThreadCount == moveSelectorList.size() distinct iterators
        AtomicLong hasNextRemaining = new AtomicLong(moveThreadCount);
        for (int i = 0; i < moveThreadCount; i++) {
            // Use offset i, increment moveThreadCount so the indices generated by each NeverEndingMoveGenerator
            // are unique
            // For instance, for thread count = 4, the sequences generated would be:
            // [0, 4, 8, ...]
            // [1, 5, 9, ...]
            // [2, 6, 10, ...]
            // [3, 7, 11, ...]
            NeverEndingMoveGenerator<Solution_> sharedGenerator = new NeverEndingMoveGenerator<>(hasNextRemaining,
                    resultQueue, moveSelectorList.get(i).iterator(), new AtomicBoolean(true), stepDecided,
                    i, moveThreadCount, selectedMoveBufferSize / moveThreadCount);
            iteratorReference.set(i, sharedGenerator);
        }
        moveIndex.set(0);
        stepDecided.set(false);

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
            if (forageResult(stepScope, stepIndex)) {
                break;
            }
            iteratorReference.get(selectMoveIndex % moveThreadCount).incrementPermits();
            selectMoveIndex++;
        }
        stepDecided.set(true);
        pickMove(stepScope);

        // Wait for all threads to finish generating and evaluating moves
        resultQueue.deciderSyncOnEnd();

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

    private boolean forageResult(LocalSearchStepScope<Solution_> stepScope, int stepIndex) {
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
        LocalSearchMoveScope<Solution_> moveScope = new LocalSearchMoveScope<>(stepScope, foragingMoveIndex, foragingMove);
        if (!result.isMoveDoable()) {
            throw new IllegalStateException("Impossible state: Local search move selector (" + moveSelector
                    + ") provided a non-doable move (" + result.getMove() + ").");
        }
        moveScope.setScore(result.getScore());
        // Every doable move result represents a single score calculation on a move thread.
        moveScope.getScoreDirector().incrementCalculationCount();
        boolean accepted = acceptor.isAccepted(moveScope);
        moveScope.setAccepted(accepted);
        logger.trace("{}        Move index ({}), score ({}), accepted ({}), move ({}).",
                logIndentation,
                foragingMoveIndex, moveScope.getScore(), moveScope.getAccepted(),
                foragingMove);
        forager.addMove(moveScope);
        if (forager.isQuitEarly()) {
            return true;
        }
        stepScope.getPhaseScope().getSolverScope().checkYielding();
        return termination.isPhaseTerminated(stepScope.getPhaseScope());
    }

    private void shutdownMoveThreads() {
        if (executor != null && !executor.isShutdown()) {
            ThreadUtils.shutdownAwaitOrKill(executor, logIndentation, "Multi-threaded Local Search");
        }
    }
}
