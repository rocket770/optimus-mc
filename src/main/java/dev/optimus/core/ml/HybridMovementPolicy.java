package dev.optimus.core.ml;

import dev.optimus.core.navigation.NearbySteering;
import java.util.function.Predicate;

/**
 * Guarded two-expert movement arbiter: (a) a movement classifier trained against the Optimus
 * 16-feature/9-action contract, (b) a conservative 1.8.9 boxing combo-window expert inspired by the
 * *ideas* in Judas. No upstream Judas/PVP ROBO checkpoint is represented as a native Optimus model.
 * This class has no authority over attacks, targets, authentication or geometry.
 */
public final class HybridMovementPolicy {
    public enum Source {
        ORIGINAL,
        LEARNED,
        FUSED,
        COMBO_BRAKE
    }

    public static final class Decision {
        public final NearbySteering.Input action;
        public final Source source;

        public Decision(NearbySteering.Input action, Source source) {
            this.action = action;
            this.source = source;
        }
    }

    private final MovementModel movement;
    private final MovementModel combat;
    private final double confidence;
    private final boolean tactical;

    public HybridMovementPolicy(
            MovementModel movement, MovementModel combat, double confidence, boolean tactical) {
        this.movement = movement;
        this.combat = combat;
        this.confidence = confidence;
        this.tactical = tactical;
    }

    /** The caller has already passed its combat, permission and navigation gates. */
    public Decision choose(
            double[] features,
            NearbySteering.Input original,
            Predicate<NearbySteering.Input> safe) {
        Decision fallback = new Decision(original, Source.ORIGINAL);
        if (original == null
                || safe == null
                || features == null
                || features.length != MovementFeatures.COUNT) return fallback;
        for (double f : features) if (!Double.isFinite(f)) return fallback;
        NearbySteering.Input moving =
                movement == null ? null : movement.suggest(features, confidence, safe);
        NearbySteering.Input boxing =
                combat == null ? null : combat.suggest(features, confidence, safe);
        if (moving == null && boxing == null) return fallback;
        NearbySteering.Input predicted;
        Source source;
        if (boxing == null) {
            predicted = moving;
            source = Source.LEARNED;
        } else if (moving == null) {
            predicted = boxing;
            source = Source.LEARNED;
        } else if (boxing == moving) {
            predicted = moving;
            source = Source.FUSED;
        } else {
            // Two separately trained policies disagree: do not force a flip in direction.
            // Their common forward intent is useful even if they differ on strafing.
            if (boxing.forward != moving.forward
                    || (boxing.side != 0 && moving.side != 0 && boxing.side != moving.side))
                return fallback;
            // Prefer the movement network's side step only when both agree on drive intent.
            predicted = moving;
            source = Source.FUSED;
        }

        if (!tactical) return new Decision(predicted, source);
        NearbySteering.Input advice = comboWindow(features);
        if (advice == null) return new Decision(predicted, source);
        if (!safe.test(advice)) return fallback;
        if (advice == NearbySteering.Input.NONE) {
            // Release W only when the learned models are still driving INTO an opponent
            // while the target is in hurt-time; original tap and escape paths run elsewhere.
            if (predicted.forward > 0 && original.forward >= 0)
                return new Decision(NearbySteering.Input.NONE, Source.COMBO_BRAKE);
            return new Decision(predicted, source);
        }
        // At re-hit spacing, block retreat from a target already in hit stun.
        // Avoid inventing new attack/sprint inputs: movement only.
        if (advice.forward > 0 && predicted.forward < 0) return fallback;
        return new Decision(predicted, source);
    }

    /**
     * Conservative, strictly opt-in tactical movement recommendation for a 1.8.9 boxing window.
     * This is not an imported Judas neural network or copied upstream source code.
     */
    public static NearbySteering.Input comboWindow(double[] f) {
        if (f == null || f.length != MovementFeatures.COUNT) return null;
        for (double value : f) if (!Double.isFinite(value)) return null;
        double distance = f[0] * 4.0;
        double yawError = Math.abs(f[1] * 90.0);
        double ownHurt = f[10], targetHurt = f[11];
        if (distance < 0 || distance > 3.55 || yawError > 22 || f[12] < 0.5 || f[13] > 0.5)
            return null;
        // Only act when the opponent is observably taking more knockback than we are.
        if (targetHurt < 0.35 || targetHurt < ownHurt + 0.25) return null;
        if (distance < 2.05 && targetHurt > 0.50) return NearbySteering.Input.NONE;
        if (distance >= 2.40) return NearbySteering.Input.FORWARD;
        return null;
    }
}