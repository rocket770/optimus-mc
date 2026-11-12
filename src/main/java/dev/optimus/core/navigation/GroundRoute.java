package dev.optimus.core.navigation;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder.Node;
import java.util.*;

/** Shared live/replay terrain rules. Grid nodes resolve to actual collision-box tops. */
public final class GroundRoute implements GridPathfinder.Terrain {
    private final CollisionGeometry geometry;
    private final PitRegion area, pit;
    private final Vec3Point rim;
    private final double floor;
    private final boolean jumpAllowed;
    private final java.util.function.BiPredicate<Vec3Point, Vec3Point> permitted;
    private final Map<Node, Vec3Point> surfaces = new HashMap<>();
    private final Map<String, Boolean> checkedEdges = new HashMap<>();

    public GroundRoute(
            CollisionGeometry geometry,
            PitRegion area,
            PitRegion pit,
            Vec3Point rim,
            double floor,
            boolean jumpAllowed) {
        this(geometry, area, pit, rim, floor, jumpAllowed, (a, b) -> true);
    }

    public GroundRoute(
            CollisionGeometry geometry,
            PitRegion area,
            PitRegion pit,
            Vec3Point rim,
            double floor,
            boolean jumpAllowed,
            java.util.function.BiPredicate<Vec3Point, Vec3Point> permitted) {
        this.geometry = geometry;
        this.area = area;
        this.pit = pit;
        this.rim = rim;
        this.floor = floor;
        this.jumpAllowed = jumpAllowed;
        this.permitted = permitted;
    }

    public Vec3Point point(Node node) {
        if (!surfaces.containsKey(node)) surfaces.put(node, geometry.surface(node.point(), area));
        return surfaces.get(node);
    }

    public boolean standable(Node node) {
        return point(node) != null;
    }

    public Vec3Point entry(Vec3Point hint) {
        if (rim == null) return hint;
        Vec3Point best = null;
        double score = Double.POSITIVE_INFINITY;
        int rimX = (int) Math.floor(rim.x), rimZ = (int) Math.floor(rim.z);
        for (int x = rimX - 1; x <= rimX + 1; x++)
            for (int z = rimZ - 3; z < rimZ; z++)
                for (int y2 = (int) Math.ceil(floor * 2); y2 <= (int) (rim.y * 2) - 7; y2++) {
                    Vec3Point to = point(new Node(x, y2, z)),
                            from = point(new Node(x, (int) Math.round(rim.y * 2), rimZ));
                    if (from == null || to == null || !edgeClear(from, to)) continue;
                    double cost = to.horizontalDistanceSq(hint) + Math.abs(to.y - floor) * .5;
                    if (cost < score) {
                        best = to;
                        score = cost;
                    }
                }
        return best;
    }

    public boolean traversable(Node a, Node b) {
        return edgeClear(point(a), point(b));
    }

    public boolean drop(Vec3Point from, Vec3Point to) {
        return from != null
                && to != null
                && (from.y - to.y <= 3
                        || pit.containsBody(from, .3, 1.8) && pit.containsBody(to, .3, 1.8))
                && geometry.walkOffClear(from, to, area);
    }

    public boolean gap(Vec3Point from, Vec3Point to) {
        if (drop(from, to)) return false;
        return (JumpArc.candidate(from, to)
                        || SprintGap.candidate(from, to)
                        || PlatformJump.candidate(from, to)
                                && (from.horizontalDistanceSq(to) > 2.01
                                        || Math.abs(to.y - from.y) <= .6))
                && !geometry.autoStepClear(from, to, area);
    }

    public boolean edgeClear(Vec3Point from, Vec3Point to) {
        if (from == null || to == null || !permitted.test(from, to)) return false;
        String key = from.serialize() + ">" + to.serialize();
        Boolean known = checkedEdges.get(key);
        if (known != null) return known;
        boolean clear = checkEdge(from, to);
        checkedEdges.put(key, clear);
        return clear;
    }

