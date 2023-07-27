package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.solver.thread.ChildThreadType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MoveThreadRunner<Solution_, Score_ extends Score<Score_>> implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MoveThreadRunner.class);

    private final String logIndentation;
    private final int moveThreadIndex;
    private final boolean evaluateDoable;

    private final BlockingQueue<MoveThreadOperation<Solution_>> operationQueue;
    private final OrderByMoveIndexBlockingQueue<Solution_> resultQueue;
    private final CyclicBarrier moveThreadBarrier;
    private final AtomicInteger moveIndex;
    private final AtomicReferenceArray<NeverEndingMoveGenerator<Solution_>> iteratorReference;

    private final boolean assertMoveScoreFromScratch;
    private final boolean assertExpectedUndoMoveScore;
    private final boolean assertStepScoreFromScratch;
    private final boolean assertExpectedStepScore;
    private final boolean assertShadowVariablesAreNotStaleAfterStep;

    private InnerScoreDirector<Solution_, Score_> scoreDirector = null;
    private final AtomicLong calculationCount = new AtomicLong(-1);

    public MoveThreadRunner(String logIndentation, int moveThreadIndex, boolean evaluateDoable,
            BlockingQueue<MoveThreadOperation<Solution_>> operationQueue,
            OrderByMoveIndexBlockingQueue<Solution_> resultQueue,
            CyclicBarrier moveThreadBarrier,
            AtomicInteger moveIndex,
            AtomicReferenceArray<NeverEndingMoveGenerator<Solution_>> iteratorReference,
            boolean assertMoveScoreFromScratch, boolean assertExpectedUndoMoveScore,
            boolean assertStepScoreFromScratch, boolean assertExpectedStepScore,
            boolean assertShadowVariablesAreNotStaleAfterStep) {
        this.logIndentation = logIndentation;
        this.moveThreadIndex = moveThreadIndex;
        this.evaluateDoable = evaluateDoable;
        this.operationQueue = operationQueue;
        this.resultQueue = resultQueue;
        this.moveThreadBarrier = moveThreadBarrier;
        this.moveIndex = moveIndex;
        this.iteratorReference = iteratorReference;
        this.assertMoveScoreFromScratch = assertMoveScoreFromScratch;
        this.assertExpectedUndoMoveScore = assertExpectedUndoMoveScore;
        this.assertStepScoreFromScratch = assertStepScoreFromScratch;
        this.assertExpectedStepScore = assertExpectedStepScore;
        this.assertShadowVariablesAreNotStaleAfterStep = assertShadowVariablesAreNotStaleAfterStep;
    }

    @Override
    public void run() {
        int generatedMoveIndex = -1;
        try {
            int stepIndex = -1;
            Score_ lastStepScore = null;
            resultQueue.waitForDecider();
            // Wait for the iteratorLock to be available before entering the loop
            while (true) {
                MoveThreadOperation<Solution_> operation;
                try {
                    if (!operationQueue.isEmpty()) {
                        operation = operationQueue.take();
                    } else {
                        generatedMoveIndex = moveIndex.getAndIncrement();
                        NeverEndingMoveGenerator<Solution_> neverEndingMoveGenerator =
                                iteratorReference.get(generatedMoveIndex % iteratorReference.length());
                        synchronized (neverEndingMoveGenerator) {
                            generatedMoveIndex = neverEndingMoveGenerator.getNextMoveIndex();
                            operation = new MoveEvaluationOperation<>(stepIndex, generatedMoveIndex,
                                    neverEndingMoveGenerator.generateNextMove());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (operation instanceof SetupOperation) {
                    // Cannot be replaced by pattern variable; "cannot be safely cast" because of parameterization
                    SetupOperation<Solution_, Score_> setupOperation = (SetupOperation<Solution_, Score_>) operation;
                    scoreDirector = setupOperation.getScoreDirector()
                            .createChildThreadScoreDirector(ChildThreadType.MOVE_THREAD);
                    stepIndex = 0;
                    lastStepScore = scoreDirector.calculateScore();
                    LOGGER.trace("{}            Move thread ({}) setup: step index ({}), score ({}).",
                            logIndentation, moveThreadIndex, stepIndex, lastStepScore);
                    try {
                        // Don't consume another operation until every moveThread took this SetupOperation
                        moveThreadBarrier.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (operation instanceof DestroyOperation) {
                    LOGGER.trace("{}            Move thread ({}) destroy: step index ({}).",
                            logIndentation, moveThreadIndex, stepIndex);
                    calculationCount.set(scoreDirector.getCalculationCount());
                    break;
                } else if (operation instanceof ApplyStepOperation) {
                    // TODO Performance gain with specialized 2-phase cyclic barrier:
                    // As soon as the last move thread has taken its ApplyStepOperation,
                    // other move threads can already depart from the moveThreadStepBarrier: no need to wait until the step is done.
                    // Cannot be replaced by pattern variable; "cannot be safely cast" because of parameterization
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
                    try {
                        // Don't consume an MoveEvaluationOperation until every moveThread took this ApplyStepOperation
                        moveThreadBarrier.await();
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else if (operation instanceof MoveEvaluationOperation<Solution_> moveEvaluationOperation) {
                    int moveIndex = moveEvaluationOperation.getMoveIndex();
                    if (stepIndex != moveEvaluationOperation.getStepIndex()) {
                        throw new IllegalStateException("Impossible situation: the moveThread's stepIndex ("
                                + stepIndex + ") differs from the operation's stepIndex ("
                                + moveEvaluationOperation.getStepIndex() + ") with moveIndex ("
                                + moveIndex + ").");
                    }
                    Move<Solution_> move = moveEvaluationOperation.getMove().rebase(scoreDirector);
                    if (evaluateDoable && !move.isMoveDoable(scoreDirector)) {
                        LOGGER.trace("{}            Move thread ({}) evaluation: step index ({}), move index ({}), not doable.",
                                logIndentation, moveThreadIndex, stepIndex, moveIndex);
                        resultQueue.addUndoableMove(moveThreadIndex, stepIndex, moveIndex, move);
                    } else {
                        Score<?> score = scoreDirector.doAndProcessMove(move, assertMoveScoreFromScratch);
                        if (assertExpectedUndoMoveScore) {
                            scoreDirector.assertExpectedUndoMoveScore(move, lastStepScore);
                        }
                        LOGGER.trace("{}            Move thread ({}) evaluation: step index ({}), move index ({}), score ({}).",
                                logIndentation, moveThreadIndex, stepIndex, moveIndex, score);
                        // Deliberately add to fail fast if there is not enough capacity (which is impossible)
                        resultQueue.addMove(moveThreadIndex, stepIndex, moveIndex, move, score);
                    }
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
            resultQueue.addExceptionThrown(generatedMoveIndex == -1 ? moveThreadIndex : generatedMoveIndex, throwable);
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
