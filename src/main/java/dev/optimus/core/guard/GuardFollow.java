package dev.optimus.core.guard;

import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;
import java.util.function.Predicate;

/** One-tick ordinary-key tracking of a point just behind the guarded player's view. */
public final class GuardFollow {
    public static final double OFFSET = .44;

    private GuardFollow() {}

    public static Vec3Point anchor(Vec3Point leader, float yaw) {
        double angle = Math.toRadians(yaw);
        return new Vec3Point(
                leader.x + Math.sin(angle) * OFFSET, leader.y, leader.z - Math.cos(angle) * OFFSET);
    }

    public static NearbySteering.Input choose(
            Vec3Point self,
            Vec3Point motion,
            Vec3Point leader,
            Vec3Point velocity,
            float leaderYaw,
            float viewYaw,
            double acceleration,
            NearbySteering.Input mirrored,
            Predicate<NearbySteering.Input> safe) {
        return choose(
                self,
                motion,
                leader,
                anchor(leader, leaderYaw),
                velocity,
                leaderYaw,
                viewYaw,
                acceleration,
                mirrored,
                safe);
    }

    public static NearbySteering.Input choose(
            Vec3Point self,
            Vec3Point motion,
            Vec3Point leader,
            Vec3Point anchor,
            Vec3Point velocity,
            float leaderYaw,
            float viewYaw,
            double acceleration,
            NearbySteering.Input mirrored,
            Predicate<NearbySteering.Input> safe) {
        return choose(
                self,
                motion,
                leader,
                anchor,
                velocity,
                leaderYaw,
                viewYaw,
                acceleration,
                .546,
                mirrored,
                safe);
    }

    public static NearbySteering.Input choose(
            Vec3Point self,
            Vec3Point motion,
            Vec3Point leader,
            Vec3Point anchor,
            Vec3Point velocity,
            float leaderYaw,
            float viewYaw,
            double acceleration,
            double friction,
            NearbySteering.Input mirrored,
            Predicate<NearbySteering.Input> safe) {
        double angle = Math.toRadians(leaderYaw), fx = -Math.sin(angle), fz = Math.cos(angle);
        double view = Math.toRadians(viewYaw), ix = -Math.sin(view), iz = Math.cos(view);
        double desiredFront = (anchor.x - leader.x) * fx + (anchor.z - leader.z) * fz;
        double frontLimit = Math.max(-.30, desiredFront);
        double vx = clamp(velocity.x, .4), vz = clamp(velocity.z, .4);
        // Near a stationary point, score the native coasting endpoint. A
        // one-frame score repeatedly over-corrects with full-strength keys.
        double horizon = vx * vx + vz * vz < .08 * .08 ? 1 / Math.max(.05, 1 - friction) : 1;
        double best = Double.POSITIVE_INFINITY;
        NearbySteering.Input chosen = NearbySteering.Input.NONE;
        for (NearbySteering.Input input : NearbySteering.Input.values()) {
            if (!safe.test(input)) continue;
            double length = Math.max(1, Math.hypot(input.forward * .98, input.side * .98));
            double mx =
                    motion.x + (ix * input.forward + iz * input.side) * .98 / length * acceleration;
            double mz =
                    motion.z + (iz * input.forward - ix * input.side) * .98 / length * acceleration;
            double x = self.x + mx * horizon, z = self.z + mz * horizon;
            double dx = x - anchor.x - vx * horizon, dz = z - anchor.z - vz * horizon;
            double front = (x - leader.x - vx * horizon) * fx + (z - leader.z - vz * horizon) * fz;
            double score =
                    dx * dx
                            + dz * dz
                            + .3 * ((mx - vx) * (mx - vx) + (mz - vz) * (mz - vz))
                            + 12 * Math.pow(Math.max(0, front - frontLimit), 2);
            // Input mirroring breaks close ties; position correction still prevents drift.
            if (mirrored != null && input == mirrored) score -= .0008;
            if (score < best) {
                best = score;
                chosen = input;
            }
        }
        return chosen;
    }

    /** A bounded travel endpoint for a straight-moving leader, never a combat aim point. */
    public static Vec3Point travelGoal(Vec3Point self, Vec3Point anchor, Vec3Point velocity) {
        double distance = Math.sqrt(self.horizontalDistanceSq(anchor));
        if (distance < 1.25 || Math.abs(self.y - anchor.y) > .1) return null;
        double dx = (anchor.x - self.x) / distance, dz = (anchor.z - self.z) / distance;
        double along = velocity.x * dx + velocity.z * dz,
                cross = Math.abs(velocity.x * dz - velocity.z * dx);
        if (along < .10 || cross > along * .35) return distance >= 4.5 ? anchor : null;
        double lead = Math.min(3.5, along * 12);
        return new Vec3Point(anchor.x + dx * lead, anchor.y, anchor.z + dz * lead);
    }

    public static double clamp(double value, double cap) {
        return Math.max(-cap, Math.min(cap, value));
    }
}