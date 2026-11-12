package dev.optimus.core.geometry;

import dev.optimus.core.navigation.FallTrajectory;
import java.util.*;

/** Collision-box geometry shared by the live world adapter and captured-map replay. */
public final class CollisionGeometry {
    private static final double CONTACT_EPSILON = .00001;
    // Native 1.8.9 jump apex with the .42f impulse, .08 gravity, .98f
    // vertical drag and .005 motion deadzone. Rounding to 1.25 admits
    // ledges just above the actual apex; native replays cross-check this bound.
    public static final double MAXIMUM_JUMP_RISE = 1.2491871;

    public static final class Box {
        public final double minX, minY, minZ, maxX, maxY, maxZ;

        public Box(double a, double b, double c, double d, double e, double f) {
            minX = a;
            minY = b;
            minZ = c;
            maxX = d;
            maxY = e;
            maxZ = f;
        }

        public boolean intersects(Box b) {
            return maxX > b.minX
                    && minX < b.maxX
                    && maxY > b.minY
                    && minY < b.maxY
                    && maxZ > b.minZ
                    && minZ < b.maxZ;
        }

        public Box union(Box b) {
            return new Box(
                    Math.min(minX, b.minX),
                    Math.min(minY, b.minY),
                    Math.min(minZ, b.minZ),
                    Math.max(maxX, b.maxX),
                    Math.max(maxY, b.maxY),
                    Math.max(maxZ, b.maxZ));
        }
    }

    public static final class Cell {
        public static final Cell UNLOADED = new Cell(false, false, Collections.<Box>emptyList());
        public static final Cell AIR = new Cell(true, false, Collections.<Box>emptyList());
        public final boolean loaded, hazard;
        public final List<Box> boxes;

        public Cell(boolean loaded, boolean hazard, List<Box> boxes) {
            this.loaded = loaded;
            this.hazard = hazard;
            this.boxes = boxes;
        }
    }

    public interface Source {
        Cell cell(int x, int y, int z);
    }

    private final Source source;

    public CollisionGeometry(Source source) {
        this.source = source;
    }

    public CollisionGeometry cached() {
        final Map<String, Cell> cells = new HashMap<>();
        return new CollisionGeometry(
                (x, y, z) -> {
                    String key = x + ":" + y + ":" + z;
                    Cell cell = cells.get(key);
                    if (cell == null) {
                        cell = source.cell(x, y, z);
                        cells.put(key, cell);
                    }
                    return cell;
                });
    }

    public static Box body(Vec3Point p) {
        return new Box(p.x - .30, p.y + .01, p.z - .30, p.x + .30, p.y + 1.80, p.z + .30);
    }

    private List<Box> hits(Box query) {
        List<Box> result = new ArrayList<>();
        for (int x = (int) Math.floor(query.minX); x <= (int) Math.floor(query.maxX); x++)
            for (int z = (int) Math.floor(query.minZ); z <= (int) Math.floor(query.maxZ); z++)
                for (int y = (int) Math.floor(query.minY) - 1;
                        y <= (int) Math.floor(query.maxY);
                        y++) {
                    Cell cell = source.cell(x, y, z);
                    if (!cell.loaded) return null;
                    // Querying the block below a floor must not treat its non-overlapping hazard as
                    // body contact.
                    if (cell.hazard && query.intersects(new Box(x, y, z, x + 1, y + 1, z + 1)))
                        return null;
                    for (Box shape : cell.boxes) if (shape.intersects(query)) result.add(shape);
                }
        return result;
    }

