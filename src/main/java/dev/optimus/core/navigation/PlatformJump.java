package dev.optimus.core.navigation;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.util.*;

/** Short platform jumps using normal forward/back input and native collision clipping. */
public final class PlatformJump {
    private PlatformJump() {}

    public static final class Plan {
        public final Vec3Point start, landing;
        public final double dx, dz;
        public final int forwardTicks;

        Plan(Vec3Point start, Vec3Point landing, double dx, double dz, int forwardTicks) {
            this.start = start;
            this.landing = landing;
            this.dx = dx;
            this.dz = dz;
            this.forwardTicks = forwardTicks;
        }
    }

    public static boolean candidate(Vec3Point from, Vec3Point to) {
        if (from == null || to == null) return false;
        double distance = from.horizontalDistanceSq(to), rise = to.y - from.y;
        return distance >= .2 && distance <= 8.01 && rise <= 1.01 && rise >= -1.01;
    }

    public static List<Vec3Point> samples(
            CollisionGeometry geometry,
            PitRegion area,
            Plan plan,
            double motionX,
            double motionZ,
            double groundAcceleration,
            double airAcceleration,
            double friction,
            double angle) {
        double r = Math.toRadians(angle),
                dx = plan.dx * Math.cos(r) - plan.dz * Math.sin(r),
                dz = plan.dx * Math.sin(r) + plan.dz * Math.cos(r);
        Vec3Point p = plan.start;
        double vy = .42, hx = motionX, hz = motionZ;
        List<Vec3Point> arc = new ArrayList<>();
        arc.add(p);
        for (int tick = 0; tick < 26; tick++) {
            double acceleration =
                    tick == 0
                            ? groundAcceleration
                            : airAcceleration * (tick < plan.forwardTicks ? 1 : -1);
            hx += dx * acceleration;
            hz += dz * acceleration;
            Vec3Point next = geometry.jumpMove(p, new Vec3Point(hx, vy, hz), area);
            if (next == null) return Collections.emptyList();
            // On its grounded launch tick vanilla may auto-step if horizontal
            // movement clips a stair/slab, replacing the modeled jump impulse.
            // Only launch arcs whose first movement is free of that ambiguity.
            if (tick == 0
                    && (Math.abs(next.x - p.x - hx) > 1e-7 || Math.abs(next.z - p.z - hz) > 1e-7))
                return Collections.emptyList();
            boolean grounded = vy < 0 && next.y > p.y + vy + 1e-7;
            boolean ceiling = vy > 0 && next.y < p.y + vy - 1e-7;
            if (Math.abs(next.x - p.x - hx) > 1e-7) hx = 0;
            if (Math.abs(next.z - p.z - hz) > 1e-7) hz = 0;
            if (ceiling) return Collections.emptyList();
            arc.add(next);
            if (grounded) return arc;
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
            Vec3Point start,
            Vec3Point landing,
            double motionX,
            double motionZ,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        if (!candidate(start, landing)) return null;
        double distance = Math.sqrt(start.horizontalDistanceSq(landing));
        if (distance < .01) return null;
        return plan(
                geometry,
                area,
                start,
                landing,
                motionX,
                motionZ,
                groundAcceleration,
                airAcceleration,
                friction,
                (landing.x - start.x) / distance,
                (landing.z - start.z) / distance);
    }

    public static Plan plan(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point start,
            Vec3Point landing,
            double motionX,
            double motionZ,
            double groundAcceleration,
            double airAcceleration,
            double friction,
            double dx,
            double dz) {
        if (!candidate(start, landing)
                || !geometry.standable(area, start)
                || !geometry.standable(area, landing)
                || !Double.isFinite(motionX)
                || !Double.isFinite(motionZ)
                || Math.hypot(motionX, motionZ) > .3
                || !Double.isFinite(groundAcceleration)
                || groundAcceleration <= 0
                || groundAcceleration > .4
                || !Double.isFinite(airAcceleration)
                || airAcceleration <= 0
                || airAcceleration > .08
                || !Double.isFinite(friction)
                || friction < .45
                || friction > .92) return null;
        if (!Double.isFinite(dx) || !Double.isFinite(dz) || Math.abs(Math.hypot(dx, dz) - 1) > .001)
            return null;
        Plan chosen = null;
        double best = Double.POSITIVE_INFINITY;
        for (int forward = 2; forward <= 14; forward++) {
            Plan trial = new Plan(start, landing, dx, dz, forward);
            double error = 0;
            boolean valid = true;
            envelope:
            for (double angle : new double[] {-2, 0, 2})
                for (double variation : new double[] {-.015, 0, .015}) {
                    List<Vec3Point> arc =
                            samples(
                                    geometry,
                                    area,
                                    trial,
                                    motionX + dx * variation,
                                    motionZ + dz * variation,
                                    groundAcceleration,
                                    airAcceleration,
                                    friction,
                                    angle);
                    if (arc.size() < 5) {
                        valid = false;
                        break envelope;
                    }
                    Vec3Point end = arc.get(arc.size() - 1);
                    if (Math.abs(end.y - landing.y) > .06
                            || end.horizontalDistanceSq(landing) > .65 * .65
                            || !geometry.standable(area, end)) {
                        valid = false;
                        break envelope;
                    }
                    Vec3Point settle = new Vec3Point(end.x + dx * .08, end.y, end.z + dz * .08);
                    if (geometry.walkEnd(end, settle, area) == null) {
                        valid = false;
                        break envelope;
                    }
                    error = Math.max(error, end.horizontalDistanceSq(landing));
                }
            if (valid && error < best) {
                best = error;
                chosen = trial;
            }
        }
        return chosen;
    }

    public static boolean possible(
            CollisionGeometry geometry, PitRegion area, Vec3Point from, Vec3Point to) {
        if (!candidate(from, to)
                || !geometry.standable(area, from)
                || !geometry.standable(area, to)) return false;
        Vec3Point highFrom = new Vec3Point(from.x, from.y + 1.25, from.z),
                highTo = new Vec3Point(to.x, from.y + 1.25, to.z);
        if (!geometry.free(CollisionGeometry.body(from).union(CollisionGeometry.body(highFrom)))
                || !geometry.free(
                        CollisionGeometry.body(highFrom).union(CollisionGeometry.body(highTo))))
            return false;
        double distance = Math.sqrt(from.horizontalDistanceSq(to)),
                dx = (to.x - from.x) / distance,
                dz = (to.z - from.z) / distance;
        // Prospective routing needs a feasible arc; launch rechecks the full live
        // speed/heading envelope. Do not run that expensive envelope per A* edge.
        for (double speed : new double[] {.118, 0, .06})
            for (int forward : new int[] {14, 10, 6}) {
                Plan trial = new Plan(from, to, dx, dz, forward);
                List<Vec3Point> arc =
                        samples(
                                geometry,
                                area,
                                trial,
                                dx * speed,
                                dz * speed,
                                .098,
                                .0196,
                                .546,
                                0);
                if (arc.size() < 5) continue;
                Vec3Point end = arc.get(arc.size() - 1);
                if (Math.abs(end.y - to.y) < .06
                        && end.horizontalDistanceSq(to) <= .65 * .65
                        && geometry.standable(area, end)
                        && geometry.walkEnd(
                                        end,
                                        new Vec3Point(end.x + dx * .08, end.y, end.z + dz * .08),
                                        area)
                                != null) return true;
            }
        return false;
    }

    public static Vec3Point projected(
            CollisionGeometry geometry,
            PitRegion area,
            Plan plan,
            Vec3Point current,
            double motionX,
            double motionY,
            double motionZ,
            double airAcceleration,
            boolean brake) {
        double side = (current.x - plan.start.x) * plan.dz - (current.z - plan.start.z) * plan.dx;
        if (Math.abs(side) > .6
                || current.horizontalDistanceSq(plan.landing) > 16
                || !geometry.standable(area, plan.landing)) return null;
        double input = brake ? -airAcceleration : airAcceleration;
        return geometry.jumpMove(
                current,
                new Vec3Point(motionX + plan.dx * input, motionY, motionZ + plan.dz * input),
                area);
    }
}