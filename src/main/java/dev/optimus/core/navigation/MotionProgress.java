package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;

/** Detect attempted movement without progress; turning/searching/airborne time does not count. */
public final class MotionProgress {
    private Vec3Point anchor;
    private long since;

    public void reset() {
        anchor = null;
    }

    public boolean stuck(Vec3Point current, long tick, boolean trying, boolean grounded) {
        if (!trying
                || !grounded
                || anchor == null
                || tick < since
                || current.horizontalDistanceSq(anchor) > .0144
                || Math.abs(current.y - anchor.y) > .3) {
            anchor = current;
            since = tick;
            return false;
        }
        if (tick - since >= 10) {
            anchor = current;
            since = tick;
            return true;
        }
        return false;
    }
}