package dev.optimus.core.combat;

import java.util.Random;

/** Bounded combat turns with discrete braking; reset across targets, GUIs and sessions. */
public final class CombatTurn {
    private float yawVelocity, pitchVelocity;
    private final Random random = new Random(0x5455524eL);
    private final Envelope yawEnvelope = new Envelope(), pitchEnvelope = new Envelope();

    private static final class Envelope {
        int direction;
        float speed = 1, acceleration = 1, lastGap;

        void reset() {
            direction = 0;
        }
    }

    public void seed(long seed) {
        random.setSeed(seed);
        reset();
    }

    public void reset() {
        yawVelocity = pitchVelocity = 0;
        yawEnvelope.reset();
        pitchEnvelope.reset();
    }

    public static float wrap(float angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private static float step(float gap, float previous, float limit, float acceleration) {
        if (!Float.isFinite(gap)) return 0;
        float desired = clamp(gap, limit);
        // The original profile applies an immediate fixed cap.
        if (acceleration == 0) return desired;
        float velocity = previous + clamp(desired - previous, acceleration);
        // Never drift away from the target or overshoot. Clipping may brake faster
        // than the acceleration limit on a reversal or near the final angle.
        if (velocity * gap <= 0) return 0;
        return Math.copySign(Math.min(Math.abs(velocity), Math.abs(gap)), gap);
    }

    private float balanced(
            float gap, float previous, float limit, float acceleration, Envelope envelope) {
        if (!Float.isFinite(gap) || gap == 0) {
            envelope.reset();
            return 0;
        }
        int direction = gap > 0 ? 1 : -1;
        float targetMotion = 0;
        if (envelope.direction != direction) {
            // Keep one envelope during an acquisition, without per-frame noise
            // or offsets to the actual target point. Reversals cannot coast away.
            envelope.direction = direction;
            envelope.speed = .92f + random.nextFloat() * .08f;
            envelope.acceleration = .9f + random.nextFloat() * .1f;
            previous = 0;
        } else {
            // The error changed by target motion minus our previous turn.
            // Follow a continuing angular motion while braking the residual
            // catch-up, so a strafing opponent does not create braking lag.
            float observed = wrap(gap - envelope.lastGap + previous) * direction;
            if (observed > 0 && observed <= limit) targetMotion = observed;
        }
        envelope.lastGap = gap;
        double a = acceleration * envelope.acceleration, distance = Math.abs(gap);
        // If this tick moves at v, braking travels v + (v-a) + ... . Solve
        // that discrete stopping distance so stationary turns brake before
        // arrival, rather than clipping a fast constant-speed turn at the end.
        double catchUp = Math.max(0, distance - targetMotion);
        double n = Math.max(1, Math.ceil((Math.sqrt(1 + 8 * catchUp / a) - 1) / 2));
        double brakingSpeed = (catchUp + a * n * (n - 1) / 2) / n;
        float desired =
                (float) Math.min(limit * envelope.speed, targetMotion + brakingSpeed) * direction;
        float velocity = previous + clamp(desired - previous, (float) a);
        // A moving opponent can change the remaining angle abruptly. Correct
        // a reversal immediately and clip only to its current remaining angle.
        if (velocity * gap <= 0) return 0;
        return Math.copySign(Math.min(Math.abs(velocity), Math.abs(gap)), gap);
    }

    public float yaw(float gap, CombatProfile profile) {
        return yaw(gap, profile, false);
    }

    public float yaw(float gap, CombatProfile profile, boolean reacting) {
        return yawVelocity =
                profile == CombatProfile.BALANCED
                        ? balanced(
                                wrap(gap),
                                yawVelocity,
                                profile.yawLimit,
                                profile.acceleration * (reacting ? 1.6f : 1),
                                yawEnvelope)
                        : step(wrap(gap), yawVelocity, profile.yawLimit, profile.acceleration);
    }

    public float pitch(float gap, CombatProfile profile) {
        return pitch(gap, profile, false);
    }

    public float pitch(float gap, CombatProfile profile, boolean reacting) {
        return pitchVelocity =
                profile == CombatProfile.BALANCED
                        ? balanced(
                                gap,
                                pitchVelocity,
                                profile.pitchLimit,
                                profile.acceleration * .6f * (reacting ? 1.6f : 1),
                                pitchEnvelope)
                        : step(gap, pitchVelocity, profile.pitchLimit, profile.acceleration);
    }
}