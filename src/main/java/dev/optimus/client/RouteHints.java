package dev.optimus.client;

import com.google.gson.*;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GridPathfinder.Node;
import dev.optimus.core.navigation.GroundRoute;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** Immutable, directed cached corridors. Imported flight samples never become ground waypoints. */
final class RouteHints {
    static final String FILE_NAME = "optimus-route-hints.json";
    private static final String ASSETS = "/assets/optimus/";

    static final class Jump {
        final int kind;
        final Vec3Point from, to, start, runUp;
        final float yaw;
        List<Vec3Point> corridor;

        Jump(int kind, Vec3Point from, Vec3Point to, Vec3Point start, Vec3Point runUp, float yaw) {
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.start = start;
            this.runUp = runUp;
            this.yaw = yaw;
        }
    }

    private static final class Edge {
        final int from, to, kind;
        final double cost;

        Edge(int from, int to, int kind, double cost) {
            this.from = from;
            this.to = to;
            this.kind = kind;
            this.cost = cost;
        }
    }

    private static final class Entry implements Comparable<Entry> {
        final int id;
        final double cost;

        Entry(int id, double cost) {
            this.id = id;
            this.cost = cost;
        }

        public int compareTo(Entry b) {
            return Double.compare(cost, b.cost);
        }
    }

    static final class Plan {
        final List<Node> nodes;
        final int visited;

        Plan(List<Node> nodes, int visited) {
            this.nodes = Collections.unmodifiableList(nodes);
            this.visited = visited;
        }
    }

    final int areaCount, linkCount;
    final String source;
    private final String mapHash;
    private final List<Vec3Point> points = new ArrayList<>();
    private final List<List<Edge>> mapEdges = new ArrayList<>(), corridors = new ArrayList<>();
    private final List<List<Edge>> incoming = new ArrayList<>();
    private final Map<Node, Integer> pointIds = new HashMap<>();
    private final Set<String> savedAdvanced = new HashSet<>();
    private final Map<String, Jump> jumps = new HashMap<>();
    private final Set<Integer> corridorNodes = new LinkedHashSet<>();
    private final Map<Node, Map<Node, Shortcut>> shortcuts = new HashMap<>();

    private final class Shortcut {
        final List<Edge> edges;
        final int first, end;
        final double cost;

        Shortcut(List<Edge> edges, int first, int end) {
            this.edges = edges;
            this.first = first;
            this.end = end;
            double total = 0;
            for (int i = first; i < end; i++) total += edgeCost(edges.get(i));
            cost = total;
        }

        List<Node> nodes() {
            List<Node> result = new ArrayList<>();
            result.add(node(points.get(edges.get(first).from)));
            for (int i = first; i < end; i++) result.add(node(points.get(edges.get(i).to)));
            return result;
        }
    }

    static RouteHints load(File directory) throws IOException {
        File file = new File(directory, FILE_NAME);
        try (InputStream map = mapStream(directory)) {
            if (map == null) throw new IOException("Missing packaged route map");
            if (file.exists()) {
                if (!file.isFile() || file.length() > 8 * 1024 * 1024)
                    throw new IOException("Route file is not a JSON file under 8 MB");
                try (InputStream in = new FileInputStream(file)) {
                    return new RouteHints(
                            read(map, 64 * 1024 * 1024), read(in), "config/" + file.getName());
                }
            }
            try (InputStream in =
                    RouteHints.class.getResourceAsStream(ASSETS + "default-route-hints.json")) {
                if (in == null)
                    throw new IOException("No route file: put " + FILE_NAME + " in config");
                return new RouteHints(read(map, 64 * 1024 * 1024), read(in), "bundled routes");
            }
        } catch (RuntimeException error) {
            throw new IOException("Invalid route file: " + error.getMessage(), error);
        }
    }

    private static InputStream mapStream(File directory) throws IOException {
        File override = new File(directory, "optimus-route-map.json");
        if (!override.exists())
            return RouteHints.class.getResourceAsStream(ASSETS + "pit-route-map.json");
        if (!override.isFile() || override.length() > 64 * 1024 * 1024)
            throw new IOException("Route map must be a JSON file under 64 MB");
        return new FileInputStream(override);
    }

    private static JsonObject read(InputStream in) throws IOException {
        return read(in, 8 * 1024 * 1024);
    }

