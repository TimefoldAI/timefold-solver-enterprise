package ai.timefold.solver.enterprise.core.multithreaded;

import java.util.Iterator;
import java.util.List;
import java.util.Random;

import ai.timefold.solver.core.config.heuristic.selector.common.SelectionCacheType;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.heuristic.selector.move.MoveSelector;
import ai.timefold.solver.core.impl.phase.scope.AbstractPhaseScope;
import ai.timefold.solver.core.impl.phase.scope.AbstractStepScope;
import ai.timefold.solver.core.impl.solver.scope.SolverScope;

/**
 * A proxy to {@link MoveSelector}s in a given list, so they can receive
 * solver events (such as {@link MoveSelector#stepStarted(AbstractStepScope)} and
 * {@link MoveSelector#phaseStarted(AbstractPhaseScope)}) without requiring modification
 * to the Solver itself to handle multiple independent {@link MoveSelector}s.
 *
 * Each {@link MoveSelector} has it own
 * {@link ai.timefold.solver.core.impl.heuristic.selector.AbstractSelector#workingRandom}
 * by modifying the {@link SolverScope} used in {@link MoveSelector#solvingStarted(SolverScope)}
 * to use a Random from the list it was given in its constructor.
 *
 * Its {@link MoveSelector#iterator()} method remains unimplemented and will throw
 * {@link UnsupportedOperationException} if called. To iterate through the moves of
 * this selector, call the {@link MoveSelector#iterator()} on the {@link MoveSelector}s
 * passed to this {@link MoveSelectorInListProxy} constructor.
 */
public class MoveSelectorInListProxy<Solution_> implements MoveSelector<Solution_> {
    /**
     * The {@link MoveSelector}s to receive
     * solver events (such as {@link MoveSelector#stepStarted(AbstractStepScope)} and
     * {@link MoveSelector#phaseStarted(AbstractPhaseScope)}).
     */
    private final List<MoveSelector<Solution_>> proxySelectorList;

    /**
     * The {@link ai.timefold.solver.core.impl.heuristic.selector.AbstractSelector#workingRandom}
     * that each {@link MoveSelector} should have.
     */
    private final List<Random> workingRandomList;

    public MoveSelectorInListProxy(List<MoveSelector<Solution_>> proxySelectorList, List<Random> workingRandomList) {
        this.proxySelectorList = proxySelectorList;
        this.workingRandomList = workingRandomList;
        if (proxySelectorList.size() != workingRandomList.size()) {
            throw new IllegalArgumentException("The proxy selector list (" + proxySelectorList +
                    ") does not have the same size as the working random list (" + workingRandomList + ").");
        }
    }

    @Override
    public long getSize() {
        // Its size is the sum of all move selector sizes
        return proxySelectorList.stream().mapToLong(MoveSelector::getSize).sum();
    }

    @Override
    public boolean isCountable() {
        // It countable if and only if all move selectors are countable
        return proxySelectorList.stream().allMatch(MoveSelector::isCountable);
    }

    @Override
    public boolean isNeverEnding() {
        // It is never ending if and only if any move selectors are never ending
        return proxySelectorList.stream().anyMatch(MoveSelector::isNeverEnding);
    }

    @Override
    public SelectionCacheType getCacheType() {
        // Assumption: all move selectors have the same cache types
        // This should be true, since they are all copies/partitions
        // of the same MoveSelector
        return proxySelectorList.stream().findAny()
                .map(MoveSelector::getCacheType)
                .orElseThrow();
    }

    @Override
    public void phaseStarted(AbstractPhaseScope<Solution_> abstractPhaseScope) {
        proxySelectorList.forEach(moveSelector -> moveSelector.phaseStarted(abstractPhaseScope));
    }

    @Override
    public void stepStarted(AbstractStepScope<Solution_> abstractStepScope) {
        proxySelectorList.forEach(moveSelector -> moveSelector.stepStarted(abstractStepScope));
    }

    @Override
    public void stepEnded(AbstractStepScope<Solution_> abstractStepScope) {
        proxySelectorList.forEach(moveSelector -> moveSelector.stepEnded(abstractStepScope));
    }

    @Override
    public void phaseEnded(AbstractPhaseScope<Solution_> abstractPhaseScope) {
        proxySelectorList.forEach(moveSelector -> moveSelector.phaseEnded(abstractPhaseScope));
    }

    /**
     * Modifies the {@link SolverScope} so each {@link MoveSelector} in
     * {@link #proxySelectorList} get a different
     * {@link ai.timefold.solver.core.impl.heuristic.selector.AbstractSelector#workingRandom}.
     */
    @Override
    public void solvingStarted(SolverScope<Solution_> solverScope) {
        // Save the original working random, so we can restore it later
        Random originalWorkingRandom = solverScope.getWorkingRandom();
        for (int i = 0; i < workingRandomList.size(); i++) {
            // Get the working random corresponding to this move selector
            // from the list
            Random newWorkingRandom = workingRandomList.get(i);

            // Seed the new working random with a random long from the original
            // working random
            newWorkingRandom.setSeed(originalWorkingRandom.nextLong());

            // Set the working random of the solver scope to the new working random
            solverScope.setWorkingRandom(newWorkingRandom);

            // Call the move selector with the modified solver scope
            proxySelectorList.get(i).solvingStarted(solverScope);
        }
        // Set the working random in the solver scope to the original working random.
        solverScope.setWorkingRandom(originalWorkingRandom);
    }

    @Override
    public void solvingEnded(SolverScope<Solution_> solverScope) {
        proxySelectorList.forEach(moveSelector -> moveSelector.solvingEnded(solverScope));
    }

    @Override
    public Iterator<Move<Solution_>> iterator() {
        throw new IllegalStateException("Cannot call iterator on MoveSelectorInListProxy");
    }

    @Override
    public boolean supportsPhaseAndSolverCaching() {
        // It supports phase and solver caching if and only if all move selectors support
        // phase and solver caching
        return proxySelectorList.stream().allMatch(MoveSelector::supportsPhaseAndSolverCaching);
    }
}
