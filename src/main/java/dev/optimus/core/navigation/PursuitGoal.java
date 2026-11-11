package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;

/**
 * Give a moving-target search time to finish, then follow its checked route briefly before
 * refreshing.
 */
public final class PursuitGoal {
    private PursuitGoal() {}

    public static boolean refresh(
            Vec3Point planned, Vec3Point current, boolean searching, long age) {
        if (planned == null || current == null) return true;
        double horizontal = planned.horizontalDistanceSq(current),
                vertical = Math.abs(planned.y - current.y);
        if (horizontal <= 1 && vertical <= .5) return false;
        if (horizontal > 36 || vertical > 3 || age < 0) return true;
        return age >= (searching ? 60 : 6);
    }
}