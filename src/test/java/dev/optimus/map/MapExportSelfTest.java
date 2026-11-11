package dev.optimus.map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.optimus.core.geometry.CollisionGeometry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.storage.WorldInfo;

/** Native readers verify world/schematic output independently of the editor's importer. */
public final class MapExportSelfTest {
    private static int checks;

    private static void expect(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static byte[] bytes(InputStream input) throws IOException {
        try (InputStream in = input;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0; ) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static int nibble(byte[] data, int index) {
        return data.length == 0 ? 0 : data[index >> 1] >> ((index & 1) * 4) & 15;
    }

    public static void main(String[] args) throws Exception {
        MapSnapshot.Builder builder =
                new MapSnapshot.Builder("negative-origin", -3, 0, -5, 8, 8, 8);
        for (int z = -5; z < 3; z++)
            for (int x = -3; x < 5; x++) {
                int index = 64 + (z + 5) * 8 + x + 3;
                builder.set(
                        index,
                        1 << 4,
                        new CollisionGeometry.Cell(
                                true,
                                false,
                                Collections.singletonList(
                                        new CollisionGeometry.Box(x, 1, z, x + 1, 2, z + 1))));
            }
        int extendedIndex = 64 + 7 * 8 + 7;
        builder.set(
                extendedIndex,
                300 << 4 | 7,
                new CollisionGeometry.Cell(
                        true,
                        false,
                        Collections.singletonList(new CollisionGeometry.Box(4, 1, 2, 5, 2, 3))));
        NBTTagCompound sign = new NBTTagCompound();
        sign.setString("id", "Sign");
        sign.setInteger("x", -2);
        sign.setInteger("y", 2);
        sign.setInteger("z", -4);
        sign.setString("Text1", "Unicode: \u2192");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        CompressedStreamTools.write(sign, new DataOutputStream(raw));
        builder.addTile(raw.toByteArray());
        builder.setBiome(-2, -4, 5);
        MapSnapshot capture = builder.build();
        boolean sealed = false;
        try {
            builder.set(0, 0, CollisionGeometry.Cell.AIR);
        } catch (IllegalStateException expected) {
            sealed = true;
        }
        expect(sealed, "a completed snapshot cannot be changed by its capture builder");
        expect(
                capture.hash().equals(capture.hash()) && capture.hash().matches("[a-f0-9]{64}"),
                "map hash is deterministic");
        Path output = Paths.get("build/map-export-tests");
        Files.createDirectories(output);
        Path archive = MapArchiveWriter.write(capture, output, message -> {});
        Files.copy(
                archive,
                Paths.get("build/map-export-fixture.zip"),
                StandardCopyOption.REPLACE_EXISTING);
        Path extracted = Files.createTempDirectory(output, "native-read-");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            expect(
                    zip.getEntry("level.dat") != null
                            && zip.getEntry("map.json") != null
                            && zip.getEntry("negative-origin.schematic") != null,
                    "one archive includes the offline world, editor graph and schematic");
            NBTTagCompound schematic =
                    CompressedStreamTools.readCompressed(
                            new ByteArrayInputStream(
                                    bytes(
                                            zip.getInputStream(
                                                    zip.getEntry("negative-origin.schematic")))));
            expect(
                    schematic.getShort("Width") == 8
                            && schematic.getShort("Length") == 8
                            && schematic.getInteger("WEOriginX") == -3,
                    "schematic keeps dimensions and negative world coordinates");
            byte[] ids = schematic.getByteArray("Blocks"),
                    data = schematic.getByteArray("Data"),
                    add = schematic.getByteArray("AddBlocks");
            for (int i = 0; i < capture.volume(); i++)
                expect(
                        ((((ids[i] & 255) | nibble(add, i) << 8) << 4) | (data[i] & 15))
                                == capture.state(i),
                        "every schematic block ID and metadata nibble survives export");
            NBTTagCompound tile = schematic.getTagList("TileEntities", 10).getCompoundTagAt(0);
            expect(
                    tile.getInteger("x") == 1
                            && tile.getInteger("z") == 1
                            && tile.getString("Text1").equals("Unicode: \u2192"),
                    "schematic tile coordinates are relative and Unicode content survives");
            WorldInfo info =
                    new WorldInfo(
                            CompressedStreamTools.readCompressed(
                                            zip.getInputStream(zip.getEntry("level.dat")))
                                    .getCompoundTag("Data"));
            expect(
                    info.getSaveVersion() == 19133 && info.getGameType().isCreative(),
                    "native Minecraft reads a 1.8.9 inspection world");
            JsonObject map =
                    new JsonParser()
                            .parse(
                                    new String(
                                            bytes(zip.getInputStream(zip.getEntry("map.json"))),
                                            StandardCharsets.UTF_8))
                            .getAsJsonObject();
            expect(
                    map.get("mapHash").getAsString().equals(capture.hash())
                            && map.getAsJsonArray("nodes").size() >= 36
                            && map.getAsJsonArray("edges").size() > 0,
                    "exported editor graph describes this exact native snapshot");
            Map<String, NBTTagCompound> chunks = new HashMap<>();
            for (ZipEntry entry : Collections.list(zip.entries()))
                if (entry.getName().endsWith(".mca")) {
                    Path path = extracted.resolve(Paths.get(entry.getName()).getFileName());
                    Files.write(path, bytes(zip.getInputStream(entry)));
                    RegionFile region = new RegionFile(path.toFile());
                    try {
                        for (int x = 0; x < 32; x++)
                            for (int z = 0; z < 32; z++)
                                try (java.io.DataInputStream in =
                                        region.getChunkDataInputStream(x, z)) {
                                    if (in == null) continue;
                                    NBTTagCompound chunk =
                                            CompressedStreamTools.read(in).getCompoundTag("Level");
                                    chunks.put(
                                            chunk.getInteger("xPos")
                                                    + ","
                                                    + chunk.getInteger("zPos"),
                                            chunk);
                                }
                    } finally {
                        region.close();
                    }
                }
            for (int i = 0; i < capture.volume(); i++) {
                NBTTagCompound chunk =
                        chunks.get(
                                Math.floorDiv(capture.x(i), 16)
                                        + ","
                                        + Math.floorDiv(capture.z(i), 16));
                int restored = 0;
                NBTTagList sections = chunk.getTagList("Sections", 10);
                for (int j = 0; j < sections.tagCount(); j++) {
                    NBTTagCompound section = sections.getCompoundTagAt(j);
                    if (section.getByte("Y") != capture.y(i) >> 4) continue;
                    int local =
                            (capture.y(i) & 15) * 256
                                    + (capture.z(i) & 15) * 16
                                    + (capture.x(i) & 15);
                    restored =
                            ((section.getByteArray("Blocks")[local] & 255)
                                                    | nibble(section.getByteArray("Add"), local)
                                                            << 8)
                                            << 4
                                    | nibble(section.getByteArray("Data"), local);
                }
                expect(
                        restored == capture.state(i),
                        "native Anvil reader restores every block, including negative chunks and extended IDs");
            }
            NBTTagCompound biomeChunk = chunks.get("-1,-1");
            expect(
                    (biomeChunk.getByteArray("Biomes")[12 * 16 + 14] & 255) == 5,
                    "world export preserves client-loaded biome IDs at negative coordinates");
        }
        long before;
        try (java.util.stream.Stream<Path> files = Files.list(output)) {
            before = files.filter(p -> p.toString().endsWith(".zip")).count();
        }
        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try {
            MapArchiveWriter.write(capture, output, message -> {});
        } catch (IOException expected) {
            cancelled = true;
        } finally {
            Thread.interrupted();
        }
        expect(cancelled, "interrupted exports cannot publish a completed archive");
        try (java.util.stream.Stream<Path> files = Files.list(output)) {
            expect(
                    files.noneMatch(p -> p.getFileName().toString().startsWith(".capture-")),
                    "successful and cancelled exports clean staging files");
        }
        try (java.util.stream.Stream<Path> files = Files.list(output)) {
            expect(
                    files.filter(p -> p.toString().endsWith(".zip")).count() == before,
                    "cancelled writes create no final archive");
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " native map archive, schema, metadata, tile, ownership and cancellation checks");
    }
}