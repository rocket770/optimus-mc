package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;
import java.util.*;

/** Walk off a supported lip, then descend; every horizontal/vertical sweep must be clear. */
public final class FallTrajectory {
    private FallTrajectory() {}

    public interface Terrain {
        boolean inside(Vec3Point point);

        boolean clear(Vec3Point from, Vec3Point to);

        boolean supported(Vec3Point point);
    }

    public static List<Vec3Point> samples(Vec3Point from, Vec3Point to, Terrain terrain) {
        double distance = Math.sqrt(from.horizontalDistanceSq(to));
        if (distance < 0.1
                || to.y >= from.y
                || !terrain.inside(from)
                || !terrain.supported(from)
                || !terrain.inside(to)
                || !terrain.supported(to)) return Collections.emptyList();
        double dx = (to.x - from.x) / distance, dz = (to.z - from.z) / distance;
        double along = 0, y = from.y, velocity = 0;
        boolean falling = false;
        Vec3Point previous = from;
        List<Vec3Point> samples = new ArrayList<>();
        samples.add(from);
        for (int tick = 0; tick < 60; tick++) {
            along = Math.min(distance, along + 0.20);
            Vec3Point horizontal = new Vec3Point(from.x + dx * along, y, from.z + dz * along);
            if (!terrain.inside(horizontal) || !terrain.clear(previous, horizontal))
                return Collections.emptyList();
            if (!terrain.supported(horizontal)) falling = true;
            if (falling) {
                velocity = (velocity - 0.08) * 0.98;
                y = Math.max(to.y, y + velocity);
            }
            Vec3Point next = new Vec3Point(horizontal.x, y, horizontal.z);
            if (!terrain.inside(next) || !terrain.clear(horizontal, next))
                return Collections.emptyList();
            samples.add(horizontal);
            samples.add(next);
            if (y <= to.y) return along >= distance - 0.30 ? samples : Collections.emptyList();
            previous = next;
        }
        return Collections.emptyList();
    }
}