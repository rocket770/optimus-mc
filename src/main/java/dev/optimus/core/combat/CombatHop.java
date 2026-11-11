package dev.optimus.core.combat;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.util.*;
import java.util.function.BiPredicate;

/** One checked, stationary jump to reach an elevated opponent, then ordinary routing. */
public final class CombatHop {
    public enum Action {
        NONE,
        PREPARING,
        JUMP,
        AIRBORNE
    }

    private final Random random = new Random();
    private Object target, lastTarget;
    private Vec3Point origin, lastOrigin, lastGoal;
    private long readyAt, started, launched = Long.MIN_VALUE, retryAt;
    private boolean leftGround;

    public void seed(long seed) {
        random.setSeed(seed);
        reset();
    }

    public void reset() {
        cancel();
        lastTarget = null;
        lastOrigin = lastGoal = null;
        retryAt = 0;
    }

    public void cancel() {
        target = null;
        origin = null;
        launched = Long.MIN_VALUE;
        leftGround = false;
    }

    public boolean active() {
        return target != null;
    }

    public boolean launched() {
        return launched != Long.MIN_VALUE;
    }

    public Vec3Point origin() {
        return origin;
    }

    public boolean mayTry(long tick, Object opponent, Vec3Point self, Vec3Point goal) {
        return active()
                || tick >= retryAt
                        && (lastTarget != opponent
                                || lastOrigin == null
                                || self.horizontalDistanceSq(lastOrigin) > 1.5 * 1.5
                                || Math.abs(self.y - lastOrigin.y) > .6
                                || goal.horizontalDistanceSq(lastGoal) > 1.5 * 1.5
                                || Math.abs(goal.y - lastGoal.y) > .6);
    }

    public Action update(
            long tick,
            Object opponent,
            Vec3Point self,
            Vec3Point goal,
            boolean grounded,
            boolean possible,
            boolean aligned) {
        if (target != null && target != opponent) cancel();
        if (launched()) {
            if (tick - launched > 26 || grounded && (leftGround || tick - launched > 3)) {
                cancel();
                return Action.NONE;
            }
            if (!grounded) leftGround = true;
            return Action.AIRBORNE; // Release jump after its single launch tick.
        }
        if (!grounded || !possible || opponent == null || !mayTry(tick, opponent, self, goal)) {
            cancel();
            return Action.NONE;
        }
        if (target == null) {
            target = opponent;
            origin = self;
            started = tick;
            readyAt = tick + 1 + random.nextInt(3);
        }
        if (tick - started > 12) {
            remember(tick, opponent, self, goal);
            cancel();
            return Action.NONE;
        }
        if (tick < readyAt || !aligned) return Action.PREPARING;
        remember(tick, opponent, self, goal);
        launched = tick;
        return Action.JUMP;
    }

    private void remember(long tick, Object opponent, Vec3Point self, Vec3Point goal) {
        lastTarget = opponent;
        lastOrigin = self;
        lastGoal = goal;
        retryAt = tick + 32 + random.nextInt(17);
    }

    public static boolean possible(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point start,
            double eyeHeight,
            CollisionGeometry.Box box,
            double motionX,
            double motionZ,
            double friction,
            BiPredicate<Vec3Point, Vec3Point> clearRay) {
        if (box.minY - start.y < .6
                || !Double.isFinite(eyeHeight)
                || eyeHeight <= 0
                || !geometry.standable(area, start)
                || !Double.isFinite(motionX)
                || !Double.isFinite(motionZ)
                || Math.hypot(motionX, motionZ) > .06
                || !Double.isFinite(friction)
                || friction < .45
                || friction > .92) return false;
        Vec3Point eye = new Vec3Point(start.x, start.y + eyeHeight, start.z);
        double distance = CombatAim.distanceSquared(eye, box);
        if (distance > Math.pow(CombatClicks.ATTACK_REACH + CollisionGeometry.MAXIMUM_JUMP_RISE, 2)
                || CombatAim.clearPoint(
                                eye, box, CombatClicks.ATTACK_REACH, hit -> clearRay.test(eye, hit))
                        != null) return false;
        List<Vec3Point> nominal = null;
        for (double[] variation :
                new double[][] {{0, 0}, {-.015, 0}, {.015, 0}, {0, -.015}, {0, .015}}) {
            List<Vec3Point> arc =
                    arc(
                            geometry,
                            area,
                            start,
                            motionX + variation[0],
                            (double) .42f,
                            motionZ + variation[1],
                            friction);
            if (!landingSafe(geometry, area, start, arc)) return false;
            if (nominal == null) nominal = arc;
        }
        int consecutive = 0;
        for (Vec3Point p : nominal) {
            Vec3Point raisedEye = new Vec3Point(p.x, p.y + eyeHeight, p.z);
            boolean reachable =
                    CombatAim.clearPoint(
                                    raisedEye,
                                    box,
                                    CombatClicks.ATTACK_REACH,
                                    hit -> clearRay.test(raisedEye, hit))
                            != null;
            consecutive = reachable ? consecutive + 1 : 0;
            if (consecutive >= 2) return true;
        }
        return false;
    }

    public static boolean safeFlight(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point origin,
            Vec3Point current,
            double motionX,
            double motionY,
            double motionZ) {
        return origin != null
                && landingSafe(
                        geometry,
                        area,
                        origin,
                        arc(geometry, area, current, motionX, motionY, motionZ, .91));
    }

    private static boolean landingSafe(
            CollisionGeometry geometry, PitRegion area, Vec3Point origin, List<Vec3Point> arc) {
        if (arc.isEmpty()) return false;
        Vec3Point end = arc.get(arc.size() - 1);
        return Math.abs(end.y - origin.y) < .06
                && end.horizontalDistanceSq(origin) < .55 * .55
                && geometry.standable(area, end)
                && geometry.autoStepClear(origin, end, area);
    }

    private static List<Vec3Point> arc(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point start,
            double hx,
            double vy,
            double hz,
            double friction) {
        if (!Double.isFinite(hx) || !Double.isFinite(vy) || !Double.isFinite(hz))
            return Collections.emptyList();
        Vec3Point p = start;
        List<Vec3Point> points = new ArrayList<>();
        points.add(start);
        for (int tick = 0; tick < 26; tick++) {
            if (Math.abs(hx) < .005) hx = 0;
            if (Math.abs(hz) < .005) hz = 0;
            if (Math.abs(vy) < .005) vy = 0;
            Vec3Point next = geometry.jumpMove(p, new Vec3Point(hx, vy, hz), area);
            if (next == null
                    || vy > 0 && next.y < p.y + vy - 1e-7
                    || Math.abs(next.x - p.x - hx) > 1e-7
                    || Math.abs(next.z - p.z - hz) > 1e-7
                    || !geometry.free(
                            CollisionGeometry.body(p).union(CollisionGeometry.body(next))))
                return Collections.emptyList();
            points.add(next);
            if (vy < 0 && next.y > p.y + vy + 1e-7) return points;
            vy = (vy - .08) * .9800000190734863;
            hx *= tick == 0 ? friction : .91;
            hz *= tick == 0 ? friction : .91;
            p = next;
        }
        return Collections.emptyList();
    }
}