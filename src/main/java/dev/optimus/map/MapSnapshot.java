package dev.optimus.map;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An owned, immutable terrain capture. No world or player objects cross the writer boundary. */
public final class MapSnapshot {
    public static final int MAX_CELLS = 6_000_000;
    public final String name;
    public final int minX, minY, minZ, width, height, length;
    private final char[] states;
    private final byte[] biomes;
    private final Map<Integer, CollisionGeometry.Cell> cells;
    private final List<byte[]> tiles;

    private MapSnapshot(Builder builder) {
        name = builder.name;
        minX = builder.minX;
        minY = builder.minY;
        minZ = builder.minZ;
        width = builder.width;
        height = builder.height;
        length = builder.length;
        states = builder.states;
        biomes = builder.biomes;
        cells = Collections.unmodifiableMap(builder.cells);
        tiles = Collections.unmodifiableList(builder.tiles);
        builder.states = null;
    }

    public int volume() {
        return states.length;
    }

    public int x(int index) {
        return minX + index % width;
    }

    public int y(int index) {
        return minY + index / (width * length);
    }

    public int z(int index) {
        return minZ + index / width % length;
    }

    public int state(int index) {
        return states[index];
    }

    public int biome(int x, int z) {
        return biomes[(z - minZ) * width + x - minX] & 255;
    }

    public Map<Integer, CollisionGeometry.Cell> cells() {
        return cells;
    }

    public List<byte[]> tileData() {
        List<byte[]> result = new ArrayList<>();
        for (byte[] tile : tiles) result.add(tile.clone());
        return result;
    }

    public int index(int x, int y, int z) {
        return (y - minY) * width * length + (z - minZ) * width + x - minX;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX
                && x < minX + width
                && y >= minY
                && y < minY + height
                && z >= minZ
                && z < minZ + length;
    }

    public CollisionGeometry.Cell cell(int x, int y, int z) {
        if (x < minX || x >= minX + width || z < minZ || z >= minZ + length || y < minY)
            return CollisionGeometry.Cell.UNLOADED;
        if (y >= minY + height) return CollisionGeometry.Cell.AIR;
        return cells.getOrDefault(index(x, y, z), CollisionGeometry.Cell.AIR);
    }

    public CollisionGeometry geometry() {
        return new CollisionGeometry(this::cell);
    }

    public PitRegion area() {
        PitRegion area = new PitRegion();
        area.setCornerA(new Vec3Point(minX, minY, minZ));
        area.setCornerB(new Vec3Point(minX + width, minY + height + 2, minZ + length));
        return area;
    }

    public String hash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataOutputStream out =
                    new DataOutputStream(
                            new DigestOutputStream(
                                    new OutputStream() {
                                        @Override
                                        public void write(int value) {}

                                        @Override
                                        public void write(byte[] bytes, int offset, int count) {}
                                    },
                                    digest));
            out.writeInt(minX);
            out.writeInt(minY);
            out.writeInt(minZ);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(length);
            for (int i = 0; i < states.length; i++) {
                out.writeChar(states[i]);
                CollisionGeometry.Cell cell = cells.get(i);
                out.writeBoolean(cell != null && cell.hazard);
                out.writeInt(cell == null ? 0 : cell.boxes.size());
                if (cell != null)
                    for (CollisionGeometry.Box box : cell.boxes) {
                        out.writeDouble(box.minX);
                        out.writeDouble(box.minY);
                        out.writeDouble(box.minZ);
                        out.writeDouble(box.maxX);
                        out.writeDouble(box.maxY);
                        out.writeDouble(box.maxZ);
                    }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest())
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException | IOException error) {
            throw new IllegalStateException("Cannot hash map capture", error);
        }
    }

    public static final class Builder {
        private final String name;
        private final int minX, minY, minZ, width, height, length;
        private char[] states;
        private final byte[] biomes;
        private final Map<Integer, CollisionGeometry.Cell> cells = new LinkedHashMap<>();
        private final List<byte[]> tiles = new ArrayList<>();

        public Builder(
                String name, int minX, int minY, int minZ, int width, int height, int length) {
            long volume = (long) width * height * length;
            if (name == null
                    || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")
                    || width < 1
                    || height < 1
                    || minY < 0
                    || minY + height > 256
                    || Math.abs((long) minX) > 30_000_000
                    || Math.abs((long) minZ) > 30_000_000
                    || length < 1
                    || width > 32767
                    || length > 32767
                    || height > 256
                    || volume > MAX_CELLS)
                throw new IllegalArgumentException("Invalid map name or capture dimensions");
            this.name = name;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.width = width;
            this.height = height;
            this.length = length;
            states = new char[(int) volume];
            biomes = new byte[width * length];
            java.util.Arrays.fill(biomes, (byte) 1);
        }

        public void setBiome(int x, int z, int biome) {
            if (states == null) throw new IllegalStateException("Capture already handed to writer");
            if (x < minX
                    || x >= minX + width
                    || z < minZ
                    || z >= minZ + length
                    || biome < 0
                    || biome > 255) throw new IllegalArgumentException("Invalid captured biome");
            biomes[(z - minZ) * width + x - minX] = (byte) biome;
        }

        public void set(int index, int state, CollisionGeometry.Cell cell) {
            if (states == null) throw new IllegalStateException("Capture already handed to writer");
            if (index < 0 || index >= states.length || state < 0 || state > 65535 || !cell.loaded)
                throw new IllegalArgumentException("Invalid or unloaded captured cell");
            states[index] = (char) state;
            if (cell.hazard || !cell.boxes.isEmpty())
                cells.put(
                        index,
                        new CollisionGeometry.Cell(
                                true,
                                cell.hazard,
                                Collections.unmodifiableList(new ArrayList<>(cell.boxes))));
            else cells.remove(index);
        }

        public void addTile(byte[] nbt) {
            if (states == null) throw new IllegalStateException("Capture already handed to writer");
            tiles.add(nbt.clone());
        }

        public MapSnapshot build() {
            if (states == null) throw new IllegalStateException("Capture already handed to writer");
            return new MapSnapshot(this);
        }
    }
}