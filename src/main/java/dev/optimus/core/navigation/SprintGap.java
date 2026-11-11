package dev.optimus.core.navigation;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.util.*;

/**
 * Ordinary sprint-gap physics with checked launch/run-up/landing, including reachable raised
 * platforms.
 */
public final class SprintGap {
    private SprintGap() {}

    public static boolean candidate(Vec3Point from, Vec3Point to) {
        if (from == null || to == null) return false;
        double x = Math.abs(to.x - from.x), z = Math.abs(to.z - from.z), rise = to.y - from.y;
        boolean longGap =
                ((Math.abs(x - 3) < .01 || Math.abs(x - 4) < .01 || Math.abs(x - 5) < .01)
                                && z < .01)
                        || ((Math.abs(z - 3) < .01
                                        || Math.abs(z - 4) < .01
                                        || Math.abs(z - 5) < .01)
                                && x < .01);
        boolean raisedShortGap =
                rise >= .49
                        && ((Math.abs(x - 2) < .01 && z < .01)
                                || (Math.abs(z - 2) < .01 && x < .01));
        boolean diagonal = x > .01 && z > .01 && x <= 4.01 && z <= 4.01 && x * x + z * z >= 4.99;
        return (longGap || raisedShortGap || diagonal) && rise <= 1 && rise >= -1;
    }

    public static final class Candidate {
        public final Vec3Point takeoff, runUp, landing;
        public final int emptyBlocks;
        public final double dx, dz;

        public Candidate(
                Vec3Point takeoff,
                Vec3Point runUp,
                Vec3Point landing,
                int width,
                double dx,
                double dz) {
            this.takeoff = takeoff;
            this.runUp = runUp;
            this.landing = landing;
            emptyBlocks = width;
            this.dx = dx;
            this.dz = dz;
        }
    }

    public static final class Plan {
        public final Candidate candidate;
        public final Vec3Point start;
        public final double maximumDistance;

        Plan(Candidate candidate, Vec3Point start, double maximum) {
            this.candidate = candidate;
            this.start = start;
            maximumDistance = maximum;
        }
    }

    public static boolean launchSupported(
            CollisionGeometry geometry, PitRegion area, Vec3Point point) {
        return point != null
                && area.containsBody(point, .3, 1.8)
                && geometry.free(CollisionGeometry.body(point))
                && geometry.supported(point, .3);
    }

    public static List<Vec3Point> samples(
            Vec3Point start,
            double landingY,
            double dx,
            double dz,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        if (!Double.isFinite(speed)
                || speed < .07
                || speed > .6
                || !Double.isFinite(groundAcceleration)
                || groundAcceleration <= 0
                || groundAcceleration > .4
                || airAcceleration <= 0
                || airAcceleration > .08
                || !Double.isFinite(airAcceleration)
                || !Double.isFinite(friction)
                || friction < .45
                || friction > .92
                || landingY - start.y > 1
                || landingY - start.y < -1) return Collections.emptyList();
        double horizontal = speed + .2, vertical = (double) .42f, along = 0, y = start.y;
        List<Vec3Point> result = new ArrayList<>();
        result.add(start);
        for (int tick = 0; tick < 26; tick++) {
            if (Math.abs(vertical) < .005) vertical = 0;
            horizontal += tick == 0 ? groundAcceleration : airAcceleration;
            along += horizontal;
            y += vertical;
            vertical = (vertical - .08) * .98;
            horizontal *= tick == 0 ? friction : .91;
            boolean landed = vertical < 0 && y <= landingY;
            result.add(
                    new Vec3Point(
                            start.x + dx * along, landed ? landingY : y, start.z + dz * along));
            if (landed) return result;
        }
        return Collections.emptyList();
    }

    private static List<Vec3Point> nativeSamples(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point start,
            double dx,
            double dz,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        Vec3Point p = start;
        double hx = dx * (speed + .2), hz = dz * (speed + .2), vy = (double) .42f;
        List<Vec3Point> arc = new ArrayList<>();
        arc.add(start);
        for (int tick = 0; tick < 26; tick++) {
            if (Math.abs(vy) < .005) vy = 0;
            hx += dx * (tick == 0 ? groundAcceleration : airAcceleration);
            hz += dz * (tick == 0 ? groundAcceleration : airAcceleration);
            Vec3Point next = geometry.jumpMove(p, new Vec3Point(hx, vy, hz), area);
            if (next == null || vy > 0 && next.y < p.y + vy - 1e-7) return Collections.emptyList();
            if (Math.abs(next.x - p.x - hx) > 1e-7 || Math.abs(next.z - p.z - hz) > 1e-7)
                return Collections.emptyList();
            arc.add(next);
            if (vy < 0 && next.y > p.y + vy + 1e-7) return arc;
            vy = (vy - .08) * .98;
            hx *= tick == 0 ? friction : .91;
            hz *= tick == 0 ? friction : .91;
            p = next;
        }
        return Collections.emptyList();
    }

