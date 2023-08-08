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

public class MoveSelectorInListProxy<Solution_> implements MoveSelector<Solution_> {
    private final List<MoveSelector<Solution_>> proxySelectorList;
    private final List<Random> workingRandomList;

    public MoveSelectorInListProxy(List<MoveSelector<Solution_>> proxySelectorList, List<Random> workingRandomList) {
        this.proxySelectorList = proxySelectorList;
        this.workingRandomList = workingRandomList;
    }

    @Override
    public long getSize() {
        return proxySelectorList.stream().mapToLong(MoveSelector::getSize).sum();
    }

    @Override
    public boolean isCountable() {
        return proxySelectorList.stream().allMatch(MoveSelector::isCountable);
    }

    @Override
    public boolean isNeverEnding() {
        return proxySelectorList.stream().anyMatch(MoveSelector::isNeverEnding);
    }

    @Override
    public SelectionCacheType getCacheType() {
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

    @Override
    public void solvingStarted(SolverScope<Solution_> solverScope) {
        Random originalWorkingRandom = solverScope.getWorkingRandom();
        for (int i = 0; i < workingRandomList.size(); i++) {
            Random newWorkingRandom = workingRandomList.get(i);
            newWorkingRandom.setSeed(originalWorkingRandom.nextLong());
            solverScope.setWorkingRandom(newWorkingRandom);
            proxySelectorList.get(i).solvingStarted(solverScope);
        }
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
        return proxySelectorList.stream().allMatch(MoveSelector::supportsPhaseAndSolverCaching);
    }
}
