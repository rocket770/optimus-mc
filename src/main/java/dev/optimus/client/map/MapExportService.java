package dev.optimus.client.map;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.map.MapArchiveWriter;
import dev.optimus.map.MapSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

/** Bounded main-thread reads followed by background serialization of owned snapshot data. */
public final class MapExportService implements AutoCloseable {
    private final Minecraft mc;
    private final Path directory;
    private final Consumer<String> notice;
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(
                    task -> {
                        Thread thread = new Thread(task, "Optimus map writer");
                        thread.setDaemon(true);
                        return thread;
                    });
    private WorldClient world;
    private MapSnapshot.Builder capture;
    private int minX, minZ, width, length, cursor, boxCount;
    private Future<Path> pending;
    private AtomicReference<String> progress = new AtomicReference<>("No map export in progress");

    public MapExportService(Minecraft mc, Path directory, Consumer<String> notice) {
        this.mc = mc;
        this.directory = directory;
        this.notice = notice;
    }

    public void start(String name, int radius) {
        if (active())
            throw new IllegalArgumentException(
                    "A map export is already running. Use /o map status or /o map cancel.");
        if (mc.theWorld == null || mc.thePlayer == null)
            throw new IllegalArgumentException("Join a world before exporting terrain.");
        if (radius < 8 || radius > 64)
            throw new IllegalArgumentException("Map radius must be between 8 and 64 blocks.");
        WorldClient source = mc.theWorld;
        int x = (int) Math.floor(mc.thePlayer.posX), z = (int) Math.floor(mc.thePlayer.posZ);
        minX = Math.floorDiv(x - radius, 16) * 16;
        minZ = Math.floorDiv(z - radius, 16) * 16;
        width = (Math.floorDiv(x + radius, 16) + 1) * 16 - minX;
        length = (Math.floorDiv(z + radius, 16) + 1) * 16 - minZ;
        for (int cx = minX; cx < minX + width; cx += 16)
            for (int cz = minZ; cz < minZ + length; cz += 16) {
                if (!source.isBlockLoaded(new BlockPos(cx, 64, cz)))
                    throw new IllegalArgumentException(
                            "The requested area contains unloaded chunks. Load them or choose a smaller radius.");
            }
        capture = new MapSnapshot.Builder(name, minX, 0, minZ, width, 256, length);
        world = source;
        cursor = boxCount = 0;
        progress = new AtomicReference<>("Capturing loaded terrain: 0%");
        notice.accept(
                "Exporting "
                        + width
                        + " × "
                        + length
                        + " loaded blocks at all heights. /o map status shows progress.");
    }

    public boolean active() {
        return capture != null || pending != null;
    }

    public String status() {
        return progress.get();
    }

    /** Called only on a START client tick; workers never read Minecraft state. */
    public void tick() {
        if (capture != null) {
            if (mc.theWorld != world) {
                cancel();
                notice.accept("Map export cancelled: world changed.");
                return;
            }
            long started = System.nanoTime();
            int processed = 0, volume = width * 256 * length;
            try {
                while (cursor < volume
                        && processed++ < 4096
                        && System.nanoTime() - started < 2_000_000) readCell(cursor++);
                progress.set("Capturing loaded terrain: " + (cursor * 100L / volume) + "%");
                if (cursor == volume) {
                    MapSnapshot snapshot = capture.build();
                    capture = null;
                    world = null;
                    AtomicReference<String> reporting = progress;
                    pending =
                            writer.submit(
                                    () ->
                                            MapArchiveWriter.write(
                                                    snapshot, directory, reporting::set));
                }
            } catch (IOException | RuntimeException error) {
                cancel();
                notice.accept("Map export failed: " + error.getMessage());
            }
        }
        if (pending != null && pending.isDone()) {
            try {
                Path result = pending.get();
                progress.set("Map exported: " + result.getFileName());
                notice.accept("Map exported: " + result.toAbsolutePath());
            } catch (ExecutionException error) {
                progress.set("Map export failed");
                notice.accept("Map export failed: " + error.getCause().getMessage());
            } catch (CancellationException error) {
                progress.set("Map export cancelled");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                pending = null;
            }
        }
    }

    private void readCell(int index) throws IOException {
        int x = minX + index % width,
                y = index / (width * length),
                z = minZ + index / width % length;
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.isBlockLoaded(pos))
            throw new IOException(
                    "A captured chunk unloaded; export cancelled without writing a partial map");
        IBlockState state = world.getBlockState(pos);
        if (y == 0) capture.setBiome(x, z, world.getBiomeGenForCoords(pos).biomeID);
        Block block = state.getBlock();
        int id = Block.getIdFromBlock(block), metadata = block.getMetaFromState(state);
        List<CollisionGeometry.Box> boxes = new ArrayList<>();
        if (id != 0) {
            List<AxisAlignedBB> nativeBoxes = new ArrayList<>();
            block.addCollisionBoxesToList(
                    world,
                    pos,
                    state,
                    new AxisAlignedBB(x, y, z, x + 1, y + 2, z + 1),
                    nativeBoxes,
                    mc.thePlayer);
            boxCount += nativeBoxes.size();
            if (boxCount > 1_000_000)
                throw new IOException(
                        "Capture exceeds one million collision boxes; choose a smaller radius");
            for (AxisAlignedBB b : nativeBoxes)
                boxes.add(
                        new CollisionGeometry.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        boolean hazard =
                block.getMaterial().isLiquid()
                        || block == Blocks.fire
                        || block == Blocks.cactus
                        || block == Blocks.web
                        || block == Blocks.portal
                        || block == Blocks.end_portal
                        || block == Blocks.ladder
                        || block == Blocks.vine;
        capture.set(index, id << 4 | metadata, new CollisionGeometry.Cell(true, hazard, boxes));
        if (block.hasTileEntity(state)) {
            TileEntity tile = world.getTileEntity(pos);
            if (tile != null) {
                NBTTagCompound nbt = new NBTTagCompound();
                tile.writeToNBT(nbt);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                CompressedStreamTools.write(nbt, new DataOutputStream(bytes));
                capture.addTile(bytes.toByteArray());
            }
        }
    }

    public void cancel() {
        capture = null;
        world = null;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        progress.set("Map export cancelled");
    }

    @Override
    public void close() {
        cancel();
        writer.shutdownNow();
    }
}