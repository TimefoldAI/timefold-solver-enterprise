package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.atomic.*;

import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.solver.thread.ChildThreadType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MoveThreadRunner<Solution_, Score_ extends Score<Score_>> implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MoveThreadRunner.class);

    private final String logIndentation;
    private final int moveThreadIndex;
    /**
     * If {@link Move#isMoveDoable(ScoreDirector)} should be called before a move is evaluated.
     */
    private final boolean evaluateDoable;

    /**
     * The next synchronized operation to take. A synchronized operation syncs a
     * {@link MoveThreadRunner} to either a {@link MultiThreadedConstructionHeuristicDecider}
     * or a {@link MultiThreadedLocalSearchDecider}. The synchronized operations are:
     *
     * <ul>
     * <li>
     * {@link SetupOperation}, used to create a child score director
     * </li>
     * <li>
     * {@link ApplyStepOperation}, used to update the working solution to match
     * the step taken.
     * </li>
     * <li>
     * {@link DestroyOperation}, used to terminate this {@link MoveThreadRunner}.
     * </li>
     * </ul>
     */
    private final AtomicReference<MoveThreadOperation<Solution_>> nextSynchronizedOperation;

    /**
     * The step index of the current {@link #nextSynchronizedOperation}. If it does
     * not match the stepIndex in {@link #run()}, then that means it is a new
     * synchronized operation that need to be taken before evaluating new moves.
     */
    private final AtomicInteger synchronizedOperationIndex;

    /**
     * A result queue used to put the result of move evaluation which blocks
     * when:
     *
     * <ul>
     * <li>There is no space in the queue</li>
     * <li>A synchronized operation need to be taken</li>
     * </ul>
     */
    private final OrderByMoveIndexBlockingQueue<Solution_> resultQueue;

    /**
     * The total number of moves that are generated in this step so far
     * (including moves to be generated).
     */
    private final AtomicInteger moveIndex;

    /**
     * An array of {@link NeverEndingMoveGenerator} to use when generating a move.
     * There are two cases:
     *
     * <ul>
     * <li>
     * When {@link MultiThreadedConstructionHeuristicDecider} is used, all items
     * in the array points to the same {@link NeverEndingMoveGenerator} (since
     * {@link ai.timefold.solver.core.impl.constructionheuristic.placer.Placement}
     * does not have a method to return multiple iterators).
     * </li>
     * <li>
     * When {@link MultiThreadedLocalSearchDecider} is used, all items in the array
     * points to distinct {@link NeverEndingMoveGenerator}.
     * </li>
     * </ul>
     *
     * The {@link NeverEndingMoveGenerator} are not thread-safe, and thus should be synchronized
     * on when accessed.
     */
    private final AtomicReferenceArray<NeverEndingMoveGenerator<Solution_>> iteratorReference;

    /**
     * A boolean that is true when a step been decided.
     */
    final AtomicBoolean stepDecided;

    private final boolean assertMoveScoreFromScratch;
    private final boolean assertExpectedUndoMoveScore;
    private final boolean assertStepScoreFromScratch;
    private final boolean assertExpectedStepScore;
    private final boolean assertShadowVariablesAreNotStaleAfterStep;

    private InnerScoreDirector<Solution_, Score_> scoreDirector = null;
    private final AtomicLong calculationCount = new AtomicLong(-1);

    public MoveThreadRunner(String logIndentation, int moveThreadIndex, boolean evaluateDoable,
            AtomicReference<MoveThreadOperation<Solution_>> nextSynchronizedOperation,
            AtomicInteger synchronizedOperationIndex,
            OrderByMoveIndexBlockingQueue<Solution_> resultQueue,
            AtomicInteger moveIndex,
            AtomicReferenceArray<NeverEndingMoveGenerator<Solution_>> iteratorReference,
            AtomicBoolean stepDecided,
            boolean assertMoveScoreFromScratch, boolean assertExpectedUndoMoveScore,
            boolean assertStepScoreFromScratch, boolean assertExpectedStepScore,
            boolean assertShadowVariablesAreNotStaleAfterStep) {
        this.logIndentation = logIndentation;
        this.moveThreadIndex = moveThreadIndex;
        this.evaluateDoable = evaluateDoable;
        this.nextSynchronizedOperation = nextSynchronizedOperation;
        this.synchronizedOperationIndex = synchronizedOperationIndex;
        this.resultQueue = resultQueue;
        this.moveIndex = moveIndex;
        this.iteratorReference = iteratorReference;
        this.stepDecided = stepDecided;
        this.assertMoveScoreFromScratch = assertMoveScoreFromScratch;
        this.assertExpectedUndoMoveScore = assertExpectedUndoMoveScore;
        this.assertStepScoreFromScratch = assertStepScoreFromScratch;
        this.assertExpectedStepScore = assertExpectedStepScore;
        this.assertShadowVariablesAreNotStaleAfterStep = assertShadowVariablesAreNotStaleAfterStep;
    }

    @Override
    public void run() {
        int generatedMoveIndex = -1;
        int stepIndex = -1;
        try {
            Score_ lastStepScore = null;
            resultQueue.waitForDecider();
            // Wait for the iteratorLock to be available before entering the loop
            while (true) {
                MoveThreadOperation<Solution_> operation;
                if (synchronizedOperationIndex.get() != stepIndex) {
                    // Our stepIndex is out of sync with the decider's step index,
                    // so we should take the nextSynchronizedOperation
                    // Note: we do not clear nextSynchronizedOperation, since it
                    // is a small object, and will be cleared when the next
                    // synchronized operation is set.
                    operation = nextSynchronizedOperation.get();
                } else {
                    // Our stepIndex is in sync with the decider's step index,
                    // so we should generate and evaluate a new move

                    // First, get our "ideal" move index, so we know what iterator
                    // to use
                    generatedMoveIndex = moveIndex.getAndIncrement();

                    // There is no "atomic add and mod", so we atomically increment and mod
                    // after (this can cause issues if moveIndex ever exceeds Integer.MAX_VALUE,
                    // but that should never happen)
                    NeverEndingMoveGenerator<Solution_> neverEndingMoveGenerator =
                            iteratorReference.get(generatedMoveIndex % iteratorReference.length());

                    // synchronize on the neverEndingMoveGenerator, so if a move evaluation
                    // was particular fast and another MoveThreadRunner got the same iterator,
                    // it will wait for this MoveThreadRunner to finish generating the Move
                    // before entering this block.
                    synchronized (neverEndingMoveGenerator) {
                        // Get the actual move index, since the first Thread to reach
                        // a synchronized block is not necessary the first thread to enter
                        // it. This will be generatedMoveIndex plus a multiple of the number
                        // of distinct generators in iteratorReference
                        generatedMoveIndex = neverEndingMoveGenerator.getNextMoveIndex();
                        LOGGER.trace(
                                "{}            Move thread ({}) step: step index ({}), move index ({}) Generating move.",
                                logIndentation, moveThreadIndex, stepIndex, generatedMoveIndex);

                        // Generate the MoveEvaluationOperation
                        // We do not need to block here, since generateNextMove is protected by
                        // a Semaphore that will have at most (moveBufferSize / moveThreadCount) permits.
                        // This prevents a newer result from overriding an older result, since there
                        // are exactly (moveBufferSize / moveThreadCount) slots per iterator, and a permit
                        // is only granted when the solver forages a move. So this is what happens for
                        // a long move (moveBufferSize / moveThreadCount = 4):
                        //
                        // M0(0)----------------------------------------
                        // P:3->2   P:2->1   P:1->0                      P:0->1->0
                        // M1(1) -- M2(2) -- M3(3) -- M4(0, blocked) --- M4(0, unblocked) --
                        operation = new MoveEvaluationOperation<>(stepIndex, generatedMoveIndex,
                                neverEndingMoveGenerator.generateNextMove());
                        LOGGER.trace(
                                "{}            Move thread ({}) step: step index ({}), move index ({}) Generated move.",
                                logIndentation, moveThreadIndex, stepIndex, generatedMoveIndex);
                    }
                }

                if (operation instanceof SetupOperation) {
                    // Cannot be replaced by pattern variable; "cannot be safely cast" because of parameterization
                    SetupOperation<Solution_, Score_> setupOperation = (SetupOperation<Solution_, Score_>) operation;
                    scoreDirector = setupOperation.getScoreDirector()
                            .createChildThreadScoreDirector(ChildThreadType.MOVE_THREAD);
                    // stepIndex is 0 because this is the first operation performed in a phase
                    stepIndex = 0;
                    lastStepScore = scoreDirector.calculateScore();
                    LOGGER.trace("{}            Move thread ({}) setup: step index ({}), score ({}).",
                            logIndentation, moveThreadIndex, stepIndex, lastStepScore);
                } else if (operation instanceof DestroyOperation) {
                    LOGGER.trace("{}            Move thread ({}) destroy: step index ({}).",
                            logIndentation, moveThreadIndex, stepIndex);
                    // Set this calculationCount to the scoreDirector's calculation count, so
                    // the solver can report an accurate score calculation speed.
                    calculationCount.set(scoreDirector.getCalculationCount());
                    break;
                } else if (operation instanceof ApplyStepOperation) {
                    // Cannot be replaced by pattern variable; "cannot be safely cast" because of parameterization
                    // Because threads do not take operations from a queue, but instead look at the current
                    // nextSynchronizedOperation and synchronizedOperationIndex, the thread does not need
                    // to wait for other threads to consume the current synchronized operation before
                    // generating moves
                    ApplyStepOperation<Solution_, Score_> applyStepOperation =
                            (ApplyStepOperation<Solution_, Score_>) operation;
                    if (stepIndex + 1 != applyStepOperation.getStepIndex()) {
                        throw new IllegalStateException("Impossible situation: the moveThread's stepIndex (" + stepIndex
                                + ") is not followed by the operation's stepIndex ("
                                + applyStepOperation.getStepIndex() + ").");
                    }
                    stepIndex = applyStepOperation.getStepIndex();
                    Move<Solution_> step = applyStepOperation.getStep().rebase(scoreDirector);
                    Score_ score = applyStepOperation.getScore();
                    step.doMoveOnly(scoreDirector);
                    predictWorkingStepScore(step, score);
                    lastStepScore = score;
                    LOGGER.trace("{}            Move thread ({}) step: step index ({}), score ({}).",
                            logIndentation, moveThreadIndex, stepIndex, lastStepScore);
                } else if (operation instanceof MoveEvaluationOperation<Solution_> moveEvaluationOperation) {
                    int moveIndex = moveEvaluationOperation.getMoveIndex();
                    if (stepIndex != moveEvaluationOperation.getStepIndex()) {
                        throw new IllegalStateException("Impossible situation: the moveThread's stepIndex ("
                                + stepIndex + ") differs from the operation's stepIndex ("
                                + moveEvaluationOperation.getStepIndex() + ") with moveIndex ("
                                + moveIndex + ").");
                    }
                    Move<Solution_> move = moveEvaluationOperation.getMove();
                    if (move == null || stepDecided.get()) {
                        // If the generated move is null, this mean the iterator is exhausted.
                        // We call reportIteratorExhausted so the thread will be blocked
                        // when all iterators are exhausted or if the decider picked a move.
                        //
                        // Alternatively, if the step has already been decided,
                        // it is worthless for us to evaluate the move we got
                        LOGGER.trace(
                                "{}            Move thread ({}) evaluation: step index ({}), move index ({}), iterator exhausted.",
                                logIndentation, moveThreadIndex, stepIndex, moveIndex);
                        resultQueue.reportIteratorExhausted(stepIndex, moveIndex);
                        continue;
                    }
                    move = move.rebase(scoreDirector);
                    if (evaluateDoable && !move.isMoveDoable(scoreDirector)) {
                        /*
                         * Construction heuristics does not get here; it accepts even non-doable moves.
                         * Local Search does not evaluate non-doable moves, they are filtered out during selection.
                         */
                        throw new IllegalStateException("Impossible state: move (" + move
                                + ") is not doable with the current scoreDirector (" + scoreDirector + ").");
                    }
                    Score<?> score = scoreDirector.doAndProcessMove(move, assertMoveScoreFromScratch);
                    if (assertExpectedUndoMoveScore) {
                        scoreDirector.assertExpectedUndoMoveScore(move, lastStepScore);
                    }
                    LOGGER.trace("{}            Move thread ({}) evaluation: step index ({}), move index ({}), score ({}).",
                            logIndentation, moveThreadIndex, stepIndex, moveIndex, score);
                    // This will block if there is a new synchronized operation to be taken
                    // (i.e. all iterators exhausted OR decider picked a move)
                    resultQueue.addMove(moveThreadIndex, stepIndex, moveIndex, move, score);
                } else {
                    throw new IllegalStateException("Unknown operation (" + operation + ").");
                }
                // TODO checkYielding();
            }
            LOGGER.trace("{}            Move thread ({}) finished.", logIndentation, moveThreadIndex);
        } catch (RuntimeException | Error throwable) {
            // Any Exception or even Error that happens here (on a move thread) must be stored
            // in the resultQueue in order to be propagated to the solver thread.
            LOGGER.trace("{}            Move thread ({}) exception that will be propagated to the solver thread.",
                    logIndentation, moveThreadIndex, throwable);
            resultQueue.addExceptionThrown(moveThreadIndex, generatedMoveIndex == -1 ? moveThreadIndex : generatedMoveIndex,
                    throwable);
        } finally {
            if (scoreDirector != null) {
                scoreDirector.close();
            }
        }
    }

    void predictWorkingStepScore(Move<Solution_> step, Score_ score) {
        // There is no need to recalculate the score, but we still need to set it
        scoreDirector.getSolutionDescriptor().setScore(scoreDirector.getWorkingSolution(), score);
        if (assertStepScoreFromScratch) {
            scoreDirector.assertPredictedScoreFromScratch(score, step);
        }
        if (assertExpectedStepScore) {
            scoreDirector.assertExpectedWorkingScore(score, step);
        }
        if (assertShadowVariablesAreNotStaleAfterStep) {
            scoreDirector.assertShadowVariablesAreNotStale(score, step);
        }
    }

    /**
     * This method is thread-safe.
     *
     * @return at least 0
     */
    public long getCalculationCount() {
        long calculationCount = this.calculationCount.get();
        if (calculationCount == -1L) {
            LOGGER.info("{}Score calculation speed will be too low"
                    + " because move thread ({})'s destroy wasn't processed soon enough.", logIndentation, moveThreadIndex);
            return 0L;
        }
        return calculationCount;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "-" + moveThreadIndex;
    }

}