    public static Plan plan(
            CollisionGeometry geometry,
            PitRegion area,
            Candidate candidate,
            Vec3Point start,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        return plan(
                geometry,
                area,
                candidate,
                start,
                speed,
                groundAcceleration,
                airAcceleration,
                friction,
                false);
    }

    public static Plan plan(
            CollisionGeometry geometry,
            PitRegion area,
            Candidate candidate,
            Vec3Point start,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction,
            boolean nativeGrounded) {
        return checkedPlan(
                geometry,
                area,
                candidate,
                start,
                speed,
                groundAcceleration,
                airAcceleration,
                friction,
                nativeGrounded,
                false);
    }
    /**
     * Prove the last grounded edge step using native Y/X/Z clipping before validating its entire
     * jump.
     */
    public static Plan planAfterGroundedStep(
            CollisionGeometry geometry,
            PitRegion area,
            Candidate candidate,
            Vec3Point from,
            Vec3Point start,
            Vec3Point delta,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        if (from == null
                || start == null
                || delta == null
                || !launchSupported(geometry, area, from)
                || !Double.isFinite(delta.x)
                || !Double.isFinite(delta.y)
                || !Double.isFinite(delta.z)
                || delta.y >= 0
                || delta.y < -.5
                || Math.hypot(delta.x, delta.z) > .4) return null;
        Vec3Point clipped = geometry.jumpMove(from, delta, area);
        if (clipped == null
                || Math.abs(clipped.y - from.y) > 1e-5
                || clipped.y <= from.y + delta.y + 1e-7
                || Math.abs(clipped.x - from.x - delta.x) > 1e-5
                || Math.abs(clipped.z - from.z - delta.z) > 1e-5
                || clipped.horizontalDistanceSq(start) > 1e-10
                || Math.abs(clipped.y - start.y) > 1e-5) return null;
        return checkedPlan(
                geometry,
                area,
                candidate,
                start,
                speed,
                groundAcceleration,
                airAcceleration,
                friction,
                true,
                true);
    }

    private static Plan checkedPlan(
            CollisionGeometry geometry,
            PitRegion area,
            Candidate candidate,
            Vec3Point start,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction,
            boolean nativeGrounded,
            boolean checkedStep) {
        if (candidate == null
                || start == null
                || !Double.isFinite(speed)
                || !Double.isFinite(groundAcceleration)
                || groundAcceleration <= 0
                || groundAcceleration > .4
                || !Double.isFinite(airAcceleration)
                || airAcceleration <= 0
                || airAcceleration > .08
                || !Double.isFinite(friction)
                || friction < .45
                || friction > .92
                || candidate.emptyBlocks < 1
                || candidate.emptyBlocks > 4
                || !Double.isFinite(candidate.dx)
                || !Double.isFinite(candidate.dz)
                || Math.abs(Math.hypot(candidate.dx, candidate.dz) - 1) > .001
                || candidate.landing.y - start.y > 1.00001
                || candidate.landing.y - start.y < -1.00001) return null;
        // Vanilla can retain onGround for the last horizontal step off a lip:
        // Y clips against the old foot box before X/Z move. Only that nearby
        // grounded pose may launch without support under its new foot box.
        boolean edgePose =
                nativeGrounded
                        && (checkedStep
                                || launchSupported(geometry, area, candidate.takeoff)
                                        && start.horizontalDistanceSq(candidate.takeoff)
                                                <= .25 * .25)
                        && area.containsBody(start, .3, 1.8)
                        && geometry.free(CollisionGeometry.body(start));
        if (candidate == null
                || speed < .12
                || speed > .55
                || !(launchSupported(geometry, area, start) || edgePose)
                || !geometry.standable(area, candidate.landing)
                || Math.abs(start.y - candidate.takeoff.y) > .26) return null;
        double maximum = 0;
        for (double angle : new double[] {-2, 0, 2})
            for (double variation : new double[] {-.02, 0, .02}) {
                double r = Math.toRadians(angle),
                        dx = candidate.dx * Math.cos(r) - candidate.dz * Math.sin(r),
                        dz = candidate.dx * Math.sin(r) + candidate.dz * Math.cos(r);
                List<Vec3Point> arc =
                        nativeSamples(
                                geometry,
                                area,
                                start,
                                dx,
                                dz,
                                speed + variation,
                                groundAcceleration,
                                airAcceleration,
                                friction);
                if (arc.isEmpty()) return null;
                for (int i = 0; i < arc.size(); i++) {
                    Vec3Point p = arc.get(i);
                    if (!area.containsBody(p, .3, 1.8) || !geometry.free(CollisionGeometry.body(p)))
                        return null;
                    if (i > 0
                            && !geometry.free(
                                    CollisionGeometry.body(arc.get(i - 1))
                                            .union(CollisionGeometry.body(p)))) return null;
                }
                Vec3Point end = arc.get(arc.size() - 1);
                if (!geometry.standable(area, end)
                        || Math.abs(end.y - candidate.landing.y) > .01
                        || end.horizontalDistanceSq(candidate.landing) > 1.1 * 1.1
                        || !geometry.autoStepClear(end, candidate.landing, area)) return null;
                if (!geometry.supported(
                        new Vec3Point(
                                end.x + candidate.dx * .15, end.y, end.z + candidate.dz * .15),
                        .001)) return null;
                maximum = Math.max(maximum, Math.sqrt(start.horizontalDistanceSq(end)));
            }
        return new Plan(candidate, start, maximum);
    }

