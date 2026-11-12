package dev.optimus.core.navigation;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;

/** Sprint eligibility follows safe run-out and the destination, not each tiny grid waypoint. */
public final class TravelSprint {
    private TravelSprint() {}

    public static boolean allowed(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point current,
            Vec3Point waypoint,
            Vec3Point goal,
            boolean chasing) {
        if (current == null
                || waypoint == null
                || goal == null
                || !area.containsBody(goal, .3, 1.8)
                || !geometry.autoStepClear(current, waypoint, area)) return false;
        double distance = Math.sqrt(current.horizontalDistanceSq(waypoint));
        if (distance < .01
                || Math.sqrt(current.horizontalDistanceSq(goal)) <= (chasing ? 2.1 : 1.2))
            return false;
        Vec3Point ahead =
                new Vec3Point(
                        current.x + (waypoint.x - current.x) / distance * 1.5,
                        current.y,
                        current.z + (waypoint.z - current.z) / distance * 1.5);
        // Follow actual stair tops through the supported run-out. Its nominal
        // height is only a starting hint, rather than a flat-ground constraint.
        Vec3Point runOut = geometry.walkEnd(current, ahead, area);
        return runOut != null && runOut.y >= current.y - 3;
    }
}