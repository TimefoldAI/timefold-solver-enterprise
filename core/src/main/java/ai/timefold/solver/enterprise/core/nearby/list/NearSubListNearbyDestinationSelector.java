package ai.timefold.solver.enterprise.core.nearby.list;

import java.util.Iterator;
import java.util.function.Function;

import ai.timefold.solver.core.impl.heuristic.selector.common.nearby.NearbyDistanceMeter;
import ai.timefold.solver.core.impl.heuristic.selector.list.DestinationSelector;
import ai.timefold.solver.core.impl.heuristic.selector.list.ElementDestinationSelector;
import ai.timefold.solver.core.impl.heuristic.selector.list.ElementRef;
import ai.timefold.solver.core.impl.heuristic.selector.list.SubList;
import ai.timefold.solver.core.impl.heuristic.selector.list.SubListSelector;
import ai.timefold.solver.core.impl.heuristic.selector.list.mimic.MimicReplayingSubListSelector;
import ai.timefold.solver.enterprise.core.nearby.common.AbstractNearbyDistanceMatrixDemand;
import ai.timefold.solver.enterprise.core.nearby.common.NearbyRandom;

public final class NearSubListNearbyDestinationSelector<Solution_>
        extends AbstractNearbyDestinationSelector<Solution_, MimicReplayingSubListSelector<Solution_>>
        implements DestinationSelector<Solution_> {

    public NearSubListNearbyDestinationSelector(ElementDestinationSelector<Solution_> childDestinationSelector,
            SubListSelector<Solution_> originSubListSelector, NearbyDistanceMeter<?, ?> nearbyDistanceMeter,
            NearbyRandom nearbyRandom, boolean randomSelection) {
        super(childDestinationSelector, originSubListSelector, nearbyDistanceMeter, nearbyRandom, randomSelection);
    }

    @Override
    protected MimicReplayingSubListSelector<Solution_> castReplayingSelector(Object uncastReplayingSelector) {
        if (!(uncastReplayingSelector instanceof MimicReplayingSubListSelector)) {
            // In order to select a nearby destination, we must first have something to be near by.
            throw new IllegalStateException("Impossible state: Nearby destination selector (" + this +
                    ") did not receive a replaying subList selector (" + uncastReplayingSelector + ").");
        }
        return (MimicReplayingSubListSelector<Solution_>) uncastReplayingSelector;
    }

    @Override
    protected AbstractNearbyDistanceMatrixDemand<?, ?, ?, ?> createDemand() {
        return new SubListNearbyDistanceMatrixDemand<>(nearbyDistanceMeter, nearbyRandom, childSelector, replayingSelector,
                origin -> computeDestinationSize());
    }

    // ************************************************************************
    // Worker methods
    // ************************************************************************

    @Override
    public Iterator<ElementRef> iterator() {
        Iterator<SubList> replayingOriginSubListIterator = replayingSelector.iterator();
        Function<Iterator<?>, Object> originFunction = i -> {
            SubList subList = (SubList) i.next();
            // Origin is the subList's first element.
            return firstElement(subList);
        };
        if (!randomSelection) {
            return new OriginalNearbyDestinationIterator(nearbyDistanceMatrix, replayingOriginSubListIterator, originFunction,
                    this::elementRef, childSelector.getSize());
        } else {
            return new RandomNearbyDestinationIterator(nearbyDistanceMatrix, nearbyRandom, workingRandom,
                    replayingOriginSubListIterator, originFunction, this::elementRef, childSelector.getSize());
        }
    }

    private Object firstElement(SubList subList) {
        return replayingSelector.getVariableDescriptor().getElement(subList.getEntity(), subList.getFromIndex());
    }

}
