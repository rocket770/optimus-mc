package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;
import java.util.*;

/**
 * Conservative walk-speed, cardinal jumps over one missing cell; all samples need live collision
 * checks.
 */
public final class JumpArc {
    private JumpArc() {}

    public static boolean candidate(Vec3Point from, Vec3Point to) {
        if (from == null || to == null) return false;
        double dx = Math.abs(to.x - from.x), dz = Math.abs(to.z - from.z), rise = to.y - from.y;
        return ((Math.abs(dx - 2) < 0.01 && dz < 0.01) || (Math.abs(dz - 2) < 0.01 && dx < 0.01))
                && rise <= 0
                && rise >= -1.0;
    }

    public static List<Vec3Point> samples(Vec3Point from, Vec3Point to) {
        return samples(from, to, 0.20);
    }

    public static List<Vec3Point> samples(Vec3Point from, Vec3Point to, double initialSpeed) {
        if (!candidate(from, to)
                || !Double.isFinite(initialSpeed)
                || initialSpeed < 0.14
                || initialSpeed > 0.28) return Collections.emptyList();
        double distance = Math.sqrt(from.horizontalDistanceSq(to));
        double dx = (to.x - from.x) / distance, dz = (to.z - from.z) / distance;
        double along = 0, y = from.y, vertical = 0.42, horizontal = initialSpeed;
        List<Vec3Point> samples = new ArrayList<>();
        samples.add(from);
        for (int tick = 0; tick < 24; tick++) {
            double remaining = distance - along;
            // Forward input until close; counter-steer to arrest momentum before landing.
            horizontal = horizontal * 0.91 + (remaining < 0.6 ? -0.02 : 0.02);
            horizontal = Math.max(0, horizontal);
            along += horizontal;
            y += vertical;
            vertical = (vertical - 0.08) * 0.98;
            samples.add(new Vec3Point(from.x + dx * along, y, from.z + dz * along));
            if (y <= to.y && vertical < 0) {
                if (Math.abs(along - distance) > 0.35) return Collections.emptyList();
                samples.set(
                        samples.size() - 1,
                        new Vec3Point(from.x + dx * along, to.y, from.z + dz * along));
                return samples;
            }
        }
        return Collections.emptyList();
    }
}