package dev.optimus.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.nbt.*;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.storage.WorldInfo;

/** Validate the delivered map with Minecraft's own NBT, world-info and region readers. */
public final class PitExportSelfTest {
    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static void expect(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        Path build = Paths.get("build");
        Files.createDirectories(build);
        Path extracted = Files.createTempDirectory(build, "pit-export-");
        try {
            try (ZipFile archive = new ZipFile("exports/Hypixel-Duels-Pit-2026-10-15.zip")) {
                for (ZipEntry entry : Collections.list(archive.entries())) {
                    if (entry.isDirectory()) continue;
                    Path destination = extracted.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(extracted))
                        throw new IOException("Invalid example archive path");
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = archive.getInputStream(entry)) {
                        Files.copy(input, destination);
                    }
                }
            }
            verify(extracted.resolve("Hypixel-Duels-Pit-2026-10-15").toFile());
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(extracted)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void verify(File folder) throws Exception {
        Map<String, Integer> source = new HashMap<>(), restored = new HashMap<>();
        try (BufferedReader in =
                new BufferedReader(
                        new InputStreamReader(
                                new GZIPInputStream(
                                        PitExportSelfTest.class.getResourceAsStream(
                                                "pit-capture/pit-blocks.tsv.gz")),
                                StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null)
                if (line.startsWith("S ")) {
                    String[] p = line.split(" ");
                    source.put(
                            p[1] + ":" + p[2] + ":" + p[3],
                            Integer.parseInt(p[4]) << 4 | Integer.parseInt(p[5]));
                }
        }
        NBTTagCompound level;
        try (InputStream in = new FileInputStream(new File(folder, "level.dat"))) {
            level = CompressedStreamTools.readCompressed(in).getCompoundTag("Data");
        }
        WorldInfo info = new WorldInfo(level);
        expect(
                info.getSaveVersion() == 19133
                        && info.getSpawnY() == 80
                        && info.getSpawnZ() == -103,
                "native world-info reads the 1.8.9 save and original-coordinate spawn");
        expect(
                info.getGameType().isCreative() && info.areCommandsAllowed(),
                "export opens in Creative inspection mode");
        int chunks = 0, tiles = 0;
        for (File file : new File(folder, "region").listFiles())
            if (file.getName().endsWith(".mca")) {
                RegionFile region = new RegionFile(file);
                try {
                    for (int x = 0; x < 32; x++)
                        for (int z = 0; z < 32; z++) {
                            DataInputStream in = region.getChunkDataInputStream(x, z);
                            if (in == null) continue;
                            NBTTagCompound chunk;
                            try {
                                chunk = CompressedStreamTools.read(in).getCompoundTag("Level");
                            } finally {
                                in.close();
                            }
                            chunks++;
                            int cx = chunk.getInteger("xPos"), cz = chunk.getInteger("zPos");
                            NBTTagList sections = chunk.getTagList("Sections", 10);
                            tiles += chunk.getTagList("TileEntities", 10).tagCount();
                            for (int i = 0; i < sections.tagCount(); i++) {
                                NBTTagCompound s = sections.getCompoundTagAt(i);
                                int sy = s.getByte("Y");
                                byte[] ids = s.getByteArray("Blocks"),
                                        metadata = s.getByteArray("Data");
                                expect(
                                        ids.length == 4096 && metadata.length == 2048,
                                        "native section block/data array lengths");
                                for (int at = 0; at < ids.length; at++)
                                    if (ids[at] != 0) {
                                        int bid = ids[at] & 255,
                                                meta =
                                                        (metadata[at / 2] & 255) >> ((at & 1) * 4)
                                                                & 15;
                                        restored.put(
                                                key(
                                                        cx * 16 + (at & 15),
                                                        sy * 16 + (at >> 8),
                                                        cz * 16 + (at >> 4 & 15)),
                                                bid << 4 | meta);
                                    }
                            }
                        }
                } finally {
                    region.close();
                }
            }
        expect(
                restored.equals(source),
                "native Anvil/NBT reader reproduces every captured block and metadata");
        NBTTagCompound schematic;
        try (InputStream in =
                new FileInputStream("exports/Hypixel-Duels-Pit-2026-10-15.schematic")) {
            schematic = CompressedStreamTools.readCompressed(in);
        }
        int width = schematic.getShort("Width"),
                height = schematic.getShort("Height"),
                length = schematic.getShort("Length");
        byte[] ids = schematic.getByteArray("Blocks"), metadata = schematic.getByteArray("Data");
        expect(
                width == 55
                        && height == 23
                        && length == 55
                        && ids.length == width * height * length,
                "native reader accepts complete schematic dimensions");
        int nonAir = 0;
        for (int at = 0; at < ids.length; at++)
            if (ids[at] != 0) {
                int x = at % width - 27,
                        z = at / width % length - 129,
                        y = at / (width * length) + 57;
                nonAir++;
                expect(
                        source.get(key(x, y, z)) == ((ids[at] & 255) << 4 | (metadata[at] & 15)),
                        "schematic block state matches capture at original coordinates");
            }
        expect(
                nonAir == source.size()
                        && tiles == 106
                        && schematic.getTagList("TileEntities", 10).tagCount() == 106,
                "both exports retain every block and existing sign");
        System.out.println(
                "PASS: Minecraft native readers verified "
                        + source.size()
                        + " blocks/metadata, "
                        + tiles
                        + " signs and "
                        + chunks
                        + " Anvil chunks in delivered pit exports");
    }
}