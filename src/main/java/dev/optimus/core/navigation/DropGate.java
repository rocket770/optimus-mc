package dev.optimus.core.navigation;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;

/** A named rim permits a deep entry fall; ordinary routes retain their three-block drop limit. */
public final class DropGate {
    private DropGate() {}

    public static boolean allows(
            Vec3Point rim, double floor, Vec3Point from, Vec3Point to, PitRegion pit) {
        if (rim == null
                || from == null
                || to == null
                || !Double.isFinite(floor)
                || pit == null
                || !pit.isDefined()) return false;
        double fall = from.y - to.y;
        return fall > 3
                && fall <= 16
                && from.y >= rim.y - 0.5
                && from.y <= rim.y + 0.5
                && to.y >= floor
                && to.y <= rim.y - 3
                && Math.abs(from.x - rim.x) <= 1.5
                && Math.abs(from.z - rim.z) <= 1.5
                && Math.abs(to.x - rim.x) <= 1.5
                && Math.abs(to.z - rim.z) <= 3.5
                && from.horizontalDistanceSq(to) <= 9.01
                && pit.containsBody(to, 0.3, 1.8);
    }

    public static boolean insideFallCorridor(Vec3Point rim, double floor, Vec3Point point) {
        return rim != null
                && point != null
                && Double.isFinite(floor)
                && point.y >= floor - 0.1
                && point.y <= rim.y + 0.6
                && Math.abs(point.x - rim.x) <= 1.8
                && Math.abs(point.z - rim.z) <= 3.8;
    }
}