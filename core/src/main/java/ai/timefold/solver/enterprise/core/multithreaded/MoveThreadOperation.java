package ai.timefold.solver.enterprise.core.multithreaded;

abstract class MoveThreadOperation<Solution_> {

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

}