    private boolean checkEdge(Vec3Point from, Vec3Point to) {
        if (from == null
                || to == null
                || !area.containsBody(from, .3, 1.8)
                || !area.containsBody(to, .3, 1.8)) return false;
        // A skipped waypoint two cells away is still a walk when the whole
        // intervening corridor has floor; distance alone cannot make it a gap jump.
        if (geometry.autoStepClear(from, to, area)) return true;
        if (drop(from, to)) return true;
        if (to.y - from.y > .6 && from.horizontalDistanceSq(to) <= 2.01) {
            if (!jumpAllowed) return false;
            Vec3Point a = new Vec3Point(from.x, from.y + 1.3, from.z),
                    b = new Vec3Point(to.x, from.y + 1.3, to.z);
            return area.containsBody(a, .3, 1.8)
                    && area.containsBody(b, .3, 1.8)
                    && geometry.free(CollisionGeometry.body(a).union(CollisionGeometry.body(b)))
                    && geometry.walkClear(from, to);
        }
        if (PlatformJump.candidate(from, to)
                && jumpAllowed
                && PlatformJump.possible(geometry, area, from, to)) return true;
        if (gap(from, to) && !JumpArc.candidate(from, to) && !SprintGap.candidate(from, to))
            return false;
        if (SprintGap.candidate(from, to)) {
            if (!jumpAllowed) return false;
            boolean diagonal = Math.abs(to.x - from.x) > .01 && Math.abs(to.z - from.z) > .01;
            SprintGap.Candidate candidate =
                    diagonal
                            ? SprintGap.diagonalLanding(geometry, area, from, to)
                            : SprintGap.find(geometry, area, from, to);
            return candidate != null
                    && candidate.landing.horizontalDistanceSq(to) < .01
                    && Math.abs(candidate.landing.y - to.y) < .26;
        }
        if (JumpArc.candidate(from, to)) {
            if (!jumpAllowed
                    || geometry.supported(
                            new Vec3Point((from.x + to.x) / 2, from.y, (from.z + to.z) / 2), .15))
                return false;
            for (double speed : new double[] {.14, .20, .28}) {
                List<Vec3Point> samples = JumpArc.samples(from, to, speed);
                if (samples.isEmpty()) return false;
                for (int i = 0; i < samples.size(); i++) {
                    if (!area.containsBody(samples.get(i), .3, 1.8)) return false;
                    if (i > 0
                            && !geometry.free(
                                    CollisionGeometry.body(samples.get(i - 1))
                                            .union(CollisionGeometry.body(samples.get(i)))))
                        return false;
                }
            }
            return true;
        }
        if (from.y - to.y > 3) {
            boolean insidePit = pit.containsBody(from, .3, 1.8) && pit.containsBody(to, .3, 1.8);
            if ((!insidePit && !DropGate.allows(rim, floor, from, to, pit))
                    || !geometry.standable(area, to)) return false;
            return !FallTrajectory.samples(
                            from,
                            to,
                            new FallTrajectory.Terrain() {
                                public boolean inside(Vec3Point p) {
                                    return area.containsBody(p, .3, 1.8);
                                }

                                public boolean clear(Vec3Point a, Vec3Point b) {
                                    return geometry.free(
                                            CollisionGeometry.body(a)
                                                    .union(CollisionGeometry.body(b)));
                                }

                                public boolean supported(Vec3Point p) {
                                    return geometry.supported(p, .3);
                                }
                            })
                    .isEmpty();
        }
        if (to.y - from.y > .6) {
            if (!jumpAllowed) return false;
            Vec3Point a = new Vec3Point(from.x, from.y + 1.3, from.z),
                    b = new Vec3Point(to.x, from.y + 1.3, to.z);
            if (!area.containsBody(a, .3, 1.8)
                    || !area.containsBody(b, .3, 1.8)
                    || !geometry.free(CollisionGeometry.body(a).union(CollisionGeometry.body(b))))
                return false;
        }
        return geometry.walkClear(from, to);
    }