    private static JsonObject read(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count, total = 0;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > maximum)
                throw new IOException("Route data exceeds " + (maximum / 1024 / 1024) + " MB");
            out.write(buffer, 0, count);
        }
        try {
            return new JsonParser()
                    .parse(new String(out.toByteArray(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Malformed route JSON", error);
        }
    }

    RouteHints(JsonObject map, JsonObject draft, String source) {
        this.source = source;
        this.mapHash = string(map, "mapHash");
        if (!"optimus-route-hints".equals(string(draft, "format"))
                || draft.get("schema").getAsInt() != 1
                || !string(map, "mapHash").equals(string(draft, "mapHash")))
            throw new IllegalArgumentException("Different map or format");
        for (JsonElement element : map.getAsJsonArray("nodes")) {
            Vec3Point p = point(element);
            pointIds.put(node(p), points.size());
            points.add(p);
            mapEdges.add(new ArrayList<>());
            corridors.add(new ArrayList<>());
            incoming.add(new ArrayList<>());
        }
        for (JsonElement item : map.getAsJsonArray("edges")) {
            JsonArray e = item.getAsJsonArray();
            int from = e.get(0).getAsInt(), to = e.get(1).getAsInt(), kind = e.get(2).getAsInt();
            double cost = e.get(3).getAsDouble();
            if (from < 0
                    || to < 0
                    || from >= points.size()
                    || to >= points.size()
                    || kind < 0
                    || kind > 5
                    || !Double.isFinite(cost)
                    || cost <= 0) throw new IllegalArgumentException("Invalid packaged edge");
            Edge edge = new Edge(from, to, kind, cost);
            mapEdges.get(from).add(edge);
            incoming.get(to).add(edge);
        }
        for (String name : new String[] {"momentumChains", "precisionJumps"})
            for (JsonElement item : map.getAsJsonArray(name)) {
                JsonObject j = item.getAsJsonObject();
                int a = j.get("from").getAsInt(),
                        b = j.get("to").getAsInt(),
                        kind = name.equals("momentumChains") ? 4 : 5;
                JsonArray trajectory = j.getAsJsonArray("points");
                Vec3Point start = point(trajectory.get(kind == 4 ? 1 : 2));
                Jump jump =
                        new Jump(
                                kind,
                                points.get(a),
                                points.get(b),
                                start,
                                kind == 4 ? start : point(j.get("runUp")),
                                j.get("yaw").getAsFloat());
                jump.corridor = new ArrayList<>();
                for (JsonElement p : trajectory) jump.corridor.add(point(p));
                jumps.put(key(node(jump.from), node(jump.to)), jump);
            }
        addSpecificPitJump();
        JsonArray areas = draft.getAsJsonArray("areas"), links = draft.getAsJsonArray("links");
        if (areas == null || links == null || areas.size() > 128 || links.size() > 512)
            throw new IllegalArgumentException("Use at most 128 areas and 512 connections");
        areaCount = areas.size();
        linkCount = links.size();
        Map<String, Set<Integer>> members = new LinkedHashMap<>();
        for (JsonElement item : areas) {
            JsonObject a = item.getAsJsonObject();
            String id = string(a, "id"), name = string(a, "name");
            double x = number(a, "x"),
                    y = number(a, "y"),
                    z = number(a, "z"),
                    radius = number(a, "radius");
            if (!id.matches("[a-zA-Z][\\w-]{0,63}")
                    || members.containsKey(id)
                    || name.trim().isEmpty()
                    || name.length() > 60
                    || radius < .4
                    || radius > 3.5) throw new IllegalArgumentException("Invalid area");
            Set<Integer> cells = new LinkedHashSet<>();
            for (int i = 0; i < points.size(); i++) {
                Vec3Point p = points.get(i);
                if (Math.abs(p.y - y) <= .26 && Math.hypot(p.x - x, p.z - z) <= radius)
                    cells.add(i);
            }
            if (cells.isEmpty()) throw new IllegalArgumentException("Unsupported area: " + name);
            members.put(id, cells);
            // Adjacent entrances within one area must be able to join, rather than teleporting
            // between members.
            for (int i : cells)
                for (Edge edge : mapEdges.get(i))
                    if (edge.kind == 0 && cells.contains(edge.to)) add(edge);
        }
        Set<String> pairs = new HashSet<>();
        for (JsonElement item : links) {
            JsonObject link = item.getAsJsonObject();
            String from = string(link, "from"), to = string(link, "to");
            if (from.equals(to)
                    || !members.containsKey(from)
                    || !members.containsKey(to)
                    || !pairs.add(from + ">" + to))
                throw new IllegalArgumentException("Invalid or duplicate connection");
            if (!link.has("parkour")
                    || !link.get("parkour").isJsonPrimitive()
                    || !link.getAsJsonPrimitive("parkour").isBoolean())
                throw new IllegalArgumentException("Invalid jump option");
            List<Edge> path =
                    shortest(
                            mapEdges,
                            members.get(from),
                            members.get(to),
                            link.get("parkour").getAsBoolean());
            if (path == null)
                throw new IllegalArgumentException("No checked route: " + from + " -> " + to);
            for (Edge edge : path) add(edge);
            for (int i = 0; i < path.size(); i++) {
                addShortcut(path, i, Math.min(i + 8, path.size()));
                addShortcut(path, i, path.size());
            }
        }
    }

    private void addSpecificPitJump() {
        Integer a = pointIds.get(node(SpecificPitJump.FROM)),
                b = pointIds.get(node(SpecificPitJump.TO));
        if (a == null
                || b == null
                || !"b84d5626434409549056b173a614e12965b8f3d12fd7d622641b7214502f1c94"
                        .equals(mapHash)) return;
        Edge edge = new Edge(a, b, SpecificPitJump.KIND, 20);
        mapEdges.get(a).add(edge);
        incoming.get(b).add(edge);
        Jump jump =
                new Jump(
                        edge.kind,
                        SpecificPitJump.FROM,
                        SpecificPitJump.TO,
                        SpecificPitJump.SETUP,
                        SpecificPitJump.SETUP,
                        90);
        jump.corridor = Arrays.asList(jump.from, jump.start, jump.to);
        jumps.put(key(node(jump.from), node(jump.to)), jump);
        add(edge);
    }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException("Missing " + key);
        return o.get(key).getAsString();
    }

    private static double number(JsonObject o, String key) {
        double value = o.get(key).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite " + key);
        return value;
    }

    private static Vec3Point point(JsonElement item) {
        JsonArray a = item.getAsJsonArray();
        if (a.size() != 3) throw new IllegalArgumentException("Invalid point");
        Vec3Point p =
                new Vec3Point(
                        a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        if (!Double.isFinite(p.x + p.y + p.z)) throw new IllegalArgumentException("Invalid point");
        return p;
    }

    static Node node(Vec3Point p) {
        return new Node((int) Math.floor(p.x), (int) Math.round(p.y * 2), (int) Math.floor(p.z));
    }

    static String key(Node a, Node b) {
        return a.x + "," + a.y2 + "," + a.z + ">" + b.x + "," + b.y2 + "," + b.z;
    }

    Jump jump(Node a, Node b) {
        return jumps.get(key(a, b));
    }

    int corridorSize() {
        return corridorNodes.size();
    }

    private void add(Edge edge) {
        if (corridors.get(edge.from).stream().noneMatch(e -> e.to == edge.to))
            corridors.get(edge.from).add(edge);
        corridorNodes.add(edge.from);
        corridorNodes.add(edge.to);
        addShortcut(Collections.singletonList(edge), 0, 1);
        if (edge.kind >= 4)
            savedAdvanced.add(key(node(points.get(edge.from)), node(points.get(edge.to))));
    }

    private void addShortcut(List<Edge> edges, int first, int end) {
        if (first >= end) return;
        Node from = node(points.get(edges.get(first).from)),
                to = node(points.get(edges.get(end - 1).to));
        if (from.equals(to)) return;
        Shortcut shortcut = new Shortcut(edges, first, end);
        Map<Node, Shortcut> exits = shortcuts.computeIfAbsent(from, k -> new HashMap<>());
        Shortcut old = exits.get(to);
        if (old == null || shortcut.cost < old.cost) exits.put(to, shortcut);
    }

    boolean uses(List<Node> path) {
        for (int i = 1; i < path.size(); i++) {
            Map<Node, Shortcut> exits = shortcuts.get(path.get(i - 1));
            if (exits != null && exits.containsKey(path.get(i))) return true;
        }
        return false;
    }

    private double edgeCost(Edge edge) {
        Vec3Point a = points.get(edge.from), b = points.get(edge.to);
        double overhead =
                edge.kind == SpecificPitJump.KIND
                        ? 14
                        : edge.kind == 4
                                ? 7
                                : edge.kind == 2 || edge.kind == 5
                                        ? 4
                                        : edge.kind == 3 ? 1 : edge.kind == 1 ? .75 : 0;
        return Math.sqrt(a.horizontalDistanceSq(b)) + Math.abs(a.y - b.y) * .2 + overhead;
    }

    java.util.function.ToDoubleFunction<Node> estimateTo(
            Vec3Point destination, PitRegion area, boolean parkour, Predicate<String> blocked) {
        final double[] remaining = new double[points.size()];
        Arrays.fill(remaining, Double.POSITIVE_INFINITY);
        PriorityQueue<Entry> open = new PriorityQueue<>();
        for (int i = 0; i < points.size(); i++) {
            Vec3Point p = points.get(i);
            if (area.containsBody(p, .3, 1.8)
                    && p.horizontalDistanceSq(destination) <= 4
                    && Math.abs(p.y - destination.y) <= .6) {
                remaining[i] = 0;
                open.add(new Entry(i, 0));
            }
        }
        // A distance estimate over the captured model guides the live search
        // toward useful stairs without prescribing a chain of saved areas.
        while (!open.isEmpty()) {
            Entry current = open.remove();
            if (current.cost != remaining[current.id]) continue;
            for (Edge edge : incoming.get(current.id)) {
                if (!parkour && edge.kind != 0 && edge.kind != 1 && edge.kind != 3) continue;
                if (edge.kind >= 4) {
                    String k = key(node(points.get(edge.from)), node(points.get(edge.to)));
                    if (!savedAdvanced.contains(k) || blocked.test(k)) continue;
                }
                Vec3Point a = points.get(edge.from), b = points.get(edge.to);
                if (!area.containsBody(a, .3, 1.8)) continue;
                double cost = edgeCost(edge);
                double next = current.cost + cost;
                if (next < remaining[edge.from]) {
                    remaining[edge.from] = next;
                    open.add(new Entry(edge.from, next));
                }
            }
        }
        return n -> {
            Integer id = pointIds.get(n);
            return id == null ? Double.NaN : remaining[id];
        };
    }

    GridPathfinder.Terrain augment(
            final GroundRoute ground,
            final CollisionGeometry live,
            final PitRegion area,
            final boolean parkour,
            final Predicate<String> blocked) {
        return new GridPathfinder.Terrain() {
            final Map<String, Boolean> available = new HashMap<>(), checked = new HashMap<>();
            final Map<String, Shortcut> selected = new HashMap<>();

            private double ordinaryCost(Node from, Node to) {
                Vec3Point a = ground.point(from), b = ground.point(to);
                double base =
                        Math.hypot(to.x - from.x, to.z - from.z) + Math.abs(to.y2 - from.y2) * .1;
                if (live.autoStepClear(a, b, area)) return base;
                if (ground.drop(a, b)) return base + 1;
                return base + (ground.gap(a, b) ? 4 : .75);
            }

            private boolean edgeAvailable(Edge edge) {
                String k = key(node(points.get(edge.from)), node(points.get(edge.to)));
                Boolean old = checked.get(k);
                if (old != null) return old;
                boolean clear =
                        allowed(edge, parkour)
                                && !blocked.test(k)
                                && ground.standable(node(points.get(edge.from)))
                                && ground.standable(node(points.get(edge.to)));
                if (clear && edge.kind >= 4) {
                    Jump jump = jumps.get(k);
                    clear = jump != null;
                    if (clear && edge.kind == SpecificPitJump.KIND)
                        clear = SpecificPitJump.available(live, area);
                    if (clear)
                        for (Vec3Point p : jump.corridor)
                            if (!area.containsBody(p, .3, 1.8)
                                    || !live.free(CollisionGeometry.body(p))) {
                                clear = false;
                                break;
                            }
                } else if (clear)
                    clear =
                            ground.traversable(
                                    node(points.get(edge.from)), node(points.get(edge.to)));
                checked.put(k, clear);
                return clear;
            }

            public boolean standable(Node n) {
                return ground.standable(n);
            }

            public boolean traversable(Node from, Node to) {
                String k = key(from, to);
                Boolean old = available.get(k);
                if (old != null) return old;
                boolean normal = ground.traversable(from, to);
                Shortcut shortcut = shortcuts.getOrDefault(from, Collections.emptyMap()).get(to);
                double ordinary = normal ? ordinaryCost(from, to) : Double.POSITIVE_INFINITY;
                if (shortcut != null && (!normal || shortcut.cost < ordinary)) {
                    boolean clear = true;
                    for (int i = shortcut.first; i < shortcut.end; i++)
                        if (!edgeAvailable(shortcut.edges.get(i))) {
                            clear = false;
                            break;
                        }
                    if (clear) {
                        selected.put(k, shortcut);
                        available.put(k, true);
                        return true;
                    }
                }
                available.put(k, normal);
                return normal;
            }

            public Iterable<Node> extraNeighbors(Node from) {
                List<Node> result = new ArrayList<>();
                for (Node n : ground.extraNeighbors(from)) result.add(n);
                result.addAll(shortcuts.getOrDefault(from, Collections.emptyMap()).keySet());
                return result;
            }

            public double cost(Node from, Node to) {
                Shortcut shortcut = selected.get(key(from, to));
                return shortcut == null ? ordinaryCost(from, to) : shortcut.cost;
            }

            public List<Node> segment(Node from, Node to) {
                Shortcut shortcut = selected.get(key(from, to));
                return shortcut == null ? Arrays.asList(from, to) : shortcut.nodes();
            }
        };
    }

    private static boolean allowed(Edge edge, boolean parkour) {
        return parkour || edge.kind == 0 || edge.kind == 3;
    }

    private List<Edge> shortest(
            List<List<Edge>> edges, Set<Integer> starts, Set<Integer> goals, boolean parkour) {
        double[] costs = new double[points.size()];
        Arrays.fill(costs, Double.POSITIVE_INFINITY);
        Edge[] parents = new Edge[points.size()];
        PriorityQueue<Entry> open = new PriorityQueue<>();
        for (int id : starts) {
            costs[id] = 0;
            open.add(new Entry(id, 0));
        }
        while (!open.isEmpty()) {
            Entry entry = open.remove();
            if (entry.cost != costs[entry.id]) continue;
            if (goals.contains(entry.id)) {
                LinkedList<Edge> path = new LinkedList<>();
                for (Edge edge = parents[entry.id]; edge != null; edge = parents[edge.from])
                    path.addFirst(edge);
                return path;
            }
            for (Edge edge : edges.get(entry.id))
                if (allowed(edge, parkour) && entry.cost + edge.cost < costs[edge.to]) {
                    costs[edge.to] = entry.cost + edge.cost;
                    parents[edge.to] = edge;
                    open.add(new Entry(edge.to, costs[edge.to]));
                }
        }
        return null;
    }

    private List<Integer> attach(Vec3Point p, CollisionGeometry live, PitRegion area) {
        List<Integer> nearby = new ArrayList<>();
        for (int id : corridorNodes)
            if (Math.abs(points.get(id).y - p.y) < .6
                    && points.get(id).horizontalDistanceSq(p) <= 64) nearby.add(id);
        nearby.sort(Comparator.comparingDouble(id -> points.get(id).horizontalDistanceSq(p)));
        List<Integer> result = new ArrayList<>();
        // Compare the full route cost from several reachable entrances/exits,
        // rather than forcing a trip through the closest four corridor cells.
        for (int i = 0; i < Math.min(nearby.size(), 32); i++) {
            int id = nearby.get(i);
            if (live.autoStepClear(p, points.get(id), area)) result.add(id);
        }
        return result;
    }

    Plan select(
            CollisionGeometry live,
            PitRegion area,
            Vec3Point from,
            Vec3Point destination,
            boolean parkour,
            Predicate<String> blocked) {
        if (live.autoStepClear(from, destination, area)) return null;
        List<Integer> starts = attach(from, live, area), goals = attach(destination, live, area);
        if (starts.isEmpty() || goals.isEmpty()) return null;
        double[] costs = new double[points.size()];
        Arrays.fill(costs, Double.POSITIVE_INFINITY);
        Edge[] parents = new Edge[points.size()];
        PriorityQueue<Entry> open = new PriorityQueue<>();
        for (int id : starts) {
            costs[id] = Math.sqrt(from.horizontalDistanceSq(points.get(id)));
            open.add(new Entry(id, costs[id]));
        }
        int visited = 0, best = -1;
        double bestCost = Double.POSITIVE_INFINITY;
        while (!open.isEmpty()) {
            Entry current = open.remove();
            if (current.cost != costs[current.id]) continue;
            if (current.cost >= bestCost) break;
            visited++;
            if (goals.contains(current.id)) {
                double total =
                        current.cost
                                + Math.sqrt(
                                        points.get(current.id).horizontalDistanceSq(destination));
                if (total < bestCost) {
                    best = current.id;
                    bestCost = total;
                }
            }
            for (Edge edge : corridors.get(current.id))
                if (allowed(edge, parkour)
                        && !blocked.test(
                                key(node(points.get(edge.from)), node(points.get(edge.to))))) {
                    double next = current.cost + edge.cost;
                    if (next >= costs[edge.to]) continue;
                    costs[edge.to] = next;
                    parents[edge.to] = edge;
                    open.add(new Entry(edge.to, next));
                }
        }
        if (best < 0) return null;
        LinkedList<Node> nodes = new LinkedList<>();
        int id = best;
        nodes.add(node(points.get(id)));
        while (parents[id] != null) {
            id = parents[id].from;
            nodes.addFirst(node(points.get(id)));
        }
        Node first = node(from), last = node(destination);
        if (!first.equals(nodes.getFirst())) nodes.addFirst(first);
        if (!last.equals(nodes.getLast())) nodes.addLast(last);
        return new Plan(nodes, visited);
    }
}