package dev.optimus.client;

import com.google.gson.*;
import dev.optimus.core.CollisionReplaySelfTest;
import dev.optimus.core.geometry.ArenaPreset;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Loader and corridor-query checks independent of any user's private draft. */
public final class RouteHintsSelfTest {
    private static int checks;

    private static void expect(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static JsonObject map() throws IOException {
        try (Reader in =
                new InputStreamReader(
                        RouteHints.class.getResourceAsStream("/assets/optimus/pit-route-map.json"),
                        StandardCharsets.UTF_8)) {
            return new JsonParser().parse(in).getAsJsonObject();
        }
    }

    private static JsonObject area(String id, double x, double y, double z) {
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        a.addProperty("name", id);
        a.addProperty("x", x);
        a.addProperty("y", y);
        a.addProperty("z", z);
        a.addProperty("radius", 1.1);
        return a;
    }

    static JsonObject fixture() throws IOException {
        double[][] pairs = {
            {21.5, 65, -111.5, 23.5, 66, -107.5},
            {2.5, 69, -106.5, 5.5, 70, -102.5},
            {5.5, 70, -102.5, 2.5, 69, -106.5},
            {-19.5, 67.5, -100.5, -21.5, 66.5, -106.5},
            {16.5, 68.5, -96.5, 20.5, 68, -97.5},
            {20.5, 68, -97.5, 16.5, 68.5, -96.5},
            {20.5, 68, -97.5, 23.5, 66, -107.5},
            {23.5, 66, -107.5, 20.5, 68, -97.5},
            {-14.5, 63, -116.5, 20.5, 63, -115.5}
        };
        JsonObject draft = new JsonObject();
        draft.addProperty("format", "optimus-route-hints");
        draft.addProperty("schema", 1);
        draft.addProperty("mapHash", map().get("mapHash").getAsString());
        JsonArray areas = new JsonArray(), links = new JsonArray();
        for (int i = 0; i < pairs.length; i++) {
            double[] p = pairs[i];
            areas.add(area("from-" + i, p[0], p[1], p[2]));
            areas.add(area("to-" + i, p[3], p[4], p[5]));
            JsonObject link = new JsonObject();
            link.addProperty("from", "from-" + i);
            link.addProperty("to", "to-" + i);
            link.addProperty("parkour", i < 8);
            links.add(link);
        }
        draft.add("areas", areas);
        draft.add("links", links);
        return draft;
    }

    static RouteHints replayHints() throws IOException {
        return new RouteHints(map(), fixture(), "native replay fixture");
    }

    static RouteHints handoffHints() throws IOException {
        JsonObject draft = fixture();
        JsonArray areas = draft.getAsJsonArray("areas"), links = draft.getAsJsonArray("links");
        String[] ids = {
            "floor",
            "stair-foot",
            "stair-top",
            "west-roof",
            "main",
            "middle",
            "east-middle",
            "east-upper",
            "north-launch",
            "north-roof"
        };
        double[][] poses = {
            {.5, 63, -100.5},
            {-14.5, 63, -116.5},
            {-13.5, 65, -115.5},
            {-14.5, 68.5, -105.5},
            {2.5, 69, -106.5},
            {5.5, 70, -102.5},
            {13.5, 65, -79.5},
            {8.5, 66, -80.5},
            {-1.5, 69, -81.5},
            {-1.5, 68.5, -87.5}
        };
        for (int i = 0; i < ids.length; i++)
            areas.add(area("handoff-" + ids[i], poses[i][0], poses[i][1], poses[i][2]));
        for (int[] pair :
                new int[][] {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {6, 7}, {7, 8}, {8, 9}}) {
            JsonObject link = new JsonObject();
            link.addProperty("from", "handoff-" + ids[pair[0]]);
            link.addProperty("to", "handoff-" + ids[pair[1]]);
            link.addProperty("parkour", true);
            links.add(link);
        }
        return new RouteHints(map(), draft, "handoff regression fixture");
    }

    static RouteHints optionalHints() throws IOException {
        JsonObject draft = fixture();
        JsonArray areas = draft.getAsJsonArray("areas"), links = draft.getAsJsonArray("links");
        String[] ids = {"stair", "approach", "roof", "wall", "floor", "outer"};
        double[][] points = {
            {-14.5, 63, -116.5},
            {-14.5, 68.5, -105.5},
            {-19.5, 67.5, -100.5},
            {-21.5, 66.5, -106.5},
            {-18.5, 63, -101.5},
            {-18.5, 63, -90.5}
        };
        for (int i = 0; i < ids.length; i++)
            areas.add(area("optional-" + ids[i], points[i][0], points[i][1], points[i][2]));
        for (int i = 0; i + 1 < ids.length; i++) {
            JsonObject link = new JsonObject();
            link.addProperty("from", "optional-" + ids[i]);
            link.addProperty("to", "optional-" + ids[i + 1]);
            link.addProperty("parkour", true);
            links.add(link);
        }
        return new RouteHints(map(), draft, "optional hint regression fixture");
    }

    private static void mixedPlannerChecks() throws Exception {
        JsonObject model = map(), draft = fixture();
        JsonArray areas = new JsonArray(), links = new JsonArray();
        areas.add(draft.getAsJsonArray("areas").get(0));
        areas.add(draft.getAsJsonArray("areas").get(1));
        links.add(draft.getAsJsonArray("links").get(0));
        draft.add("areas", areas);
        draft.add("links", links);
        RouteHints hints = new RouteHints(model, draft, "one optional precision connection");
        CollisionGeometry live =
                CollisionReplaySelfTest.capture("duels-pit-2026-10-15.tsv.gz").cached();
        PitRegion pit = new PitRegion();
        pit.setCornerA(ArenaPreset.PIT_A);
        pit.setCornerB(ArenaPreset.PIT_B);
        Vec3Point from = new Vec3Point(21.5, 65, -111.5),
                landing = new Vec3Point(23.5, 66, -107.5),
                destination = null;
        for (JsonElement item : model.getAsJsonArray("nodes")) {
            JsonArray p = item.getAsJsonArray();
            Vec3Point candidate =
                    new Vec3Point(
                            p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
            if (Math.abs(candidate.y - landing.y) < .01
                    && candidate.horizontalDistanceSq(landing) > 1.21
                    && candidate.horizontalDistanceSq(landing) <= 16
                    && live.autoStepClear(landing, candidate, pit)) {
                destination = candidate;
                break;
            }
        }
        expect(
                destination != null,
                "one-hint fixture has a walkable destination outside the named landing area");
        final GridPathfinder.Node end = RouteHints.node(destination);
        GroundRoute ground = new GroundRoute(live, pit, pit, null, 63, true, true);
        final java.util.function.ToDoubleFunction<GridPathfinder.Node> estimate =
                hints.estimateTo(destination, pit, true, key -> false);
        GridPathfinder.Goal goal =
                new GridPathfinder.Goal() {
                    public boolean reached(GridPathfinder.Node n) {
                        return end.equals(n);
                    }

                    public double estimate(GridPathfinder.Node n) {
                        double value = estimate.applyAsDouble(n);
                        return Double.isFinite(value)
                                ? value
                                : Math.hypot(n.x - end.x, n.z - end.z);
                    }
                };
        java.util.List<GridPathfinder.Node> path =
                new GridPathfinder()
                        .find(
                                ground.start(from),
                                hints.augment(ground, live, pit, true, key -> false),
                                goal,
                                65536);
        expect(
                !path.isEmpty() && path.get(path.size() - 1).equals(end),
                "mixed search reaches an unhinted destination after one cached connection");
        int advanced = 0;
        for (int i = 1; i < path.size(); i++)
            if (hints.jump(path.get(i - 1), path.get(i)) != null) advanced++;
        expect(advanced == 1, "mixed route uses exactly its one required precision connection");
        path =
                new GridPathfinder()
                        .find(
                                ground.start(from),
                                hints.augment(ground, live, pit, true, key -> true),
                                goal,
                                65536);
        expect(
                !path.isEmpty(),
                "blocking the cached connection still permits an ordinary live detour");
        for (int i = 1; i < path.size(); i++)
            expect(
                    hints.jump(path.get(i - 1), path.get(i)) == null,
                    "blocked hint is absent from the ordinary fallback route");
    }

    private static void attachmentChecks() {
        JsonObject graph = new JsonObject();
        graph.addProperty("mapHash", "attachment-fixture");
        JsonArray nodes = new JsonArray(), edges = new JsonArray();
        double[][] coordinates = {
            {-.5, -100.5},
            {-1.5, -100.5},
            {-2.5, -100.5},
            {-3.5, -100.5},
            {-3.5, -101.5},
            {-2.5, -101.5},
            {-1.5, -101.5},
            {-.5, -101.5},
            {-.5, -102.5},
            {-1.5, -102.5},
            {-2.5, -102.5},
            {-3.5, -102.5},
            {-3.5, -103.5},
            {-2.5, -103.5},
            {-8.5, -105.5},
            {-8.5, -101.5},
            {-8.5, -95.5},
            {-3.5, -95.5},
            {3.5, -95.5},
            {8.5, -95.5},
            {8.5, -100.5}
        };
        for (int i = 0; i < coordinates.length; i++) {
            JsonArray p = new JsonArray();
            p.add(new JsonPrimitive(coordinates[i][0]));
            p.add(new JsonPrimitive(63));
            p.add(new JsonPrimitive(coordinates[i][1]));
            nodes.add(p);
            if (i > 0) {
                JsonArray e = new JsonArray();
                e.add(new JsonPrimitive(i - 1));
                e.add(new JsonPrimitive(i));
                e.add(new JsonPrimitive(0));
                e.add(
                        new JsonPrimitive(
                                Math.hypot(
                                        coordinates[i][0] - coordinates[i - 1][0],
                                        coordinates[i][1] - coordinates[i - 1][1])));
                edges.add(e);
            }
        }
        graph.add("nodes", nodes);
        graph.add("edges", edges);
        graph.add("momentumChains", new JsonArray());
        graph.add("precisionJumps", new JsonArray());
        JsonObject draft = new JsonObject();
        draft.addProperty("format", "optimus-route-hints");
        draft.addProperty("schema", 1);
        draft.addProperty("mapHash", "attachment-fixture");
        JsonArray areas = new JsonArray();
        areas.add(area("start", -.5, 63, -100.5));
        areas.add(area("end", 8.5, 63, -100.5));
        draft.add("areas", areas);
        JsonObject link = new JsonObject();
        link.addProperty("from", "start");
        link.addProperty("to", "end");
        link.addProperty("parkour", false);
        JsonArray links = new JsonArray();
        links.add(link);
        draft.add("links", links);
        RouteHints hints = new RouteHints(graph, draft, "attachment regression");
        PitRegion pit = new PitRegion();
        pit.setCornerA(new Vec3Point(-20, 62.5, -120));
        pit.setCornerB(new Vec3Point(20, 70, -80));
        CollisionGeometry live =
                new CollisionGeometry(
                                (x, y, z) -> {
                                    boolean solid =
                                            y == 62
                                                    || x == 4
                                                            && z >= -102
                                                            && z <= -99
                                                            && (y == 63 || y == 64);
                                    return solid
                                            ? new CollisionGeometry.Cell(
                                                    true,
                                                    false,
                                                    java.util.Arrays.asList(
                                                            new CollisionGeometry.Box(
                                                                    x, y, z, x + 1, y + 1, z + 1)))
                                            : CollisionGeometry.Cell.AIR;
                                })
                        .cached();
        Vec3Point from = new Vec3Point(.5, 63, -100.5), to = new Vec3Point(8.5, 63, -100.5);
        expect(
                !live.autoStepClear(from, to, pit),
                "attachment fixture requires going around a wall");
        RouteHints.Plan plan = hints.select(live, pit, from, to, false, key -> false);
        expect(
                plan != null && plan.nodes.size() <= 3,
                "a later reachable corridor entrance beats walking the closest-node detour");
        for (GridPathfinder.Node node : plan.nodes)
            expect(
                    node.x >= 0,
                    "entry selection does not send the bot backward through the saved prefix");
    }

    public static void main(String[] args) throws Exception {
        attachmentChecks();
        mixedPlannerChecks();
        JsonObject map = map(), draft = fixture();
        RouteHints hints = new RouteHints(map, draft, "test");
        try (InputStream in =
                RouteHints.class.getResourceAsStream("/assets/optimus/default-route-hints.json")) {
            expect(
                    in != null,
                    "source builds include the versioned release route graph without private drafts");
            if (in != null) {
                JsonObject bundled =
                        new JsonParser()
                                .parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                RouteHints built = new RouteHints(map, bundled, "bundled routes");
                expect(
                        built.areaCount == bundled.getAsJsonArray("areas").size()
                                && built.linkCount == bundled.getAsJsonArray("links").size(),
                        "the supplied default draft loads against the packaged model");
            }
        }
        expect(hints.areaCount == 18 && hints.linkCount == 9, "fixture counts");
        expect(
                hints.corridorSize() < map.getAsJsonArray("nodes").size(),
                "lookup searches a smaller saved-corridor graph");
        CollisionGeometry live =
                CollisionReplaySelfTest.capture("duels-pit-2026-10-15.tsv.gz").cached();
        PitRegion pit = new PitRegion();
        pit.setCornerA(ArenaPreset.PIT_A);
        pit.setCornerB(ArenaPreset.PIT_B);
        Vec3Point a = new Vec3Point(21.5, 65, -111.5), b = new Vec3Point(23.5, 66, -107.5);
        RouteHints.Plan plan = hints.select(live, pit, a, b, true, key -> false);
        expect(
                plan != null && plan.nodes.size() >= 2,
                "reported precision jump loads into a usable corridor");
        expect(
                plan.visited <= hints.corridorSize(),
                "corridor query does not expand the whole collision map");
        expect(
                hints.select(live, pit, a, b, false, key -> false) == null,
                "parkour off excludes advanced ascent");
        expect(
                hints.select(live, pit, a, b, true, key -> true) == null,
                "temporarily rejected connections cannot be reselected");
        CollisionGeometry unloaded =
                new CollisionGeometry((x, y, z) -> CollisionGeometry.Cell.UNLOADED);
        expect(
                hints.select(unloaded, pit, a, b, true, key -> false) == null,
                "unloaded attachments cannot authorize movement");
        Vec3Point floor = new Vec3Point(.5, 63, -100.5), near = new Vec3Point(4.5, 63, -100.5);
        expect(
                hints.select(live, pit, floor, near, true, key -> false) == null,
                "direct supported walking bypasses hints");
        JsonObject forged = new JsonParser().parse(draft.toString()).getAsJsonObject();
        forged.getAsJsonArray("links")
                .get(0)
                .getAsJsonObject()
                .addProperty("modelPath", "invented trajectory");
        expect(
                new RouteHints(map, forged, "test").select(live, pit, a, b, true, key -> false)
                        != null,
                "imported modelPath is ignored and recomputed");
        Path directory = Files.createTempDirectory("optimus-route-loader");
        Path file = directory.resolve(RouteHints.FILE_NAME);
        Path model = directory.resolve("optimus-route-map.json");
        Files.write(file, draft.toString().getBytes(StandardCharsets.UTF_8));
        expect(
                RouteHints.load(directory.toFile()).linkCount == 9,
                "config JSON overrides the bundled draft");
        expect(
                RouteHints.load(directory.toFile()).source.endsWith(RouteHints.FILE_NAME),
                "the Optimus route file takes priority");
        Files.write(model, map.toString().getBytes(StandardCharsets.UTF_8));
        expect(
                RouteHints.load(directory.toFile()).linkCount == 9,
                "a matching exported route map can override the bundled pit model");
        RouteHints old = OptimusMod.routeHints;
        File oldDirectory = OptimusMod.routeDirectory;
        String oldIssue = OptimusMod.routeIssue;
        try {
            OptimusMod.routeHints = hints;
            OptimusMod.routeDirectory = directory.toFile();
            Files.write(model, "{}".getBytes(StandardCharsets.UTF_8));
            expect(
                    !OptimusMod.reloadRoutes() && OptimusMod.routeHints == hints,
                    "an invalid map reload preserves the last usable graph");
            Files.write(model, map.toString().getBytes(StandardCharsets.UTF_8));
            for (String invalid :
                    new String[] {
                        "{",
                        "{}",
                        draft.toString().replace(map.get("mapHash").getAsString(), "different-map"),
                        draft.toString().replace("\"radius\":1.1", "\"radius\":0")
                    }) {
                Files.write(file, invalid.getBytes(StandardCharsets.UTF_8));
                expect(
                        !OptimusMod.reloadRoutes() && OptimusMod.routeHints == hints,
                        "invalid reload preserves the last usable graph");
            }
        } finally {
            OptimusMod.routeHints = old;
            OptimusMod.routeDirectory = oldDirectory;
            OptimusMod.routeIssue = oldIssue;
            Files.delete(file);
            Files.delete(model);
            Files.delete(directory);
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " route loader, map/geometry validation, bypass and corridor-query checks");
    }
}