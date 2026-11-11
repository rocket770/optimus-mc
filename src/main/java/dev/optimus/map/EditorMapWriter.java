package dev.optimus.map;

import com.google.gson.stream.JsonWriter;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundRoute;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Builds ordinary connections with the same geometry and route rules as the live navigator. */
public final class EditorMapWriter {
    private EditorMapWriter() {}

    public static void write(MapSnapshot capture, Writer output, Consumer<String> progress)
            throws IOException {
        CollisionGeometry geometry = capture.geometry();
        PitRegion area = capture.area();
        GroundRoute route = new GroundRoute(geometry, area, new PitRegion(), null, 0, true, true);
        List<Vec3Point> points = new ArrayList<>();
        Map<String, List<Integer>> columns = new HashMap<>();
        Set<String> unique = new HashSet<>();
        for (Map.Entry<Integer, CollisionGeometry.Cell> entry : capture.cells().entrySet()) {
            interrupted();
            int x = capture.x(entry.getKey()), z = capture.z(entry.getKey());
            for (CollisionGeometry.Box box : entry.getValue().boxes) {
                Vec3Point point = new Vec3Point(x + .5, box.maxY, z + .5);
                if (!unique.add(point.serialize()) || !geometry.standable(area, point)) continue;
                if (points.size() >= 25000)
                    throw new IOException(
                            "More than 25,000 supported surfaces; export a smaller radius");
                columns.computeIfAbsent(x + "," + z, key -> new ArrayList<>()).add(points.size());
                points.add(point);
            }
        }
        JsonWriter json = new JsonWriter(output);
        json.beginObject()
                .name("format")
                .value("optimus-map")
                .name("schema")
                .value(1)
                .name("editorBuild")
                .value("workshop-r4")
                .name("name")
                .value(capture.name)
                .name("mapHash")
                .value(capture.hash())
                .name("model")
                .value("native-collision")
                .name("bounds")
                .beginArray()
                .value(capture.minX)
                .value(capture.minY)
                .value(capture.minZ)
                .value(capture.minX + capture.width)
                .value(capture.minY + capture.height)
                .value(capture.minZ + capture.length)
                .endArray();
        json.name("boxes").beginArray();
        int rendered = 0;
        for (Map.Entry<Integer, CollisionGeometry.Cell> entry : capture.cells().entrySet()) {
            interrupted();
            int index = entry.getKey(),
                    x = capture.x(index),
                    y = capture.y(index),
                    z = capture.z(index);
            if (interior(capture, x, y, z)) continue;
            for (CollisionGeometry.Box box : entry.getValue().boxes) {
                if (++rendered > 200000)
                    throw new IOException(
                            "More than 200,000 visible collision boxes; export a smaller radius");
                json.beginArray()
                        .value(box.minX)
                        .value(box.minY)
                        .value(box.minZ)
                        .value(box.maxX)
                        .value(box.maxY)
                        .value(box.maxZ)
                        .value(capture.state(index) >> 4)
                        .endArray();
            }
        }
        json.endArray().name("nodes").beginArray();
        for (Vec3Point point : points)
            json.beginArray().value(point.x).value(point.y).value(point.z).endArray();
        json.endArray().name("edges").beginArray();
        for (int i = 0; i < points.size(); i++) {
            interrupted();
            if (i % 250 == 0)
                progress.accept("Building routes: " + i + "/" + points.size() + " surfaces");
            Vec3Point from = points.get(i);
            int x = (int) Math.floor(from.x), z = (int) Math.floor(from.z);
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dz == 0
                            || dx != 0 && dz != 0 && (Math.abs(dx) != 1 || Math.abs(dz) != 1))
                        continue;
                    for (int j :
                            columns.getOrDefault(
                                    (x + dx) + "," + (z + dz), java.util.Collections.emptyList())) {
                        Vec3Point to = points.get(j);
                        if (to.y > from.y + CollisionGeometry.MAXIMUM_JUMP_RISE
                                || to.y < from.y - 3
                                || !route.edgeClear(from, to)) continue;
                        int kind =
                                geometry.autoStepClear(from, to, area)
                                        ? 0
                                        : route.drop(from, to)
                                                ? 3
                                                : from.horizontalDistanceSq(to) <= 2.01
                                                                && to.y > from.y + .6
                                                        ? 1
                                                        : 2;
                        double cost =
                                Math.sqrt(from.horizontalDistanceSq(to))
                                        + Math.abs(to.y - from.y) * .2
                                        + (kind == 1 ? .8 : kind == 2 ? 2 : kind == 3 ? .6 : 0);
                        json.beginArray().value(i).value(j).value(kind).value(cost).endArray();
                    }
                }
        }
        json.endArray()
                .name("momentumChains")
                .beginArray()
                .endArray()
                .name("precisionJumps")
                .beginArray()
                .endArray()
                .endObject();
        json.flush();
    }

    private static boolean fullCube(MapSnapshot capture, int x, int y, int z) {
        List<CollisionGeometry.Box> boxes = capture.cell(x, y, z).boxes;
        if (boxes.size() != 1) return false;
        CollisionGeometry.Box box = boxes.get(0);
        return box.minX == x
                && box.minY == y
                && box.minZ == z
                && box.maxX == x + 1
                && box.maxY == y + 1
                && box.maxZ == z + 1;
    }

    private static boolean interior(MapSnapshot c, int x, int y, int z) {
        return fullCube(c, x, y, z)
                && fullCube(c, x - 1, y, z)
                && fullCube(c, x + 1, y, z)
                && fullCube(c, x, y - 1, z)
                && fullCube(c, x, y + 1, z)
                && fullCube(c, x, y, z - 1)
                && fullCube(c, x, y, z + 1);
    }

    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Map export cancelled");
    }
}