package ai.timefold.solver.enterprise.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.config.partitionedsearch.PartitionedSearchPhaseConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.enterprise.TimefoldSolverEnterpriseService;
import ai.timefold.solver.core.impl.heuristic.HeuristicConfigPolicy;
import ai.timefold.solver.core.impl.partitionedsearch.DefaultPartitionedSearchPhaseFactory;
import ai.timefold.solver.core.impl.solver.recaller.BestSolutionRecaller;
import ai.timefold.solver.core.impl.solver.termination.Termination;
import ai.timefold.solver.core.impl.testdata.domain.TestdataSolution;
import ai.timefold.solver.enterprise.core.partitioned.DefaultPartitionedSearchPhase;
import ai.timefold.solver.enterprise.core.partitioned.testdata.TestdataSolutionPartitioner;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class DefaultTimefoldSolverEnterpriseServiceTest {

    @Test
    void loads() {
        Assertions.assertThat(TimefoldSolverEnterpriseService.load())
                .isNotNull();
    }

    @Test
    void solverVersion() {
        Assertions.assertThat(TimefoldSolverEnterpriseService.identifySolverVersion())
                .contains("Enterprise Edition");
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "4, 2"
    })
    void partitionedSearchResolvedActiveThreadCountAuto(int availableCpuCount, int expectedResolvedCpuCount) {
        assertThat(DefaultTimefoldSolverEnterpriseService.resolveActiveThreadCount(
                PartitionedSearchPhaseConfig.ACTIVE_THREAD_COUNT_AUTO,
                availableCpuCount))
                .isEqualTo(expectedResolvedCpuCount);
    }

    @Test
    void partitionedSearchResolveActiveThreadCountUnlimited() {
        assertThat(DefaultTimefoldSolverEnterpriseService
                .resolveActiveThreadCount(PartitionedSearchPhaseConfig.ACTIVE_THREAD_COUNT_UNLIMITED))
                .isNull();
    }

    @Test
    void partitionedSearchAssertionsForNonIntrusiveFullAssertMode() {
        DefaultPartitionedSearchPhase<TestdataSolution> partitionedSearchPhase =
                mockEnvironmentMode(EnvironmentMode.NON_INTRUSIVE_FULL_ASSERT);
        assertThat(partitionedSearchPhase.isAssertStepScoreFromScratch()).isTrue();
        assertThat(partitionedSearchPhase.isAssertExpectedStepScore()).isFalse();
        assertThat(partitionedSearchPhase.isAssertShadowVariablesAreNotStaleAfterStep()).isFalse();
    }

    @Test
    void partitionedSearchAssertionsForIntrusiveFastAssertMode() {
        DefaultPartitionedSearchPhase<TestdataSolution> partitionedSearchPhase =
                mockEnvironmentMode(EnvironmentMode.FAST_ASSERT);
        assertThat(partitionedSearchPhase.isAssertStepScoreFromScratch()).isFalse();
        assertThat(partitionedSearchPhase.isAssertExpectedStepScore()).isTrue();
        assertThat(partitionedSearchPhase.isAssertShadowVariablesAreNotStaleAfterStep()).isTrue();
    }

    private DefaultPartitionedSearchPhase<TestdataSolution> mockEnvironmentMode(EnvironmentMode environmentMode) {
        HeuristicConfigPolicy<TestdataSolution> heuristicConfigPolicy = mock(HeuristicConfigPolicy.class);
        when(heuristicConfigPolicy.getEnvironmentMode()).thenReturn(environmentMode);
        // Reuse the same mock as it doesn't matter.
        when(heuristicConfigPolicy.createPhaseConfigPolicy()).thenReturn(heuristicConfigPolicy);

        PartitionedSearchPhaseConfig phaseConfig = new PartitionedSearchPhaseConfig();
        phaseConfig.setSolutionPartitionerClass(TestdataSolutionPartitioner.class);
        DefaultPartitionedSearchPhaseFactory<TestdataSolution> partitionedSearchPhaseFactory =
                new DefaultPartitionedSearchPhaseFactory<>(phaseConfig);
        return (DefaultPartitionedSearchPhase<TestdataSolution>) partitionedSearchPhaseFactory.buildPhase(0,
                heuristicConfigPolicy, mock(BestSolutionRecaller.class), mock(Termination.class));
    }

}