    public static Candidate find(
            CollisionGeometry geometry, PitRegion area, Vec3Point current, Vec3Point goal) {
        if (current == null
                || goal == null
                || current.horizontalDistanceSq(goal) > 144
                || !area.containsBody(goal, .3, 1.8)) return null;
        Vec3Point base =
                geometry.surface(
                        new Vec3Point(
                                Math.floor(current.x) + .5, current.y, Math.floor(current.z) + .5),
                        area);
        if (base == null) return null;
        Candidate chosen = null;
        double best = Math.sqrt(current.horizontalDistanceSq(goal)) - .25;
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            for (int width : new int[] {1, 2, 3, 4}) {
                double dx = direction[0], dz = direction[1];
                if ((goal.x - base.x) * dx + (goal.z - base.z) * dz < 1.5) continue;
                boolean empty = true;
                for (int i = 1; i <= width; i++)
                    if (geometry.supported(
                            new Vec3Point(base.x + dx * i, base.y, base.z + dz * i), .15)) {
                        empty = false;
                        break;
                    }
                if (!empty) continue;
                for (double height : new double[] {0, .5, 1, -.5, -1}) {
                    if (width == 1 && height < .5)
                        continue; // Existing short level/lower jumps keep their walking controls.
                    Vec3Point landing =
                            geometry.surface(
                                    new Vec3Point(
                                            base.x + dx * (width + 1),
                                            base.y + height,
                                            base.z + dz * (width + 1)),
                                    area);
                    if (landing == null || !heightProgress(base, landing, goal)) continue;
                    double score =
                            Math.sqrt(landing.horizontalDistanceSq(goal))
                                    + Math.abs(landing.y - goal.y) * .4;
                    if (score >= best) continue;
                    double initialOffset =
                            width == 1 && height == .5
                                    ? -.85
                                    : height > .5
                                            ? (width == 1 ? -.45 : .2)
                                            : (width == 2 ? -.45 : .2);
                    // A wider raised gap can need a later supported takeoff. The old
                    // single .2 anchor rejected the real half-slab roof near -13,-109
                    // even though a normal sprint arc from .4 passes the full checks.
                    double[] offsets =
                            width >= 2 && (height > 0 || width == 4)
                                    ? new double[] {initialOffset, .3, .4, .5, .6, .65, .7, .75}
                                    : new double[] {initialOffset};
                    for (double offset : offsets) {
                        Vec3Point takeoff =
                                new Vec3Point(base.x + dx * offset, base.y, base.z + dz * offset);
                        if (!geometry.autoStepClear(current, takeoff, area)) continue;
                        Vec3Point runUp = null;
                        for (double room :
                                width == 4
                                        ? new double[] {1.9, 1.5, 1.2, .9, .6, .3, 0}
                                        : new double[] {.9, .6, .3, 0}) {
                            Vec3Point p =
                                    new Vec3Point(
                                            takeoff.x - dx * room,
                                            takeoff.y,
                                            takeoff.z - dz * room);
                            if (geometry.autoStepClear(current, p, area)
                                    && geometry.supported(p, .001)) {
                                runUp = p;
                                break;
                            }
                        }
                        if (runUp == null) continue;
                        Candidate candidate = new Candidate(takeoff, runUp, landing, width, dx, dz);
                        double launchSpeed = width == 1 ? .12 : .153;
                        if (plan(geometry, area, candidate, takeoff, launchSpeed, .1274, .026, .546)
                                != null) {
                            chosen = candidate;
                            best = score;
                            break;
                        }
                    }
                }
            }
        for (int x = -4; x <= 4; x++)
            for (int z = -4; z <= 4; z++) {
                if (x == 0 || z == 0 || x * x + z * z < 5) continue;
                if ((goal.x - base.x) * x + (goal.z - base.z) * z <= 0) continue;
                for (double height : new double[] {0, .5, 1, -.5, -1}) {
                    Vec3Point landing =
                            geometry.surface(
                                    new Vec3Point(base.x + x, base.y + height, base.z + z), area);
                    if (landing == null || !heightProgress(base, landing, goal)) continue;
                    double score =
                            Math.sqrt(landing.horizontalDistanceSq(goal))
                                    + Math.abs(landing.y - goal.y) * .4;
                    if (score >= best || geometry.autoStepClear(base, landing, area)) continue;
                    Candidate candidate = diagonalLanding(geometry, area, current, landing);
                    if (candidate != null) {
                        chosen = candidate;
                        best = score;
                    }
                }
            }
        return chosen;
    }

