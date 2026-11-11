package dev.optimus.core.navigation;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.util.function.ToDoubleFunction;

/** Native ground acceleration plus retained motion; includes a supported coasting run-out. */
public final class GroundMotion {
    private GroundMotion() {}

    public static Vec3Point delta(
            double motionX,
            double motionZ,
            float yaw,
            int forward,
            int side,
            double acceleration,
            double inputScale) {
        double f = forward * .98 * inputScale, s = side * .98 * inputScale;
        double length = Math.max(1, Math.hypot(f, s)), angle = Math.toRadians(yaw);
        return new Vec3Point(
                (Math.abs(motionX) < .005 ? 0 : motionX)
                        + (-Math.sin(angle) * f + Math.cos(angle) * s) / length * acceleration,
                0,
                (Math.abs(motionZ) < .005 ? 0 : motionZ)
                        + (Math.cos(angle) * f + Math.sin(angle) * s) / length * acceleration);
    }

    public static boolean safe(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point from,
            double motionX,
            double motionZ,
            float yaw,
            int forward,
            int side,
            double moveSpeed,
            double inputScale,
            boolean sneak,
            ToDoubleFunction<Vec3Point> frictionAt) {
        double friction = frictionAt.applyAsDouble(from);
        if (!Double.isFinite(friction)
                || friction <= 0
                || friction >= 1
                || !Double.isFinite(moveSpeed)) return false;
        Vec3Point delta =
                delta(
                        motionX,
                        motionZ,
                        yaw,
                        forward,
                        side,
                        moveSpeed * .16277136 / (friction * friction * friction),
                        inputScale * (sneak ? .3 : 1));
        Vec3Point current = from;
        for (int tick = 0; tick < 64; tick++) {
            Vec3Point nominal =
                    sneak
                            ? geometry.sneakStep(current, delta.x, delta.z)
                            : new Vec3Point(current.x + delta.x, current.y, current.z + delta.z);
            if (nominal == null) return false;
            Vec3Point next = geometry.walkEnd(current, nominal, area);
            if (next == null) return false;
            // Native sneak clips displacement at the lip, but retained motion still decays
            // normally.
            double x = delta.x * friction, z = delta.z * friction;
            if (Math.abs(x) < .005) x = 0;
            if (Math.abs(z) < .005) z = 0;
            if (x == 0 && z == 0) return true;
            delta = new Vec3Point(x, 0, z);
            current = next;
            friction = frictionAt.applyAsDouble(current);
            if (!Double.isFinite(friction) || friction <= 0 || friction >= 1) return false;
        }
        return false;
    }
}