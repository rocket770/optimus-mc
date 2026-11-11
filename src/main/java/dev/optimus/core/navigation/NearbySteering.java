package dev.optimus.core.navigation;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;

/**
 * A few checked ordinary-key choices; no grid alignment, route search or target-floor requirement.
 */
public final class NearbySteering {
    public enum Input {
        NONE(0, 0),
        FORWARD(1, 0),
        LEFT(0, 1),
        RIGHT(0, -1),
        FORWARD_LEFT(1, 1),
        FORWARD_RIGHT(1, -1),
        BACK(-1, 0),
        BACK_LEFT(-1, 1),
        BACK_RIGHT(-1, -1);
        public final int forward, side;

        Input(int forward, int side) {
            this.forward = forward;
            this.side = side;
        }

        public Vec3Point point(Vec3Point from, float yaw, double distance) {
            double angle = Math.toRadians(yaw), length = Math.hypot(forward, side);
            if (length == 0) return from;
            return new Vec3Point(
                    from.x
                            + (-Math.sin(angle) * forward + Math.cos(angle) * side)
                                    / length
                                    * distance,
                    from.y,
                    from.z
                            + (Math.cos(angle) * forward + Math.sin(angle) * side)
                                    / length
                                    * distance);
        }
    }

    private int preferred = 1;
    private Input held = Input.NONE;
    private long holdUntil, backUntil, escapeUntil, lastRecovery = Long.MIN_VALUE;

    public void reset() {
        preferred = 1;
        held = Input.NONE;
        holdUntil = backUntil = escapeUntil = 0;
        lastRecovery = Long.MIN_VALUE;
    }

    public void recover(long tick) {
        if (lastRecovery != Long.MIN_VALUE && tick - lastRecovery < 60) preferred = -preferred;
        lastRecovery = tick;
        backUntil = tick + 3;
        escapeUntil = tick + 10;
        held = Input.NONE;
    }

    public boolean recovering(long tick) {
        return tick < escapeUntil;
    }

    private static Vec3Point checked(
            CollisionGeometry geometry, PitRegion area, Vec3Point from, float yaw, Input input) {
        if (input == Input.NONE) return null;
        // .48 covers a sprint step and diagonal-input prediction; .75 gives a
        // little run-out without demanding a clear corridor all the way to the opponent.
        Vec3Point step = geometry.walkEnd(from, input.point(from, yaw, .48), area);
        Vec3Point runOut = geometry.walkEnd(from, input.point(from, yaw, .75), area);
        if (step == null || runOut == null || !geometry.supported(runOut, .001)) return null;
        if (Math.abs(step.y - from.y) < .1
                && !geometry.free(CollisionGeometry.body(from).union(CollisionGeometry.body(step))))
            return null;
        return step;
    }

    public Input choose(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point from,
            Vec3Point goal,
            float yaw,
            long tick) {
        if (from == null
                || goal == null
                || !Float.isFinite(yaw)
                || !area.containsBody(goal, .3, 1.8)
                || !area.containsBody(from, .3, 1.8)
                || !geometry.free(CollisionGeometry.body(from))
                || !geometry.supported(from, .3)) return Input.NONE;
        if (tick < backUntil) {
            for (Input input :
                    new Input[] {
                        Input.BACK,
                        preferred > 0 ? Input.BACK_LEFT : Input.BACK_RIGHT,
                        preferred > 0 ? Input.LEFT : Input.RIGHT,
                        preferred > 0 ? Input.RIGHT : Input.LEFT
                    }) if (checked(geometry, area, from, yaw, input) != null) return input;
            return Input.NONE;
        }
        if (tick < escapeUntil) {
            for (Input input :
                    new Input[] {
                        preferred > 0 ? Input.LEFT : Input.RIGHT,
                        preferred > 0 ? Input.RIGHT : Input.LEFT,
                        preferred > 0 ? Input.FORWARD_LEFT : Input.FORWARD_RIGHT,
                        Input.BACK,
                        Input.FORWARD
                    }) if (checked(geometry, area, from, yaw, input) != null) return input;
            return Input.NONE;
        }
        if (checked(geometry, area, from, yaw, Input.FORWARD) != null) {
            held = Input.NONE;
            return Input.FORWARD;
        }
        if (tick < holdUntil && checked(geometry, area, from, yaw, held) != null) return held;
        Input chosen = Input.NONE;
        double best = Double.POSITIVE_INFINITY;
        for (Input input :
                new Input[] {
                    Input.FORWARD_LEFT,
                    Input.FORWARD_RIGHT,
                    Input.LEFT,
                    Input.RIGHT,
                    Input.BACK_LEFT,
                    Input.BACK_RIGHT,
                    Input.BACK
                }) {
            Vec3Point end = checked(geometry, area, from, yaw, input);
            if (end == null) continue;
            double score =
                    Math.sqrt(end.horizontalDistanceSq(goal))
                            + Math.abs(end.y - goal.y) * .4
                            + (input.forward < 0 ? .25 : 0)
                            - (input.side == preferred ? .08 : 0);
            if (score < best) {
                chosen = input;
                best = score;
            }
        }
        held = chosen;
        holdUntil = tick + 6;
        if (chosen.side != 0) preferred = chosen.side;
        // If backing out is the only usable direction, finish that small
        // back-out/side-step sequence before trying forward into the corner again.
        if (chosen.forward < 0) recover(tick);
        return chosen;
    }
}