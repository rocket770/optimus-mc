package dev.optimus.core.navigation;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.util.*;

/** Vanilla sprint jump over continuously supported flat ground, never a gap-jump shortcut. */
public final class SprintJump {
    private SprintJump() {}

    public static final class Plan {
        public final Vec3Point start, goal;
        public final double dx, dz, maximumDistance;

        Plan(Vec3Point start, Vec3Point goal, double dx, double dz, double maximumDistance) {
            this.start = start;
            this.goal = goal;
            this.dx = dx;
            this.dz = dz;
            this.maximumDistance = maximumDistance;
        }
    }

    public static List<Vec3Point> samples(
            Vec3Point from,
            double dx,
            double dz,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        if (!Double.isFinite(speed)
                || !Double.isFinite(groundAcceleration)
                || !Double.isFinite(airAcceleration)
                || !Double.isFinite(friction)
                || speed < .05
                || speed > .65
                || groundAcceleration <= 0
                || groundAcceleration > .4
                || airAcceleration <= 0
                || airAcceleration > .08
                || friction < .45
                || friction > .92) return Collections.emptyList();
        double horizontal = speed + .2, vertical = .42, along = 0, y = from.y;
        List<Vec3Point> points = new ArrayList<>();
        points.add(from);
        for (int tick = 0; tick < 24; tick++) {
            horizontal += tick == 0 ? groundAcceleration : airAcceleration;
            along += horizontal;
            y += vertical;
            vertical = (vertical - .08) * .98;
            horizontal *= tick == 0 ? friction : .91;
            points.add(
                    new Vec3Point(from.x + dx * along, Math.max(from.y, y), from.z + dz * along));
            if (y <= from.y && vertical < 0) return points;
        }
        return Collections.emptyList();
    }

    public static Plan plan(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point from,
            Vec3Point goal,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        return plan(
                geometry,
                area,
                from,
                goal,
                speed,
                groundAcceleration,
                airAcceleration,
                friction,
                false);
    }

    public static Plan plan(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point from,
            Vec3Point goal,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction,
            boolean chasing) {
        return plan(
                geometry,
                area,
                from,
                goal,
                speed,
                groundAcceleration,
                airAcceleration,
                friction,
                chasing ? 7 : 6,
                chasing ? 3.25 : 1.5);
    }

    public static Plan guardPlan(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point from,
            Vec3Point goal,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction) {
        return plan(
                geometry,
                area,
                from,
                goal,
                speed,
                groundAcceleration,
                airAcceleration,
                friction,
                4.5,
                .8);
    }

    private static Plan plan(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point from,
            Vec3Point goal,
            double speed,
            double groundAcceleration,
            double airAcceleration,
            double friction,
            double minimum,
            double margin) {
        double distance = Math.sqrt(from.horizontalDistanceSq(goal));
        // motionX/Z are already multiplied by ground friction: ordinary sprint
        // settles around .153, even though displacement is about .281 per tick.
        if (distance < minimum
                || speed < .12
                || speed > .55
                || !area.containsBody(goal, .3, 1.8)
                || !geometry.standable(area, from)) return null;
        double dx = (goal.x - from.x) / distance, dz = (goal.z - from.z) / distance, max = 0;
        List<Vec3Point> nominal =
                samples(from, dx, dz, speed, groundAcceleration, airAcceleration, friction);
        if (nominal.isEmpty()) return null;
        double runningSpeed = speed, runningDistance = 0;
        for (int tick = 1; tick < nominal.size(); tick++) {
            runningSpeed += groundAcceleration;
            runningDistance += runningSpeed;
            runningSpeed *= friction;
        }
        if (Math.sqrt(from.horizontalDistanceSq(nominal.get(nominal.size() - 1)))
                < runningDistance + .20) return null;
        for (double error : new double[] {-5, 0, 5})
            for (double variation : new double[] {-.04, 0, .04}) {
                double radians = Math.toRadians(error),
                        x = dx * Math.cos(radians) - dz * Math.sin(radians),
                        z = dx * Math.sin(radians) + dz * Math.cos(radians);
                List<Vec3Point> points =
                        samples(
                                from,
                                x,
                                z,
                                speed + variation,
                                groundAcceleration,
                                airAcceleration,
                                friction);
                if (points.isEmpty()) return null;
                for (int i = 0; i < points.size(); i++) {
                    Vec3Point p = points.get(i), ground = new Vec3Point(p.x, from.y, p.z);
                    if (!area.containsBody(p, .3, 1.8) || !geometry.standable(area, ground))
                        return null;
                    if (i > 0) {
                        Vec3Point prior = points.get(i - 1),
                                priorGround = new Vec3Point(prior.x, from.y, prior.z);
                        if (!geometry.free(
                                        CollisionGeometry.body(prior)
                                                .union(CollisionGeometry.body(p)))
                                || !geometry.autoStepClear(priorGround, ground, area)) return null;
                    }
                }
                max =
                        Math.max(
                                max,
                                Math.sqrt(
                                        from.horizontalDistanceSq(points.get(points.size() - 1))));
            }
        if (max + margin > distance) return null; // land before close combat or a travel endpoint
        return new Plan(from, goal, dx, dz, max);
    }

    public static boolean continuing(
            CollisionGeometry geometry,
            PitRegion area,
            Plan plan,
            Vec3Point current,
            Vec3Point next) {
        double x = current.x - plan.start.x, z = current.z - plan.start.z;
        double along = x * plan.dx + z * plan.dz, cross = x * plan.dz - z * plan.dx;
        if (along < -.2
                || along > plan.maximumDistance + .6
                || Math.abs(cross) > .45
                || !area.containsBody(current, .3, 1.8)
                || !area.containsBody(next, .3, 1.8)
                || !geometry.free(
                        CollisionGeometry.body(current).union(CollisionGeometry.body(next))))
            return false;
        return geometry.standable(area, new Vec3Point(next.x, plan.start.y, next.z));
    }
}