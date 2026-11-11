package dev.optimus.core.combat;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.Vec3Point;
import java.util.function.Predicate;

/** Aim at the actual body and intersect its unexpanded box with the current view ray. */
public final class CombatAim {
    private CombatAim() {}

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static Vec3Point point(Vec3Point eye, CollisionGeometry.Box box) {
        double height = box.maxY - box.minY;
        return new Vec3Point(
                (box.minX + box.maxX) / 2,
                clamp(eye.y, box.minY + height * .2, box.maxY - height * .15),
                (box.minZ + box.maxZ) / 2);
    }
    /** Prefer the usual body point, then exposed parts of the same unexpanded hitbox. */
    public static Vec3Point clearPoint(
            Vec3Point eye, CollisionGeometry.Box box, double reach, Predicate<Vec3Point> clearHit) {
        Vec3Point preferred = point(eye, box);
        if (clearPointHit(eye, box, preferred, reach, clearHit)) return preferred;
        double cx = (box.minX + box.maxX) / 2, cz = (box.minZ + box.maxZ) / 2;
        double sx = (box.maxX - box.minX) * .3, sz = (box.maxZ - box.minZ) * .3;
        for (double height : new double[] {.85, .65, .5, .35, .2})
            for (double[] offset : new double[][] {{0, 0}, {-sx, 0}, {sx, 0}, {0, -sz}, {0, sz}}) {
                Vec3Point candidate =
                        new Vec3Point(
                                cx + offset[0],
                                box.minY + (box.maxY - box.minY) * height,
                                cz + offset[1]);
                if (clearPointHit(eye, box, candidate, reach, clearHit)) return candidate;
            }
        return null;
    }

    private static boolean clearPointHit(
            Vec3Point eye,
            CollisionGeometry.Box box,
            Vec3Point point,
            double reach,
            Predicate<Vec3Point> clearHit) {
        Vec3Point intersection =
                hit(
                        eye,
                        box,
                        new Vec3Point(point.x - eye.x, point.y - eye.y, point.z - eye.z),
                        reach);
        return intersection != null && clearHit.test(intersection);
    }

    public static double distanceSquared(Vec3Point eye, CollisionGeometry.Box box) {
        double x = eye.x - clamp(eye.x, box.minX, box.maxX),
                y = eye.y - clamp(eye.y, box.minY, box.maxY),
                z = eye.z - clamp(eye.z, box.minZ, box.maxZ);
        return x * x + y * y + z * z;
    }

    public static Vec3Point hit(
            Vec3Point eye, CollisionGeometry.Box box, float yaw, float pitch, double reach) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Double.isFinite(reach) || reach <= 0)
            return null;
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        return hit(
                eye,
                box,
                new Vec3Point(
                        -Math.sin(ry) * Math.cos(rp), -Math.sin(rp), Math.cos(ry) * Math.cos(rp)),
                reach);
    }

    public static Vec3Point hit(
            Vec3Point eye, CollisionGeometry.Box box, Vec3Point look, double reach) {
        if (!Double.isFinite(reach) || reach <= 0) return null;
        for (double value :
                new double[] {
                    eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    look.x, look.y, look.z
                }) if (!Double.isFinite(value)) return null;
        double length = Math.sqrt(look.x * look.x + look.y * look.y + look.z * look.z);
        if (length < 1e-9) return null;
        double[] start = {eye.x, eye.y, eye.z},
                direction = {look.x / length, look.y / length, look.z / length};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        double near = 0, far = reach;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(direction[i]) < 1e-9) {
                if (start[i] < min[i] || start[i] > max[i]) return null;
                continue;
            }
            double a = (min[i] - start[i]) / direction[i], b = (max[i] - start[i]) / direction[i];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) return null;
        }
        return new Vec3Point(
                eye.x + direction[0] * near,
                eye.y + direction[1] * near,
                eye.z + direction[2] * near);
    }
}