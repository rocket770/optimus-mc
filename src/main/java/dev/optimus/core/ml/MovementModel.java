package dev.optimus.core.ml;

import dev.optimus.core.navigation.NearbySteering;
import java.util.function.Predicate;

/** A local model may abstain; it never owns permissions, targets, aim or attacks. */
public interface MovementModel {
    NearbySteering.Input suggest(
            double[] features, double confidence, Predicate<NearbySteering.Input> safe);
}