package dev.optimus.core.ml;

import dev.optimus.core.navigation.NearbySteering;
import java.util.function.Predicate;

/** Optional steering port. The caller retains its normal eligibility and final geometry gates. */
public interface MovementAdvisor {
    NearbySteering.Input choose(
            long tick,
            double[] features,
            NearbySteering.Input baseline,
            Predicate<NearbySteering.Input> safe);
}