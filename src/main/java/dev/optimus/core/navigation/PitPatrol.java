package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder.Node;
import java.util.*;

/** Short random walks consisting only of already validated, supported adjacent edges. */
public final class PitPatrol {
    private PitPatrol() {}

    public static List<Node> route(GroundRoute terrain, Vec3Point current, Random random) {
        Node start = terrain.start(current);
        if (start == null) return Collections.emptyList();
        List<Node> path = new ArrayList<>();
        Set<Node> visited = new HashSet<>();
        path.add(start);
        visited.add(start);
        Node from = start;
        int[] heights = {0, 1, -1, 2, -2, -3, -4, -5, -6};
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int heading = -1;
        for (int step = 0; step < 8; step++) {
            List<Integer> order = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
            Collections.shuffle(order, random);
            if (heading >= 0 && random.nextDouble() < .85) {
                order.remove(Integer.valueOf(heading));
                order.add(0, heading);
            }
            Node chosen = null;
            for (int direction : order) {
                int[] d = directions[direction];
                for (int height : heights) {
                    Node next = new Node(from.x + d[0], from.y2 + height, from.z + d[1]);
                    if (visited.contains(next)
                            || !terrain.standable(next)
                            || !terrain.traversable(from, next)) continue;
                    chosen = next;
                    heading = direction;
                    break;
                }
                if (chosen != null) break;
            }
            if (chosen == null) break;
            path.add(chosen);
            visited.add(chosen);
            from = chosen;
        }
        // End at the farthest prefix so a winding walk doesn't just return to its start.
        int farthest = 0;
        double distance = .81;
        for (int i = 1; i < path.size(); i++) {
            double nextDistance = current.horizontalDistanceSq(terrain.point(path.get(i)));
            if (nextDistance > distance) {
                distance = nextDistance;
                farthest = i;
            }
        }
        return farthest == 0
                ? Collections.emptyList()
                : new ArrayList<>(path.subList(0, farthest + 1));
    }
}