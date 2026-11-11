package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder.Node;
import java.util.*;

/** A complete checked short route, used for corner recovery while the main search continues. */
public final class LocalApproach {
    private LocalApproach() {}

    private static double score(Vec3Point p, Vec3Point goal) {
        return Math.sqrt(p.horizontalDistanceSq(goal)) + Math.abs(p.y - goal.y) * .8;
    }

    private static List<Node> neighbors(GroundRoute terrain, Node from) {
        List<Node> result = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int height : new int[] {0, 1, 2, -1, -2}) {
                    Node n = new Node(from.x + dx, from.y2 + height, from.z + dz);
                    if (terrain.standable(n) && terrain.traversable(from, n)) result.add(n);
                }
            }
        for (Node n : terrain.extraNeighbors(from))
            if (!result.contains(n)
                    && Math.abs(n.y2 - from.y2) <= 2
                    && terrain.standable(n)
                    && terrain.traversable(from, n)) result.add(n);
        return result;
    }

    public static List<Node> route(GroundRoute terrain, Vec3Point current, Vec3Point goal) {
        if (current == null || goal == null || current.horizontalDistanceSq(goal) > 144)
            return Collections.emptyList();
        Node start = terrain.start(current);
        if (start == null) return Collections.emptyList();
        double original = score(current, goal), best = original - .15;
        List<Node> result = Collections.emptyList();
        for (Node first : neighbors(terrain, start)) {
            Vec3Point firstPoint = terrain.point(first);
            // This is provisional progress while the full route searches. A
            // two-hop score must not commit a jump away from the opponent just
            // because a later drop gets closer in X/Z. Necessary jump detours
            // belong to the complete route, which can see the return climb.
            if (terrain.gap(terrain.point(start), firstPoint)
                    && score(firstPoint, goal) >= original) continue;
            double firstScore = score(firstPoint, goal) + .08;
            if (firstScore < best) {
                best = firstScore;
                result = Arrays.asList(start, first);
            }
            for (Node second : neighbors(terrain, first)) {
                if (second.equals(start)) continue;
                double secondScore = score(terrain.point(second), goal) + .16;
                if (secondScore < best) {
                    best = secondScore;
                    result = Arrays.asList(start, first, second);
                }
            }
        }
        return result;
    }
}