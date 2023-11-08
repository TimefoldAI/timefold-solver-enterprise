package ai.timefold.solver.enterprise.core;

import static ai.timefold.solver.core.config.partitionedsearch.PartitionedSearchPhaseConfig.ACTIVE_THREAD_COUNT_AUTO;
import static ai.timefold.solver.core.config.partitionedsearch.PartitionedSearchPhaseConfig.ACTIVE_THREAD_COUNT_UNLIMITED;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiFunction;

import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.heuristic.selector.common.SelectionCacheType;
import ai.timefold.solver.core.config.heuristic.selector.common.SelectionOrder;
import ai.timefold.solver.core.config.heuristic.selector.common.nearby.NearbySelectionConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.list.DestinationSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.list.SubListSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.value.ValueSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.partitionedsearch.PartitionedSearchPhaseConfig;
import ai.timefold.solver.core.config.phase.PhaseConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.util.ConfigUtils;
import ai.timefold.solver.core.enterprise.TimefoldSolverEnterpriseService;
import ai.timefold.solver.core.impl.constructionheuristic.decider.ConstructionHeuristicDecider;
import ai.timefold.solver.core.impl.constructionheuristic.decider.forager.ConstructionHeuristicForager;
import ai.timefold.solver.core.impl.domain.entity.descriptor.EntityDescriptor;
import ai.timefold.solver.core.impl.heuristic.HeuristicConfigPolicy;
import ai.timefold.solver.core.impl.heuristic.selector.common.nearby.NearbyDistanceMeter;
import ai.timefold.solver.core.impl.heuristic.selector.entity.EntitySelector;
import ai.timefold.solver.core.impl.heuristic.selector.entity.EntitySelectorFactory;
import ai.timefold.solver.core.impl.heuristic.selector.list.DestinationSelector;
import ai.timefold.solver.core.impl.heuristic.selector.list.ElementDestinationSelector;
import ai.timefold.solver.core.impl.heuristic.selector.list.RandomSubListSelector;
import ai.timefold.solver.core.impl.heuristic.selector.list.SubListSelector;
import ai.timefold.solver.core.impl.heuristic.selector.list.SubListSelectorFactory;
import ai.timefold.solver.core.impl.heuristic.selector.move.MoveSelector;
import ai.timefold.solver.core.impl.heuristic.selector.value.EntityIndependentValueSelector;
import ai.timefold.solver.core.impl.heuristic.selector.value.ValueSelector;
import ai.timefold.solver.core.impl.heuristic.selector.value.ValueSelectorFactory;
import ai.timefold.solver.core.impl.localsearch.decider.LocalSearchDecider;
import ai.timefold.solver.core.impl.localsearch.decider.acceptor.Acceptor;
import ai.timefold.solver.core.impl.localsearch.decider.forager.LocalSearchForager;
import ai.timefold.solver.core.impl.partitionedsearch.PartitionedSearchPhase;
import ai.timefold.solver.core.impl.partitionedsearch.partitioner.SolutionPartitioner;
import ai.timefold.solver.core.impl.solver.termination.Termination;
import ai.timefold.solver.core.impl.solver.thread.ChildThreadType;
import ai.timefold.solver.core.impl.util.Pair;
import ai.timefold.solver.enterprise.core.multithreaded.MultiThreadedConstructionHeuristicDecider;
import ai.timefold.solver.enterprise.core.multithreaded.MultiThreadedLocalSearchDecider;
import ai.timefold.solver.enterprise.core.nearby.common.NearbyRandom;
import ai.timefold.solver.enterprise.core.nearby.common.NearbyRandomFactory;
import ai.timefold.solver.enterprise.core.nearby.entity.NearEntityNearbyEntitySelector;
import ai.timefold.solver.enterprise.core.nearby.list.NearSubListNearbyDestinationSelector;
import ai.timefold.solver.enterprise.core.nearby.list.NearSubListNearbySubListSelector;
import ai.timefold.solver.enterprise.core.nearby.list.NearValueNearbyDestinationSelector;
import ai.timefold.solver.enterprise.core.nearby.value.NearEntityNearbyValueSelector;
import ai.timefold.solver.enterprise.core.nearby.value.NearValueNearbyValueSelector;
import ai.timefold.solver.enterprise.core.partitioned.DefaultPartitionedSearchPhase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultTimefoldSolverEnterpriseService implements TimefoldSolverEnterpriseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTimefoldSolverEnterpriseService.class);

    @Override
    public <Solution_> ConstructionHeuristicDecider<Solution_> buildConstructionHeuristic(int moveThreadCount,
            Termination<Solution_> termination, ConstructionHeuristicForager<Solution_> forager,
            EnvironmentMode environmentMode, HeuristicConfigPolicy<Solution_> configPolicy) {
        Integer moveThreadBufferSize = configPolicy.getMoveThreadBufferSize();
        if (moveThreadBufferSize == null) {
            // TODO Verify this is a good default by more meticulous benchmarking on multiple machines and JDK's
            // If it's too low, move threads will need to wait on the buffer, which hurts performance
            // If it's too high, more moves are selected that aren't foraged
            moveThreadBufferSize = 10;
        }
        ThreadFactory threadFactory = configPolicy.buildThreadFactory(ChildThreadType.MOVE_THREAD);
        int selectedMoveBufferSize = moveThreadCount * moveThreadBufferSize;
        MultiThreadedConstructionHeuristicDecider<Solution_> multiThreadedDecider =
                new MultiThreadedConstructionHeuristicDecider<>(configPolicy.getLogIndentation(), termination, forager,
                        threadFactory, moveThreadCount, selectedMoveBufferSize);
        if (environmentMode.isNonIntrusiveFullAsserted()) {
            multiThreadedDecider.setAssertStepScoreFromScratch(true);
        }
        if (environmentMode.isIntrusiveFastAsserted()) {
            multiThreadedDecider.setAssertExpectedStepScore(true);
            multiThreadedDecider.setAssertShadowVariablesAreNotStaleAfterStep(true);
        }
        return multiThreadedDecider;
    }

    @Override
    public <Solution_> LocalSearchDecider<Solution_> buildLocalSearch(int moveThreadCount, Termination<Solution_> termination,
            MoveSelector<Solution_> moveSelector, Acceptor<Solution_> acceptor, LocalSearchForager<Solution_> forager,
            EnvironmentMode environmentMode, HeuristicConfigPolicy<Solution_> configPolicy) {
        Integer moveThreadBufferSize = configPolicy.getMoveThreadBufferSize();
        if (moveThreadBufferSize == null) {
            // TODO Verify this is a good default by more meticulous benchmarking on multiple machines and JDK's
            // If it's too low, move threads will need to wait on the buffer, which hurts performance
            // If it's too high, more moves are selected that aren't foraged
            moveThreadBufferSize = 10;
        }
        ThreadFactory threadFactory = configPolicy.buildThreadFactory(ChildThreadType.MOVE_THREAD);
        int selectedMoveBufferSize = moveThreadCount * moveThreadBufferSize;
        MultiThreadedLocalSearchDecider<Solution_> multiThreadedDecider = new MultiThreadedLocalSearchDecider<>(
                configPolicy.getLogIndentation(), termination, moveSelector, acceptor, forager,
                threadFactory, moveThreadCount, selectedMoveBufferSize);
        if (environmentMode.isNonIntrusiveFullAsserted()) {
            multiThreadedDecider.setAssertStepScoreFromScratch(true);
        }
        if (environmentMode.isIntrusiveFastAsserted()) {
            multiThreadedDecider.setAssertExpectedStepScore(true);
            multiThreadedDecider.setAssertShadowVariablesAreNotStaleAfterStep(true);
        }
        return multiThreadedDecider;
    }

    @Override
    public <Solution_> PartitionedSearchPhase<Solution_> buildPartitionedSearch(int phaseIndex,
            PartitionedSearchPhaseConfig phaseConfig, HeuristicConfigPolicy<Solution_> solverConfigPolicy,
            Termination<Solution_> solverTermination,
            BiFunction<HeuristicConfigPolicy<Solution_>, Termination<Solution_>, Termination<Solution_>> phaseTerminationFunction) {
        HeuristicConfigPolicy<Solution_> phaseConfigPolicy = solverConfigPolicy.createPhaseConfigPolicy();
        ThreadFactory threadFactory = solverConfigPolicy.buildThreadFactory(ChildThreadType.PART_THREAD);
        Termination<Solution_> phaseTermination = phaseTerminationFunction.apply(phaseConfigPolicy, solverTermination);
        Integer resolvedActiveThreadCount = resolveActiveThreadCount(phaseConfig.getRunnablePartThreadLimit());
        List<PhaseConfig> phaseConfigList_ = phaseConfig.getPhaseConfigList();
        if (ConfigUtils.isEmptyCollection(phaseConfigList_)) {
            phaseConfigList_ = Arrays.asList(new ConstructionHeuristicPhaseConfig(), new LocalSearchPhaseConfig());
        }

        DefaultPartitionedSearchPhase.Builder<Solution_> builder =
                new DefaultPartitionedSearchPhase.Builder<>(phaseIndex, solverConfigPolicy.getLogIndentation(),
                        phaseTermination, buildSolutionPartitioner(phaseConfig), threadFactory, resolvedActiveThreadCount,
                        phaseConfigList_, phaseConfigPolicy.createChildThreadConfigPolicy(ChildThreadType.PART_THREAD));

        EnvironmentMode environmentMode = phaseConfigPolicy.getEnvironmentMode();
        if (environmentMode.isNonIntrusiveFullAsserted()) {
            builder.setAssertStepScoreFromScratch(true);
        }
        if (environmentMode.isIntrusiveFastAsserted()) {
            builder.setAssertExpectedStepScore(true);
            builder.setAssertShadowVariablesAreNotStaleAfterStep(true);
        }
        return builder.build();
    }

    private static <Solution_> SolutionPartitioner<Solution_>
            buildSolutionPartitioner(PartitionedSearchPhaseConfig phaseConfig) {
        if (phaseConfig.getSolutionPartitionerClass() != null) {
            SolutionPartitioner<?> solutionPartitioner =
                    ConfigUtils.newInstance(phaseConfig, "solutionPartitionerClass", phaseConfig.getSolutionPartitionerClass());
            ConfigUtils.applyCustomProperties(solutionPartitioner, "solutionPartitionerClass",
                    phaseConfig.getSolutionPartitionerCustomProperties(), "solutionPartitionerCustomProperties");
            return (SolutionPartitioner<Solution_>) solutionPartitioner;
        } else {
            if (phaseConfig.getSolutionPartitionerCustomProperties() != null) {
                throw new IllegalStateException(
                        "If there is no solutionPartitionerClass (" + phaseConfig.getSolutionPartitionerClass()
                                + "), then there can be no solutionPartitionerCustomProperties ("
                                + phaseConfig.getSolutionPartitionerCustomProperties() + ") either.");
            }
            // TODO Implement generic partitioner
            throw new UnsupportedOperationException();
        }
    }

    static Integer resolveActiveThreadCount(String runnablePartThreadLimit) {
        return resolveActiveThreadCount(runnablePartThreadLimit, Runtime.getRuntime().availableProcessors());
    }

    static Integer resolveActiveThreadCount(String runnablePartThreadLimit, int availableProcessorCount) {
        Integer resolvedActiveThreadCount;
        final boolean threadLimitNullOrAuto =
                runnablePartThreadLimit == null || runnablePartThreadLimit.equals(ACTIVE_THREAD_COUNT_AUTO);
        if (threadLimitNullOrAuto) {
            // Leave one for the Operating System and 1 for the solver thread, take the rest
            resolvedActiveThreadCount = Math.max(1, availableProcessorCount - 2);
        } else if (runnablePartThreadLimit.equals(ACTIVE_THREAD_COUNT_UNLIMITED)) {
            resolvedActiveThreadCount = null;
        } else {
            resolvedActiveThreadCount = ConfigUtils.resolvePoolSize("runnablePartThreadLimit",
                    runnablePartThreadLimit, ACTIVE_THREAD_COUNT_AUTO, ACTIVE_THREAD_COUNT_UNLIMITED);
            if (resolvedActiveThreadCount < 1) {
                throw new IllegalArgumentException("The runnablePartThreadLimit (" + runnablePartThreadLimit
                        + ") resulted in a resolvedActiveThreadCount (" + resolvedActiveThreadCount
                        + ") that is lower than 1.");
            }
            if (resolvedActiveThreadCount > availableProcessorCount) {
                LOGGER.debug("The resolvedActiveThreadCount ({}) is higher than "
                        + "the availableProcessorCount ({}), so the JVM will "
                        + "round-robin the CPU instead.", resolvedActiveThreadCount, availableProcessorCount);
            }
        }
        return resolvedActiveThreadCount;
    }

    @Override
    public <Solution_> EntitySelector<Solution_> applyNearbySelection(EntitySelectorConfig config,
            HeuristicConfigPolicy<Solution_> configPolicy, NearbySelectionConfig nearbySelectionConfig,
            SelectionCacheType minimumCacheType, SelectionOrder resolvedSelectionOrder,
            EntitySelector<Solution_> entitySelector) {
        boolean randomSelection = resolvedSelectionOrder.toRandomSelectionBoolean();
        if (nearbySelectionConfig.getOriginEntitySelectorConfig() == null) {
            throw new IllegalArgumentException("The entitySelector (" + config
                    + ")'s nearbySelectionConfig (" + nearbySelectionConfig + ") requires an originEntitySelector.");
        }
        EntitySelectorFactory<Solution_> entitySelectorFactory =
                EntitySelectorFactory.create(nearbySelectionConfig.getOriginEntitySelectorConfig());
        EntitySelector<Solution_> originEntitySelector =
                entitySelectorFactory.buildEntitySelector(configPolicy, minimumCacheType, resolvedSelectionOrder);
        NearbyDistanceMeter nearbyDistanceMeter =
                configPolicy.getClassInstanceCache().newInstance(nearbySelectionConfig, "nearbyDistanceMeterClass",
                        nearbySelectionConfig.getNearbyDistanceMeterClass());
        // TODO Check nearbyDistanceMeterClass.getGenericInterfaces() to confirm generic type S is an entityClass
        NearbyRandom nearbyRandom = NearbyRandomFactory.create(nearbySelectionConfig).buildNearbyRandom(randomSelection);
        return new NearEntityNearbyEntitySelector<>(entitySelector, originEntitySelector, nearbyDistanceMeter,
                nearbyRandom, randomSelection);
    }

    @Override
    public <Solution_> ValueSelector<Solution_> applyNearbySelection(ValueSelectorConfig config,
            HeuristicConfigPolicy<Solution_> configPolicy, EntityDescriptor<Solution_> entityDescriptor,
            SelectionCacheType minimumCacheType, SelectionOrder resolvedSelectionOrder,
            ValueSelector<Solution_> valueSelector) {
        NearbySelectionConfig nearbySelectionConfig = config.getNearbySelectionConfig();
        boolean randomSelection = resolvedSelectionOrder.toRandomSelectionBoolean();
        NearbyDistanceMeter<?, ?> nearbyDistanceMeter = configPolicy.getClassInstanceCache().newInstance(nearbySelectionConfig,
                "nearbyDistanceMeterClass", nearbySelectionConfig.getNearbyDistanceMeterClass());
        // TODO Check nearbyDistanceMeterClass.getGenericInterfaces() to confirm generic type S is an entityClass
        NearbyRandom nearbyRandom = NearbyRandomFactory.create(nearbySelectionConfig).buildNearbyRandom(randomSelection);
        if (nearbySelectionConfig.getOriginEntitySelectorConfig() != null) {
            EntitySelector<Solution_> originEntitySelector = EntitySelectorFactory
                    .<Solution_> create(nearbySelectionConfig.getOriginEntitySelectorConfig())
                    .buildEntitySelector(configPolicy, minimumCacheType, resolvedSelectionOrder);
            return new NearEntityNearbyValueSelector<>(valueSelector, originEntitySelector, nearbyDistanceMeter,
                    nearbyRandom, randomSelection);
        } else if (nearbySelectionConfig.getOriginValueSelectorConfig() != null) {
            ValueSelector<Solution_> originValueSelector = ValueSelectorFactory
                    .<Solution_> create(nearbySelectionConfig.getOriginValueSelectorConfig())
                    .buildValueSelector(configPolicy, entityDescriptor, minimumCacheType, resolvedSelectionOrder);
            if (!(valueSelector instanceof EntityIndependentValueSelector)) {
                throw new IllegalArgumentException(
                        "The valueSelectorConfig (" + config
                                + ") needs to be based on an "
                                + EntityIndependentValueSelector.class.getSimpleName() + " (" + valueSelector + ")."
                                + " Check your @" + ValueRangeProvider.class.getSimpleName() + " annotations.");
            }
            if (!(originValueSelector instanceof EntityIndependentValueSelector)) {
                throw new IllegalArgumentException(
                        "The originValueSelectorConfig (" + nearbySelectionConfig.getOriginValueSelectorConfig()
                                + ") needs to be based on an "
                                + EntityIndependentValueSelector.class.getSimpleName() + " (" + originValueSelector + ")."
                                + " Check your @" + ValueRangeProvider.class.getSimpleName() + " annotations.");
            }
            return new NearValueNearbyValueSelector<>(
                    (EntityIndependentValueSelector<Solution_>) valueSelector,
                    (EntityIndependentValueSelector<Solution_>) originValueSelector,
                    nearbyDistanceMeter, nearbyRandom, randomSelection);
        } else {
            throw new IllegalArgumentException("The valueSelector (" + config
                    + ")'s nearbySelectionConfig (" + nearbySelectionConfig
                    + ") requires an originEntitySelector or an originValueSelector.");
        }
    }

    @Override
    public <Solution_> SubListSelector<Solution_> applyNearbySelection(SubListSelectorConfig config,
            HeuristicConfigPolicy<Solution_> configPolicy, SelectionCacheType minimumCacheType,
            SelectionOrder resolvedSelectionOrder, RandomSubListSelector<Solution_> subListSelector) {
        NearbySelectionConfig nearbySelectionConfig = config.getNearbySelectionConfig();
        randomDistributionNearbyLimitation(nearbySelectionConfig).ifPresent(configPropertyNameAndValue -> {
            if (config.getMinimumSubListSize() != null && config.getMinimumSubListSize() > 1) {
                throw new IllegalArgumentException("Using minimumSubListSize (" + config.getMinimumSubListSize()
                        + ") is not allowed because the nearby selection distribution uses a "
                        + configPropertyNameAndValue.getKey() + " (" + configPropertyNameAndValue.getValue()
                        + ") which may limit the ability to select all nearby values."
                        + " As a consequence, it may be impossible to select a subList with the required minimumSubListSize."
                        + " Therefore, this combination is prohibited.");
            }
        });

        nearbySelectionConfig.validateNearby(minimumCacheType, resolvedSelectionOrder);

        boolean randomSelection = resolvedSelectionOrder.toRandomSelectionBoolean();

        NearbyDistanceMeter<?, ?> nearbyDistanceMeter =
                configPolicy.getClassInstanceCache().newInstance(nearbySelectionConfig,
                        "nearbyDistanceMeterClass", nearbySelectionConfig.getNearbyDistanceMeterClass());
        // TODO Check nearbyDistanceMeterClass.getGenericInterfaces() to confirm generic type S is an entityClass
        NearbyRandom nearbyRandom = NearbyRandomFactory.create(nearbySelectionConfig).buildNearbyRandom(randomSelection);

        if (nearbySelectionConfig.getOriginSubListSelectorConfig() == null) {
            throw new IllegalArgumentException("The subListSelector (" + config
                    + ")'s nearbySelectionConfig (" + nearbySelectionConfig
                    + ") requires an originSubListSelector.");
        }
        SubListSelector<Solution_> replayingOriginSubListSelector = SubListSelectorFactory
                .<Solution_> create(nearbySelectionConfig.getOriginSubListSelectorConfig())
                // Entity selector not needed for replaying selector.
                .buildSubListSelector(configPolicy, null, minimumCacheType, resolvedSelectionOrder);
        return new NearSubListNearbySubListSelector<>(
                subListSelector,
                replayingOriginSubListSelector,
                nearbyDistanceMeter,
                nearbyRandom);
    }

    private static Optional<Pair<String, Object>>
            randomDistributionNearbyLimitation(NearbySelectionConfig nearbySelectionConfig) {
        if (nearbySelectionConfig.getBlockDistributionSizeRatio() != null
                && nearbySelectionConfig.getBlockDistributionSizeRatio() < 1) {
            return Optional.of(Pair.of("blockDistributionSizeRatio", nearbySelectionConfig.getBlockDistributionSizeRatio()));
        }
        if (nearbySelectionConfig.getBlockDistributionSizeMaximum() != null) {
            return Optional
                    .of(Pair.of("blockDistributionSizeMaximum", nearbySelectionConfig.getBlockDistributionSizeMaximum()));
        }
        if (nearbySelectionConfig.getLinearDistributionSizeMaximum() != null) {
            return Optional
                    .of(Pair.of("linearDistributionSizeMaximum", nearbySelectionConfig.getLinearDistributionSizeMaximum()));
        }
        if (nearbySelectionConfig.getParabolicDistributionSizeMaximum() != null) {
            return Optional.of(
                    Pair.of("parabolicDistributionSizeMaximum", nearbySelectionConfig.getParabolicDistributionSizeMaximum()));
        }
        return Optional.empty();
    }

    @Override
    public <Solution_> DestinationSelector<Solution_> applyNearbySelection(DestinationSelectorConfig config,
            HeuristicConfigPolicy<Solution_> configPolicy, SelectionCacheType minimumCacheType,
            SelectionOrder resolvedSelectionOrder, ElementDestinationSelector<Solution_> destinationSelector) {
        NearbySelectionConfig nearbySelectionConfig = config.getNearbySelectionConfig();
        nearbySelectionConfig.validateNearby(minimumCacheType, resolvedSelectionOrder);

        boolean randomSelection = resolvedSelectionOrder.toRandomSelectionBoolean();

        NearbyDistanceMeter<?, ?> nearbyDistanceMeter =
                configPolicy.getClassInstanceCache().newInstance(nearbySelectionConfig,
                        "nearbyDistanceMeterClass", nearbySelectionConfig.getNearbyDistanceMeterClass());
        // TODO Check nearbyDistanceMeterClass.getGenericInterfaces() to confirm generic type S is an entityClass
        NearbyRandom nearbyRandom = NearbyRandomFactory.create(nearbySelectionConfig).buildNearbyRandom(randomSelection);

        if (nearbySelectionConfig.getOriginValueSelectorConfig() != null) {
            ValueSelector<Solution_> originValueSelector = ValueSelectorFactory
                    .<Solution_> create(nearbySelectionConfig.getOriginValueSelectorConfig())
                    .buildValueSelector(configPolicy, destinationSelector.getEntityDescriptor(), minimumCacheType,
                            resolvedSelectionOrder);
            return new NearValueNearbyDestinationSelector<>(
                    destinationSelector,
                    ((EntityIndependentValueSelector<Solution_>) originValueSelector),
                    nearbyDistanceMeter,
                    nearbyRandom,
                    randomSelection);
        } else if (nearbySelectionConfig.getOriginSubListSelectorConfig() != null) {
            SubListSelector<Solution_> subListSelector = SubListSelectorFactory
                    .<Solution_> create(nearbySelectionConfig.getOriginSubListSelectorConfig())
                    // Entity selector not needed for replaying selector.
                    .buildSubListSelector(configPolicy, null, minimumCacheType, resolvedSelectionOrder);
            return new NearSubListNearbyDestinationSelector<>(
                    destinationSelector,
                    subListSelector,
                    nearbyDistanceMeter,
                    nearbyRandom,
                    randomSelection);
        } else {
            throw new IllegalArgumentException("The destinationSelector (" + config
                    + ")'s nearbySelectionConfig (" + nearbySelectionConfig
                    + ") requires an originSubListSelector or an originValueSelector.");
        }
    }

}