    private static boolean heightProgress(Vec3Point from, Vec3Point landing, Vec3Point goal) {
        // This is a local shortcut, not a complete route. Horizontal progress
        // cannot justify dropping below both the start and an uphill opponent.
        // A* can still choose a deliberate descent as part of a checked route.
        return landing.y >= Math.min(from.y, goal.y) - .26;
    }

    public static Candidate diagonalLanding(
            CollisionGeometry geometry, PitRegion area, Vec3Point current, Vec3Point landing) {
        Vec3Point base =
                geometry.surface(
                        new Vec3Point(
                                Math.floor(current.x) + .5, current.y, Math.floor(current.z) + .5),
                        area);
        if (base == null
                || !candidate(base, landing)
                || Math.abs(base.x - landing.x) < .01
                || Math.abs(base.z - landing.z) < .01) return null;
        double distance = Math.sqrt(base.horizontalDistanceSq(landing)),
                dx = (landing.x - base.x) / distance,
                dz = (landing.z - base.z) / distance;
        boolean gap = false;
        for (double along = .75; along < distance - .5; along += .5)
            if (!geometry.supported(
                    new Vec3Point(base.x + dx * along, base.y, base.z + dz * along), .15)) {
                gap = true;
                break;
            }
        if (!gap) return null;
        Vec3Point high = new Vec3Point(base.x, base.y + 1.25, base.z),
                across = new Vec3Point(landing.x, base.y + 1.25, landing.z);
        if (!geometry.free(CollisionGeometry.body(base).union(CollisionGeometry.body(high)))
                || !geometry.free(
                        CollisionGeometry.body(high).union(CollisionGeometry.body(across))))
            return null;
        for (double offset : new double[] {-.85, -.65, -.45, -.25, 0, .2, .4, .6, .75, .9}) {
            Vec3Point takeoff = new Vec3Point(base.x + dx * offset, base.y, base.z + dz * offset);
            if (!geometry.autoStepClear(current, takeoff, area)) continue;
            for (double room : new double[] {.9, .6, .3, 0}) {
                Vec3Point runUp =
                        new Vec3Point(takeoff.x - dx * room, takeoff.y, takeoff.z - dz * room);
                if (!geometry.autoStepClear(current, runUp, area)
                        || !geometry.supported(runUp, .001)) continue;
                Candidate result =
                        new Candidate(
                                takeoff,
                                runUp,
                                landing,
                                (int)
                                                Math.round(
                                                        Math.max(
                                                                Math.abs(landing.x - base.x),
                                                                Math.abs(landing.z - base.z)))
                                        - 1,
                                dx,
                                dz);
                if (plan(geometry, area, result, takeoff, .153, .1274, .026, .546) != null)
                    return result;
            }
        }
        return null;
    }

    public static boolean continuing(
            CollisionGeometry geometry,
            PitRegion area,
            Plan plan,
            Vec3Point current,
            Vec3Point next) {
        double x = current.x - plan.start.x, z = current.z - plan.start.z;
        double along = x * plan.candidate.dx + z * plan.candidate.dz,
                cross = x * plan.candidate.dz - z * plan.candidate.dx;
        if (along < -.2
                || along > plan.maximumDistance + .6
                || Math.abs(cross) > .35
                || !geometry.standable(area, plan.candidate.landing)) return false;
        if (next.y < plan.candidate.landing.y && current.y >= plan.candidate.landing.y) {
            next = new Vec3Point(next.x, plan.candidate.landing.y, next.z);
            if (!geometry.standable(area, next)) return false;
        }
        return area.containsBody(current, .3, 1.8)
                && area.containsBody(next, .3, 1.8)
                && geometry.free(
                        CollisionGeometry.body(current).union(CollisionGeometry.body(next)));
    }
}