    public Iterable<Node> extraNeighbors(Node from) {
        List<Node> extra = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Vec3Point p = point(from);
        if (p != null)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 || dz == 0) continue;
                    if ((Math.abs(dx) > 1 || Math.abs(dz) > 1) && !floorGap(p, dx, dz)) continue;
                    for (int height : new int[] {0, 1, 2, -1, -2}) {
                        Node n = new Node(from.x + dx, from.y2 + height, from.z + dz);
                        Vec3Point landing = point(n);
                        if (landing != null
                                && (Math.abs(dx) == 1 && Math.abs(dz) == 1 || jumpAllowed))
                            extra.add(n);
                    }
                }
        if (p != null && jumpAllowed)
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 || dz == 0 || Math.max(Math.abs(dx), Math.abs(dz)) < 3) continue;
                    if (!floorGap(p, dx, dz)) continue;
                    for (int height : new int[] {0, 1, 2, -1, -2}) {
                        Node n = new Node(from.x + dx, from.y2 + height, from.z + dz);
                        if (point(n) != null) extra.add(n);
                    }
                }
        if (jumpAllowed && p != null)
            for (int[] d : dirs)
                for (int height = -2; height <= 1; height++) {
                    Node n = new Node(from.x + d[0] * 2, from.y2 + height, from.z + d[1] * 2);
                    Vec3Point landing = point(n);
                    if (landing != null
                            && !geometry.supported(
                                    new Vec3Point(
                                            (p.x + landing.x) / 2, p.y, (p.z + landing.z) / 2),
                                    .15)
                            && gap(p, landing)) extra.add(n);
                }
        if (jumpAllowed && p != null)
            for (int[] d : dirs)
                for (int distance : new int[] {3, 4, 5}) {
                    boolean empty = true;
                    for (int i = 1; i < distance; i++)
                        if (geometry.supported(
                                new Vec3Point(p.x + d[0] * i, p.y, p.z + d[1] * i), .15)) {
                            empty = false;
                            break;
                        }
                    if (empty)
                        for (int height : new int[] {0, 1, 2, -1, -2})
                            extra.add(
                                    new Node(
                                            from.x + d[0] * distance,
                                            from.y2 + height,
                                            from.z + d[1] * distance));
                }
        if (jumpAllowed && p != null)
            for (int[] d : dirs)
                if (!geometry.supported(new Vec3Point(p.x + d[0], p.y, p.z + d[1]), .15))
                    extra.add(new Node(from.x + d[0] * 2, from.y2 + 2, from.z + d[1] * 2));
        if (p != null && pit.containsBody(p, .3, 1.8)) {
            // Pit falls have no damage limit; every actual descent still needs clear geometry and
            // support.
            int bottom = (int) Math.ceil(Math.min(pit.cornerA().y, pit.cornerB().y) * 2);
            for (int[] d : dirs)
                for (int y2 = from.y2 - 7; y2 >= bottom; y2--)
                    extra.add(new Node(from.x + d[0], y2, from.z + d[1]));
        }
        if (rim != null
                && p != null
                && Math.abs(p.x - rim.x) <= 1.5
                && Math.abs(p.z - rim.z) <= 1.5
                && Math.abs(p.y - rim.y) <= .5)
            for (int[] d : dirs)
                for (int distance = 1; distance <= 3; distance++)
                    for (int y2 = from.y2 - 7;
                            y2 >= Math.max(from.y2 - 32, (int) Math.ceil(floor * 2));
                            y2--)
                        extra.add(new Node(from.x + d[0] * distance, y2, from.z + d[1] * distance));
        return extra;
    }

    private boolean floorGap(Vec3Point from, int dx, int dz) {
        double distance = Math.hypot(dx, dz);
        for (double along = .75; along < distance - .5; along += .5)
            if (!geometry.supported(
                    new Vec3Point(
                            from.x + dx / distance * along, from.y, from.z + dz / distance * along),
                    .15)) return true;
        return false;
    }

    public Node start(Vec3Point current) {
        Node center =
                new Node(
                        (int) Math.floor(current.x),
                        (int) Math.round(current.y * 2),
                        (int) Math.floor(current.z));
        Node best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int height : new int[] {0, 1, -1, 2, -2})
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    Node n = new Node(center.x + dx, center.y2 + height, center.z + dz);
                    Vec3Point p = point(n);
                    double cost =
                            p == null
                                    ? Double.POSITIVE_INFINITY
                                    : current.horizontalDistanceSq(p)
                                            + Math.abs(p.y - current.y) * .1;
                    if (p != null && cost < distance && geometry.autoStepClear(current, p, area)) {
                        best = n;
                        distance = cost;
                    }
                }
        return best;
    }
}