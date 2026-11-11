package dev.optimus.core.ml;

import dev.optimus.core.navigation.NearbySteering;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Versioned movement feature and action encoding shared by inference and recording. */
public final class MovementFeatures {
    public static final int COUNT = 16;

    private MovementFeatures() {}

    public static final List<String> NAMES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "distance",
                            "yaw_gap",
                            "radial_speed",
                            "toward_speed",
                            "target_forward_speed",
                            "target_side_speed",
                            "self_forward_speed",
                            "self_side_speed",
                            "self_health",
                            "target_health",
                            "self_hurt",
                            "target_hurt",
                            "melee_reachable",
                            "reacting",
                            "direct_pursuit",
                            "nearby_pursuit"));

    public static NearbySteering.Input action(
            boolean forward, boolean back, boolean left, boolean right) {
        int f = forward ? 1 : back ? -1 : 0, side = left ? 1 : right ? -1 : 0;
        for (NearbySteering.Input input : NearbySteering.Input.values())
            if (input.forward == f && input.side == side) return input;
        return NearbySteering.Input.NONE;
    }
    /** Features are clipped/scaled so training exports remain portable across coordinates. */
    public static double[] features(
            double distance,
            double yawGap,
            double radialSpeed,
            double towardSpeed,
            double targetDx,
            double targetDz,
            double selfMotionX,
            double selfMotionZ,
            double yaw,
            double health,
            double targetHealth,
            int hurtTime,
            int targetHurtTime,
            boolean reachable,
            boolean reacting,
            boolean direct,
            boolean nearby) {
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r), cos = Math.cos(r);
        return new double[] {
            clamp(distance / 4.0, -2, 4),
            clamp(yawGap / 90.0, -2, 2),
            clamp(radialSpeed * 5, -5, 5),
            clamp(towardSpeed * 5, -5, 5),
            clamp((-sin * targetDx + cos * targetDz) * 5, -5, 5),
            clamp((cos * targetDx + sin * targetDz) * 5, -5, 5),
            clamp((-sin * selfMotionX + cos * selfMotionZ) * 5, -5, 5),
            clamp((cos * selfMotionX + sin * selfMotionZ) * 5, -5, 5),
            clamp(health / 20, 0, 2),
            clamp(targetHealth / 20, 0, 2),
            clamp(hurtTime / 10.0, 0, 2),
            clamp(targetHurtTime / 10.0, 0, 2),
            reachable ? 1 : 0,
            reacting ? 1 : 0,
            direct ? 1 : 0,
            nearby ? 1 : 0
        };
    }

    private static double clamp(double v, double low, double high) {
        return Double.isFinite(v) ? Math.max(low, Math.min(high, v)) : Double.NaN;
    }
}