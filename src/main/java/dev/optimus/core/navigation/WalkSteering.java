package dev.optimus.core.navigation;

import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.Vec3Point;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/** Relative WASD choices, small required alignment corrections and waypoint progress. */
public final class WalkSteering {
    private float velocity;

    public void reset() {
        velocity = 0;
    }

    public static NearbySteering.Input input(float travelYaw, float viewYaw) {
        NearbySteering.Input chosen = NearbySteering.Input.NONE;
        double best = -Double.MAX_VALUE;
        double angle = Math.toRadians(CombatTurn.wrap(travelYaw - viewYaw));
        for (NearbySteering.Input input : NearbySteering.Input.values()) {
            if (input == NearbySteering.Input.NONE) continue;
            double score =
                    (input.forward * Math.cos(angle) - input.side * Math.sin(angle))
                            / Math.hypot(input.forward, input.side);
            if (score > best) {
                chosen = input;
                best = score;
            }
        }
        return chosen;
    }

    public static float alignment(float travelYaw, float viewYaw) {
        float gap = CombatTurn.wrap(travelYaw - viewYaw);
        return gap - Math.round(gap / 45) * 45;
    }

    public float turn(float error) {
        error = CombatTurn.wrap(error);
        if (Math.abs(error) < .8f) {
            velocity = 0;
            return 0;
        }
        float desired = Math.max(-12, Math.min(12, error * .45f));
        velocity += Math.max(-3, Math.min(3, desired - velocity));
        if (velocity * error <= 0) velocity = 0;
        return Math.copySign(Math.min(Math.abs(velocity), Math.abs(error)), error);
    }

    public static boolean passed(Vec3Point previous, Vec3Point next, Vec3Point current) {
        if (previous == null || next == null || Math.abs(current.y - next.y) > .6) return false;
        double dx = next.x - previous.x, dz = next.z - previous.z, length = dx * dx + dz * dz;
        if (length < .01) return false;
        double x = current.x - previous.x, z = current.z - previous.z;
        double along = (x * dx + z * dz) / length;
        double cross = x * dz - z * dx;
        return along >= 1 && cross * cross / length <= .25;
    }

    /** Return the actual route cursor as well as the aim point, so skipped corners stay skipped. */
    public static int lookAhead(
            int index,
            int size,
            Vec3Point current,
            IntFunction<Vec3Point> point,
            Predicate<Vec3Point> safeWalk) {
        int chosen = index;
        for (int ahead = index + 1; ahead < Math.min(size, index + 12); ahead++) {
            Vec3Point candidate = point.apply(ahead);
            if (current.horizontalDistanceSq(candidate) > 36 || !safeWalk.test(candidate)) break;
            chosen = ahead;
        }
        return chosen;
    }

    /** Rejoin a hinted corridor beyond an obsolete detour, using a checked walk. */
    public static int shortcut(
            int index,
            int size,
            Vec3Point current,
            IntFunction<Vec3Point> point,
            Predicate<Vec3Point> safeWalk) {
        int checked = 0;
        for (int ahead = size - 1; ahead > index && checked < 24; ahead--) {
            Vec3Point candidate = point.apply(ahead);
            if (candidate == null || current.horizontalDistanceSq(candidate) > 64) continue;
            checked++;
            // A blocked earlier corner need not block a later point approached
            // from a different side. The whole new connector still needs floor.
            if (safeWalk.test(candidate)) return ahead;
        }
        return index;
    }
}