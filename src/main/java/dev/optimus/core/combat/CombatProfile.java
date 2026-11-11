package dev.optimus.core.combat;

import java.util.Locale;
import java.util.Random;

/** Explicit comparison profiles, not claims about any anti-cheat's thresholds. */
public enum CombatProfile {
    // Default controller preset; every profile uses the same lobby/pit safety checks.
    BALANCED(32, 18, 10, 2, 4, 8, 8, true),
    BASELINE(14, 8, 0, 4, 6, 14, 16, true),
    PACED(6, 4, 1.5f, 7, 10, 3, 3, false);
    public final float yawLimit, pitchLimit, acceleration, yawTolerance, pitchTolerance;
    public final int minimumAttackTicks, maximumAttackTicks;
    public final boolean strafe;

    CombatProfile(
            float yaw,
            float pitch,
            float acceleration,
            int minimum,
            int maximum,
            float yawTolerance,
            float pitchTolerance,
            boolean strafe) {
        this.yawLimit = yaw;
        this.pitchLimit = pitch;
        this.acceleration = acceleration;
        this.minimumAttackTicks = minimum;
        this.maximumAttackTicks = maximum;
        this.yawTolerance = yawTolerance;
        this.pitchTolerance = pitchTolerance;
        this.strafe = strafe;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CombatProfile parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public int attackDelay(Random random) {
        return minimumAttackTicks + random.nextInt(maximumAttackTicks - minimumAttackTicks + 1);
    }
}