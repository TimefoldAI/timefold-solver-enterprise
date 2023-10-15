package ai.timefold.solver.enterprise.core.multithreaded;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.heuristic.selector.move.factory.MoveListFactoryConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveListFactory;
import ai.timefold.solver.core.impl.testdata.domain.TestdataEntity;
import ai.timefold.solver.core.impl.testdata.domain.TestdataSolution;
import ai.timefold.solver.core.impl.testdata.domain.list.TestdataListEntity;
import ai.timefold.solver.core.impl.testdata.domain.list.TestdataListSolution;
import ai.timefold.solver.core.impl.testdata.util.PlannerAssert;
import ai.timefold.solver.core.impl.testdata.util.PlannerTestUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.micrometer.core.instrument.Metrics;

class MultiThreadedSolverTest {

    @BeforeEach
    void resetGlobalRegistry() {
        Metrics.globalRegistry.clear();
    }

    @Test
    @Timeout(10)
    void stopMultiThreadedSolving_whenThereIsNoMoveAvailable() {
        SolverConfig solverConfig = PlannerTestUtils.buildSolverConfig(TestdataSolution.class, TestdataEntity.class);
        solverConfig.setMoveThreadCount("1"); // Enable the multi-threaded solving.
        LocalSearchPhaseConfig localSearchPhaseConfig = (LocalSearchPhaseConfig) solverConfig.getPhaseConfigList().get(1);

        MoveListFactoryConfig moveListFactoryConfig = new MoveListFactoryConfig();
        moveListFactoryConfig.setMoveListFactoryClass(TestMoveListFactory.class);
        localSearchPhaseConfig.setMoveSelectorConfig(moveListFactoryConfig);

        SolverFactory<TestdataSolution> solverFactory = SolverFactory.create(solverConfig);
        Solver<TestdataSolution> solver = solverFactory.buildSolver();

        TestdataSolution solution = solver.solve(TestdataSolution.generateSolution());
        PlannerAssert.assertSolutionInitialized(solution);
    }

    @Test
    void multiThreadedSolvingListVariable() {
        SolverConfig solverConfig = PlannerTestUtils.buildSolverConfig(TestdataListSolution.class, TestdataListEntity.class);
        solverConfig.setMoveThreadCount("1"); // Enable the multi-threaded solving.

        SolverFactory<TestdataListSolution> solverFactory = SolverFactory.create(solverConfig);
        Solver<TestdataListSolution> solver = solverFactory.buildSolver();

        TestdataListSolution solution = solver.solve(TestdataListSolution.generateUninitializedSolution(10, 10));
        assertThat(solution.getScore().initScore()).isEqualTo(0);
    }

    public static class TestMoveListFactory implements MoveListFactory<TestdataSolution> {

        public TestMoveListFactory() {
        }

        @Override
        public List<? extends Move<TestdataSolution>> createMoveList(TestdataSolution solution) {
            return List.of();
        }
    }
}
