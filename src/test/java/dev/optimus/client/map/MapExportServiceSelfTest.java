package dev.optimus.client.map;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import sun.misc.Unsafe;

/** An isolated client shell proves main-thread capture, chunk checks and transfer cancellation. */
public final class MapExportServiceSelfTest {
    private static int checks;

    private static void expect(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static final class World extends WorldClient {
        Thread owner;
        boolean loaded;
        int reads;

        private World() {
            super(null, null, 0, EnumDifficulty.NORMAL, null);
        }

        private void mainThread() {
            if (Thread.currentThread() != owner)
                throw new AssertionError("Worker read live Minecraft state");
        }

        @Override
        public boolean isBlockLoaded(BlockPos pos) {
            mainThread();
            return loaded;
        }

        @Override
        public net.minecraft.world.biome.BiomeGenBase getBiomeGenForCoords(BlockPos pos) {
            mainThread();
            return net.minecraft.world.biome.BiomeGenBase.plains;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            mainThread();
            reads++;
            return (pos.getY() == 0 ? Blocks.stone : Blocks.air).getDefaultState();
        }
    }

    private static void rejects(Runnable action, String why) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        expect(rejected, why);
    }

    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        Class<?> loader = Class.forName("net.minecraftforge.fml.common.Loader");
        Field instance = loader.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, unsafe.allocateInstance(loader));
        Bootstrap.register();
        Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        World world = (World) unsafe.allocateInstance(World.class);
        world.owner = Thread.currentThread();
        world.loaded = true;
        mc.theWorld = world;
        mc.thePlayer = (EntityPlayerSP) unsafe.allocateInstance(EntityPlayerSP.class);
        mc.thePlayer.posX = .5;
        mc.thePlayer.posZ = .5;
        Path output = Files.createTempDirectory("optimus-map-capture-test-");
        List<String> notices = new ArrayList<>();
        try (MapExportService service = new MapExportService(mc, output, notices::add)) {
            rejects(
                    () -> service.start("../escape", 8),
                    "capture names cannot escape the output directory");
            rejects(() -> service.start("map", 1000), "capture radius is bounded");
            world.loaded = false;
            rejects(
                    () -> service.start("unloaded", 8),
                    "unloaded chunks are rejected without loading them");
            world.loaded = true;
            service.start("transfer", 8);
            service.tick();
            expect(
                    world.reads > 0 && world.reads <= 4096,
                    "one tick reads a bounded slice of terrain");
            mc.theWorld = (World) unsafe.allocateInstance(World.class);
            service.tick();
            expect(
                    !service.active()
                            && notices.stream().anyMatch(text -> text.contains("world changed")),
                    "world transfer cancels capture instead of mixing worlds");
            mc.theWorld = world;
            world.reads = 0;
            service.start("complete", 8);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (service.active() && System.nanoTime() < deadline) {
                service.tick();
                Thread.sleep(1);
            }
            expect(
                    !service.active() && service.status().startsWith("Map exported:"),
                    "background writing completes from an owned snapshot: " + service.status());
            expect(
                    world.reads == 32 * 32 * 256,
                    "capture visits every cell in the selected loaded chunk rectangle exactly once");
            try (java.util.stream.Stream<Path> files = Files.list(output)) {
                expect(
                        files.filter(path -> path.toString().endsWith(".zip")).count() == 1,
                        "only the complete capture publishes an archive");
            }
            service.start("cancelled", 8);
            service.cancel();
            expect(!service.active(), "explicit cancellation releases the capture");
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " live-world capture ownership, limits, transfer and completion checks");
    }
}