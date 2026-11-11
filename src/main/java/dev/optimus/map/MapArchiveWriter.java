package dev.optimus.map;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.RegionFile;

/** Writes a standard 1.8.9 inspection world, classic schematic and exact editor graph. */
public final class MapArchiveWriter {
    private MapArchiveWriter() {}

    public static Path write(MapSnapshot capture, Path directory, Consumer<String> progress)
            throws IOException {
        Files.createDirectories(directory);
        Path staging = Files.createTempDirectory(directory, ".capture-");
        Path partial = Files.createTempFile(directory, ".capture-", ".zip.tmp");
        Path destination =
                directory.resolve(
                        capture.name + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip");
        try {
            progress.accept("Writing offline world");
            writeWorld(capture, staging);
            progress.accept("Writing schematic");
            try (OutputStream output =
                    Files.newOutputStream(staging.resolve(capture.name + ".schematic"))) {
                CompressedStreamTools.writeCompressed(schematic(capture), output);
            }
            try (Writer output =
                    Files.newBufferedWriter(staging.resolve("map.json"), StandardCharsets.UTF_8)) {
                EditorMapWriter.write(capture, output, progress);
            }
            String readme =
                    "Optimus loaded-terrain export\n\n"
                            + "Extract this folder into a Minecraft 1.8.9 saves directory to inspect the world.\n"
                            + "Import this ZIP, map.json, or the schematic into the existing Optimus HTML workshop.\n"
                            + "Bounds: "
                            + capture.minX
                            + ","
                            + capture.minY
                            + ","
                            + capture.minZ
                            + " through "
                            + (capture.minX + capture.width - 1)
                            + ","
                            + (capture.minY + capture.height - 1)
                            + ","
                            + (capture.minZ + capture.length - 1)
                            + ".\n"
                            + "Only client-loaded terrain inside these bounds was captured. Outside terrain is empty.\n"
                            + "The capture spans multiple client ticks. Moving entities, player data and server-only chunks are excluded.\n"
                            + "Native collision geometry and ordinary routes are included; advanced pit-specific jump routines are not generated for a new map.";
            Files.write(staging.resolve("README.txt"), readme.getBytes(StandardCharsets.UTF_8));
            progress.accept("Compressing map archive");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(partial));
                    Stream<Path> paths = Files.walk(staging)) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    EditorMapWriter.interrupted();
                    ZipEntry entry =
                            new ZipEntry(staging.relativize(path).toString().replace('\\', '/'));
                    entry.setTime(0);
                    zip.putNextEntry(entry);
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
            EditorMapWriter.interrupted();
            try {
                Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(partial, destination);
            }
            return destination;
        } finally {
            Files.deleteIfExists(partial);
            try (Stream<Path> paths = Files.walk(staging)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    public static NBTTagCompound schematic(MapSnapshot capture) throws IOException {
        NBTTagCompound root = new NBTTagCompound();
        root.setShort("Width", (short) capture.width);
        root.setShort("Height", (short) capture.height);
        root.setShort("Length", (short) capture.length);
        root.setString("Materials", "Alpha");
        root.setInteger("WEOriginX", capture.minX);
        root.setInteger("WEOriginY", capture.minY);
        root.setInteger("WEOriginZ", capture.minZ);
        root.setInteger("WEOffsetX", 0);
        root.setInteger("WEOffsetY", 0);
        root.setInteger("WEOffsetZ", 0);
        byte[] blocks = new byte[capture.volume()],
                data = new byte[capture.volume()],
                add = new byte[(capture.volume() + 1) / 2];
        boolean extended = false;
        for (int i = 0; i < blocks.length; i++) {
            int state = capture.state(i), id = state >> 4;
            blocks[i] = (byte) id;
            data[i] = (byte) (state & 15);
            nibble(add, i, id >> 8);
            extended |= id > 255;
        }
        root.setByteArray("Blocks", blocks);
        root.setByteArray("Data", data);
        if (extended) root.setByteArray("AddBlocks", add);
        NBTTagList tiles = new NBTTagList();
        for (NBTTagCompound tile : tiles(capture)) {
            tile.setInteger("x", tile.getInteger("x") - capture.minX);
            tile.setInteger("y", tile.getInteger("y") - capture.minY);
            tile.setInteger("z", tile.getInteger("z") - capture.minZ);
            tiles.appendTag(tile);
        }
        root.setTag("TileEntities", tiles);
        root.setTag("Entities", new NBTTagList());
        return root;
    }

    private static void writeWorld(MapSnapshot capture, Path root) throws IOException {
        NBTTagCompound level = new NBTTagCompound(), data = new NBTTagCompound();
        level.setTag("Data", data);
        data.setInteger("version", 19133);
        data.setString("LevelName", capture.name);
        data.setInteger("GameType", 1);
        data.setBoolean("allowCommands", true);
        data.setBoolean("initialized", true);
        data.setString("generatorName", "flat");
        data.setInteger("generatorVersion", 0);
        data.setString("generatorOptions", "3;minecraft:air;1;");
        data.setInteger("SpawnX", capture.minX + capture.width / 2);
        data.setInteger("SpawnZ", capture.minZ + capture.length / 2);
        int spawnY = capture.minY + capture.height + 1;
        for (int y = capture.minY + capture.height - 1; y >= capture.minY; y--) {
            if (capture.state(
                            capture.index(data.getInteger("SpawnX"), y, data.getInteger("SpawnZ")))
                    != 0) {
                spawnY = y + 2;
                break;
            }
        }
        data.setInteger("SpawnY", spawnY);
        NBTTagCompound rules = new NBTTagCompound();
        rules.setString("doDaylightCycle", "false");
        rules.setString("doMobSpawning", "false");
        data.setTag("GameRules", rules);
        try (OutputStream output = Files.newOutputStream(root.resolve("level.dat"))) {
            CompressedStreamTools.writeCompressed(level, output);
        }
        Path regions = Files.createDirectories(root.resolve("region"));
        List<NBTTagCompound> tiles = tiles(capture);
        for (int cx = Math.floorDiv(capture.minX, 16);
                cx <= Math.floorDiv(capture.minX + capture.width - 1, 16);
                cx++) {
            for (int cz = Math.floorDiv(capture.minZ, 16);
                    cz <= Math.floorDiv(capture.minZ + capture.length - 1, 16);
                    cz++) {
                EditorMapWriter.interrupted();
                RegionFile region =
                        new RegionFile(
                                regions.resolve(
                                                "r."
                                                        + Math.floorDiv(cx, 32)
                                                        + "."
                                                        + Math.floorDiv(cz, 32)
                                                        + ".mca")
                                        .toFile());
                try (DataOutputStream output = region.getChunkDataOutputStream(cx & 31, cz & 31)) {
                    CompressedStreamTools.write(chunk(capture, cx, cz, tiles), output);
                } finally {
                    region.close();
                }
            }
        }
    }

    private static NBTTagCompound chunk(
            MapSnapshot capture, int cx, int cz, List<NBTTagCompound> tiles) {
        NBTTagCompound root = new NBTTagCompound(), level = new NBTTagCompound();
        root.setTag("Level", level);
        level.setInteger("xPos", cx);
        level.setInteger("zPos", cz);
        level.setBoolean("TerrainPopulated", true);
        level.setBoolean("LightPopulated", false);
        level.setByte("V", (byte) 1);
        NBTTagList sections = new NBTTagList();
        int[] heights = new int[256];
        for (int sy = 0; sy < 16; sy++) {
            byte[] ids = new byte[4096],
                    data = new byte[2048],
                    add = new byte[2048],
                    sky = new byte[2048];
            Arrays.fill(sky, (byte) 255);
            boolean used = false, extended = false;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int wx = cx * 16 + x, wy = sy * 16 + y, wz = cz * 16 + z;
                        if (!capture.contains(wx, wy, wz)) continue;
                        int state = capture.state(capture.index(wx, wy, wz)),
                                id = state >> 4,
                                index = y * 256 + z * 16 + x;
                        ids[index] = (byte) id;
                        nibble(data, index, state & 15);
                        nibble(add, index, id >> 8);
                        if (id != 0) {
                            used = true;
                            heights[z * 16 + x] = wy + 1;
                        }
                        extended |= id > 255;
                    }
            if (!used) continue;
            NBTTagCompound section = new NBTTagCompound();
            section.setByte("Y", (byte) sy);
            section.setByteArray("Blocks", ids);
            section.setByteArray("Data", data);
            section.setByteArray("BlockLight", new byte[2048]);
            section.setByteArray("SkyLight", sky);
            if (extended) section.setByteArray("Add", add);
            sections.appendTag(section);
        }
        NBTTagList chunkTiles = new NBTTagList();
        for (NBTTagCompound tile : tiles)
            if (tile.getInteger("x") >> 4 == cx && tile.getInteger("z") >> 4 == cz)
                chunkTiles.appendTag(tile);
        level.setTag("Sections", sections);
        level.setTag("TileEntities", chunkTiles);
        level.setTag("Entities", new NBTTagList());
        level.setIntArray("HeightMap", heights);
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 1);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int wx = cx * 16 + x, wz = cz * 16 + z;
                if (wx >= capture.minX
                        && wx < capture.minX + capture.width
                        && wz >= capture.minZ
                        && wz < capture.minZ + capture.length)
                    biomes[z * 16 + x] = (byte) capture.biome(wx, wz);
            }
        level.setByteArray("Biomes", biomes);
        return root;
    }

    private static List<NBTTagCompound> tiles(MapSnapshot capture) throws IOException {
        List<NBTTagCompound> result = new ArrayList<>();
        for (byte[] bytes : capture.tileData())
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                result.add(CompressedStreamTools.read(input));
            }
        return result;
    }

    private static void nibble(byte[] bytes, int index, int value) {
        bytes[index >> 1] |= (byte) ((value & 15) << ((index & 1) * 4));
    }
}