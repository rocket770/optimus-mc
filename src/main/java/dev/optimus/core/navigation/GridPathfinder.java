package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;
import java.util.*;

/** Bounded A*: cardinal walking, half-block steps, one-block jumps and drops up to three blocks. */
public final class GridPathfinder {
    public static final class Node {
        public final int x, y2, z;

        public Node(int x, int y2, int z) {
            this.x = x;
            this.y2 = y2;
            this.z = z;
        }

        public Vec3Point point() {
            return new Vec3Point(x + 0.5, y2 / 2.0, z + 0.5);
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Node)) return false;
            Node other = (Node) value;
            return x == other.x && y2 == other.y2 && z == other.z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y2) * 31 + z;
        }
    }

    public interface Terrain {
        boolean standable(Node node);

        boolean traversable(Node from, Node to);

        default Iterable<Node> extraNeighbors(Node from) {
            return Collections.emptyList();
        }

        default double cost(Node from, Node to) {
            return Math.hypot(to.x - from.x, to.z - from.z) + Math.abs(to.y2 - from.y2) * .1;
        }

        default List<Node> segment(Node from, Node to) {
            return Arrays.asList(from, to);
        }
    }

    public interface Goal {
        boolean reached(Node node);

        double estimate(Node node);
    }

    private static final class Entry {
        final Node node;
        final double cost, score, estimate;

        Entry(Node node, double cost, double estimate) {
            this.node = node;
            this.cost = cost;
            this.estimate = estimate;
            this.score = cost + estimate;
        }
    }

    public enum State {
        SEARCHING,
        FOUND,
        NO_PATH,
        NODE_LIMIT
    }
    /**
     * Retains the frontier across bounded client-tick slices; timeout means continue, not no path.
     */
    public static final class Search {
        private final Terrain terrain;
        private final Goal goal;
        private final int budget;
        private final PriorityQueue<Entry> open =
                new PriorityQueue<>(
                        new Comparator<Entry>() {
                            public int compare(Entry a, Entry b) {
                                int score = Double.compare(a.score, b.score);
                                return score != 0 ? score : Double.compare(a.estimate, b.estimate);
                            }
                        });
        private final Map<Node, Double> costs = new HashMap<>();
        private final Map<Node, Node> parents = new HashMap<>();
        private int visited;
        private State state = State.SEARCHING;
        private List<Node> path = Collections.emptyList();

        public Search(Node start, Terrain terrain, Goal goal, int budget) {
            this.terrain = terrain;
            this.goal = goal;
            this.budget = budget;
            if (budget <= 0) {
                state = State.NODE_LIMIT;
                return;
            }
            if (start == null || !terrain.standable(start)) {
                state = State.NO_PATH;
                return;
            }
            costs.put(start, 0.0);
            open.add(new Entry(start, 0, goal.estimate(start)));
        }

        public State state() {
            return state;
        }

        public int visited() {
            return visited;
        }

        public List<Node> path() {
            return path;
        }

        public State advance(int maximumExpansions, long maximumNanos) {
            if (state != State.SEARCHING || maximumExpansions <= 0) return state;
            long started = System.nanoTime();
            int slice = 0;
            int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            int[] heights = {0, 1, 2, -1, -2, -3, -4, -5, -6};
            while (!open.isEmpty() && visited < budget && slice < maximumExpansions) {
                // Guarantee at least one expansion even with a tiny slice; an expensive frame never
                // erases progress.
                if (slice > 0 && maximumNanos > 0 && System.nanoTime() - started >= maximumNanos)
                    return state;
                Entry current = open.remove();
                if (current.cost > costs.get(current.node)) continue;
                visited++;
                slice++;
                if (goal.reached(current.node)) {
                    LinkedList<Node> result = new LinkedList<>();
                    for (Node n = current.node; n != null; n = parents.get(n)) result.addFirst(n);
                    List<Node> expanded = new ArrayList<>();
                    expanded.add(result.getFirst());
                    for (int i = 1; i < result.size(); i++) {
                        List<Node> segment = terrain.segment(result.get(i - 1), result.get(i));
                        expanded.addAll(segment.subList(1, segment.size()));
                    }
                    path = Collections.unmodifiableList(expanded);
                    state = State.FOUND;
                    return state;
                }
                List<Node> neighbors = new ArrayList<>();
                for (int[] d : directions)
                    for (int height : heights)
                        neighbors.add(
                                new Node(
                                        current.node.x + d[0],
                                        current.node.y2 + height,
                                        current.node.z + d[1]));
                for (Node next : terrain.extraNeighbors(current.node)) neighbors.add(next);
                for (Node next : neighbors) {
                    if (!terrain.standable(next) || !terrain.traversable(current.node, next))
                        continue;
                    double stepCost = terrain.cost(current.node, next);
                    if (!Double.isFinite(stepCost) || stepCost <= 0) continue;
                    double cost = current.cost + stepCost;
                    if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    costs.put(next, cost);
                    parents.put(next, current.node);
                    open.add(new Entry(next, cost, goal.estimate(next)));
                }
            }
            if (open.isEmpty()) state = State.NO_PATH;
            else if (visited >= budget) state = State.NODE_LIMIT;
            return state;
        }
    }

    public List<Node> find(Node start, Terrain terrain, Goal goal, int budget) {
        return find(start, terrain, goal, budget, 0);
    }

    public List<Node> find(Node start, Terrain terrain, Goal goal, int budget, long maximumNanos) {
        Search search = new Search(start, terrain, goal, budget);
        search.advance(budget, maximumNanos);
        return search.path(); // a partial frontier never becomes a movement path
    }
}