    public boolean free(Box query) {
        List<Box> hits = hits(query);
        return hits != null && hits.isEmpty();
    }
    /** Ordinary grounded sneak edge clipping, matching Entity.moveEntity's X/Z/combined checks. */
    public Vec3Point sneakStep(Vec3Point from, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 8 || Math.abs(z) > 8)
            return null;
        while (x != 0 && noSneakSupport(from, x, 0)) x = towardZero(x);
        while (z != 0 && noSneakSupport(from, 0, z)) z = towardZero(z);
        while (x != 0 && z != 0 && noSneakSupport(from, x, z)) {
            x = towardZero(x);
            z = towardZero(z);
        }
        return new Vec3Point(from.x + x, from.y, from.z + z);
    }

    private boolean noSneakSupport(Vec3Point from, double x, double z) {
        List<Box> shapes =
                hits(
                        new Box(
                                from.x - .3 + x,
                                from.y - 1,
                                from.z - .3 + z,
                                from.x + .3 + x,
                                from.y + .8,
                                from.z + .3 + z));
        return shapes == null || shapes.isEmpty();
    }

    private static double towardZero(double value) {
        return Math.abs(value) < .05 ? 0 : value - Math.copySign(.05, value);
    }
    /** Native Y/X/Z collision clipping for a jump prediction; never moves an entity. */
    public Vec3Point jumpMove(Vec3Point from, Vec3Point delta, PitRegion area) {
        Box b = new Box(from.x - .3, from.y, from.z - .3, from.x + .3, from.y + 1.8, from.z + .3);
        Box end = shift(b, delta.x, delta.y, delta.z);
        List<Box> shapes = hits(b.union(end));
        if (shapes == null) return null;
        double x = delta.x, y = delta.y, z = delta.z;
        for (Box s : shapes)
            if (b.maxX > s.minX && b.minX < s.maxX && b.maxZ > s.minZ && b.minZ < s.maxZ) {
                if (y > 0 && b.maxY <= s.minY) y = Math.min(y, s.minY - b.maxY);
                else if (y < 0 && b.minY >= s.maxY) y = Math.max(y, s.maxY - b.minY);
            }
        b = shift(b, 0, y, 0);
        for (Box s : shapes)
            if (b.maxY > s.minY && b.minY < s.maxY && b.maxZ > s.minZ && b.minZ < s.maxZ) {
                if (x > 0 && b.maxX <= s.minX) x = Math.min(x, s.minX - b.maxX);
                else if (x < 0 && b.minX >= s.maxX) x = Math.max(x, s.maxX - b.minX);
            }
        b = shift(b, x, 0, 0);
        for (Box s : shapes)
            if (b.maxY > s.minY && b.minY < s.maxY && b.maxX > s.minX && b.minX < s.maxX) {
                if (z > 0 && b.maxZ <= s.minZ) z = Math.min(z, s.minZ - b.maxZ);
                else if (z < 0 && b.minZ >= s.maxZ) z = Math.max(z, s.maxZ - b.minZ);
            }
        Vec3Point next = new Vec3Point(from.x + x, from.y + y, from.z + z);
        return area.containsBody(next, .3, 1.8) && free(body(next)) ? next : null;
    }

    private static Box shift(Box b, double x, double y, double z) {
        return new Box(b.minX + x, b.minY + y, b.minZ + z, b.maxX + x, b.maxY + y, b.maxZ + z);
    }

    public boolean supported(Vec3Point p, double halfWidth) {
        List<Box> hits =
                hits(
                        new Box(
                                p.x - halfWidth,
                                p.y - .06,
                                p.z - halfWidth,
                                p.x + halfWidth,
                                p.y - .001,
                                p.z + halfWidth));
        return hits != null && !hits.isEmpty();
    }

    public boolean standable(PitRegion area, Vec3Point p) {
        return p != null && area.containsBody(p, .3, 1.8) && free(body(p)) && supported(p, .15);
    }

    /** Actual foot contact includes ledge edges; grid-node standability stays centre-supported. */
    public boolean touchingGround(PitRegion area, Vec3Point p) {
        if (p == null || !area.containsBody(p, .3, 1.8) || !free(body(p))) return false;
        List<Box> support =
                hits(
                        new Box(
                                p.x - .3,
                                p.y - CONTACT_EPSILON,
                                p.z - .3,
                                p.x + .3,
                                p.y + CONTACT_EPSILON,
                                p.z + .3));
        if (support == null) return false;
        for (Box shape : support) if (Math.abs(shape.maxY - p.y) < CONTACT_EPSILON) return true;
        return false;
    }
    /** Resolve carpets/snow/stair surfaces rather than forcing feet onto half-block coordinates. */
    public Vec3Point surface(Vec3Point nominal, PitRegion area) {
        List<Box> hits =
                hits(
                        new Box(
                                nominal.x - .15,
                                nominal.y - .26,
                                nominal.z - .15,
                                nominal.x + .15,
                                nominal.y + .26,
                                nominal.z + .15));
        if (hits == null) return null;
        Vec3Point best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Box shape : hits) {
            double gap = Math.abs(shape.maxY - nominal.y);
            if (gap > .251 || gap >= distance) continue;
            Vec3Point candidate = new Vec3Point(nominal.x, shape.maxY, nominal.z);
            if (standable(area, candidate)) {
                best = candidate;
                distance = gap;
            }
        }
        return best;
    }
    /** Vanilla stepping uses the full foot box and can climb each half of a stair separately. */
    private Vec3Point stepSurface(Vec3Point nominal, PitRegion area) {
        List<Box> hits =
                hits(
                        new Box(
                                nominal.x - .3,
                                nominal.y - .61,
                                nominal.z - .3,
                                nominal.x + .3,
                                nominal.y + .61,
                                nominal.z + .3));
        if (hits == null) return null;
        Vec3Point best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Box shape : hits) {
            double gap = Math.abs(shape.maxY - nominal.y);
            if (gap > .6001 || gap >= distance) continue;
            Vec3Point p = new Vec3Point(nominal.x, shape.maxY, nominal.z);
            if (area.containsBody(p, .3, 1.8) && free(body(p)) && supported(p, .3)) {
                best = p;
                distance = gap;
            }
        }
        return best;
    }
    /**
     * Follow actual stair/slab/carpet tops in small sweeps without changing the player's position.
     */
    public Vec3Point walkEnd(Vec3Point from, Vec3Point to, PitRegion area) {
        if (from == null
                || to == null
                || !area.containsBody(from, .3, 1.8)
                || !free(body(from))
                || !supported(from, .3)) return null;
        double distance = Math.sqrt(from.horizontalDistanceSq(to));
        if (distance > 8) return null;
        int steps = Math.max(1, (int) Math.ceil(distance / .10));
        Vec3Point previous = from;
        for (int i = 1; i <= steps; i++) {
            double fraction = (double) i / steps;
            Vec3Point next =
                    stepSurface(
                            new Vec3Point(
                                    from.x + (to.x - from.x) * fraction,
                                    previous.y,
                                    from.z + (to.z - from.z) * fraction),
                            area);
            if (next == null || !walkClear(previous, next)) return null;
            previous = next;
        }
        return previous;
    }

    public boolean autoStepClear(Vec3Point from, Vec3Point to, PitRegion area) {
        Vec3Point end = walkEnd(from, to, area);
        return end != null && Math.abs(end.y - to.y) < .26;
    }
    /** A checked walk off an adjacent lip, without a positive jump impulse. */
    public boolean walkOffClear(Vec3Point from, Vec3Point to, PitRegion area) {
        if (from == null
                || to == null
                || from.y - to.y < .26
                || from.horizontalDistanceSq(to) > 2.01
                || !standable(area, from)
                || !standable(area, to)) return false;
        return !FallTrajectory.samples(
                        from,
                        to,
                        new FallTrajectory.Terrain() {
                            public boolean inside(Vec3Point p) {
                                return area.containsBody(p, .3, 1.8);
                            }

                            public boolean clear(Vec3Point a, Vec3Point b) {
                                return free(body(a).union(body(b)));
                            }

                            public boolean supported(Vec3Point p) {
                                return CollisionGeometry.this.supported(p, .3);
                            }
                        })
                .isEmpty();
    }

    public boolean walkClear(Vec3Point from, Vec3Point to) {
        double rise = to.y - from.y;
        if (rise > MAXIMUM_JUMP_RISE || rise < -3 || from.horizontalDistanceSq(to) > 2.01)
            return false;
        Vec3Point a = new Vec3Point(from.x, Math.max(from.y, to.y), from.z),
                b = new Vec3Point(to.x, Math.max(from.y, to.y), to.z);
        return free(body(from).union(body(a)))
                && free(body(a).union(body(b)))
                && free(body(b).union(body(to)));
    }
    /** Nearby direct pursuit needs continuous supported ground, not just safe endpoints. */
    public boolean pursuitClear(Vec3Point from, Vec3Point target, PitRegion pit) {
        return pursuitClear(from, target, pit, 8);
    }

    public boolean pursuitClear(Vec3Point from, Vec3Point target, PitRegion pit, double range) {
        if (from == null
                || target == null
                || !pit.containsBody(target, .3, 1.8)
                || !Double.isFinite(range)
                || range < 0
                || range > 12
                || from.horizontalDistanceSq(target) > range * range
                || !standable(pit, from)) return false;
        // Short batches retain walkEnd's bounded workload, with the same stair/slab
        // surfaces and swept collision checks as actual walking and route look-ahead.
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(from.horizontalDistanceSq(target)) / 4));
        Vec3Point previous = from;
        for (int i = 1; i <= steps; i++) {
            double fraction = (double) i / steps;
            Vec3Point nominal =
                    new Vec3Point(
                            from.x + (target.x - from.x) * fraction,
                            previous.y,
                            from.z + (target.z - from.z) * fraction);
            Vec3Point next = walkEnd(previous, nominal, pit);
            if (next == null) return false;
            previous = next;
        }
        return target.y >= previous.y - .1 && target.y - previous.y <= 1.3;
    }
    /**
     * Chase the supported floor beneath ordinary knockback, while aim keeps the live entity
     * position.
     */
    public Vec3Point pursuitDestination(Vec3Point target, boolean airborne, PitRegion pit) {
        if (target == null || !pit.containsBody(target, .3, 1.8)) return null;
        if (!airborne) return target;
        Vec3Point floor = landingBelow(target, pit, 3);
        return floor == null || target.y - floor.y > 3 ? target : floor;
    }

    public boolean knockbackControlClear(Vec3Point from, Vec3Point next, PitRegion pit) {
        if (!pit.containsBody(from, .3, 1.8)
                || !pit.containsBody(next, .3, 1.8)
                || from.horizontalDistanceSq(next) > .25
                || !free(body(from).union(body(next)))) return false;
        Vec3Point floor = landingBelow(next, pit);
        return floor != null && next.y >= floor.y;
    }
    /** Highest safe floor below a flying player's actual horizontal position. */
    public Vec3Point landingBelow(Vec3Point current, PitRegion area) {
        return landingBelow(current, area, 64);
    }

    private Vec3Point landingBelow(Vec3Point current, PitRegion area, double maximumDrop) {
        for (double y = Math.floor(current.y * 2) / 2.0;
                y >= Math.max(1, current.y - maximumDrop);
                y -= .5) {
            Vec3Point floor = surface(new Vec3Point(current.x, y, current.z), area);
            if (floor != null
                    && floor.y <= current.y + .1
                    && free(body(current).union(body(floor)))) return floor;
        }
        return null;
    }
}