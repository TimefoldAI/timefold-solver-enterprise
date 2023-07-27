package ai.timefold.solver.enterprise.core.multithreaded;

import ai.timefold.solver.core.impl.heuristic.move.Move;

sealed interface NeverEndingMoveGenerator<Solution_> permits SharedNeverEndingMoveGenerator {
    Move<Solution_> generateNextMove();
}
