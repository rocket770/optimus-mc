package dev.optimus.learning;

import dev.optimus.core.ml.HybridMovementPolicy;
import dev.optimus.core.ml.MovementAdvisor;
import dev.optimus.core.ml.MovementModel;
import dev.optimus.core.navigation.NearbySteering;
import java.util.Locale;
import java.util.function.Predicate;

/** Inference and mode selection only; model sources and recordings have separate owners. */
public final class LearningRuntime implements MovementAdvisor {
    private final MovementModel movement;
    private final MovementModel expert;
    private LearningConfiguration configuration;
    private HybridMovementPolicy policy;
    private long observed, suggested, applied, tactical, failures;

    public LearningRuntime(
            LearningConfiguration configuration, MovementModel movement, MovementModel expert) {
        this.movement = movement;
        this.expert = expert;
        configure(configuration);
    }

    public void configure(LearningConfiguration configuration) {
        this.configuration = configuration;
        policy =
                new HybridMovementPolicy(
                        movement,
                        configuration.hybrid ? expert : null,
                        configuration.confidence,
                        configuration.hybrid);
    }

    public void resetCounters() {
        observed = suggested = applied = tactical = failures = 0;
    }

    @Override
    public NearbySteering.Input choose(
            long tick,
            double[] features,
            NearbySteering.Input baseline,
            Predicate<NearbySteering.Input> safe) {
        if (configuration.mode == LearningConfiguration.Mode.OFF) return baseline;
        observed++;
        try {
            HybridMovementPolicy.Decision decision = policy.choose(features, baseline, safe);
            // A model implementation is a recommendation, never an authority to move.
            if (decision.source == HybridMovementPolicy.Source.ORIGINAL
                    || decision.action == null
                    || safe == null
                    || !safe.test(decision.action)) return baseline;
            if (decision.source == HybridMovementPolicy.Source.COMBO_BRAKE) tactical++;
            else suggested++;
            if (configuration.mode != LearningConfiguration.Mode.LIVE) return baseline;
            applied++;
            return decision.action;
        } catch (RuntimeException error) {
            failures++;
            return baseline;
        }
    }

    public String status() {
        return "ML: "
                + configuration.mode.name().toLowerCase(Locale.ROOT)
                + "; model="
                + (movement != null)
                + "; expert="
                + (configuration.hybrid && expert != null)
                + "; confidence="
                + configuration.confidence
                + "; suggested="
                + suggested
                + "; applied="
                + applied
                + "/"
                + observed
                + "; tactical="
                + tactical
                + "; errors="
                + failures;
    }
}