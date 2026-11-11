package dev.optimus.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.ml.MovementAdvisor;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;
import dev.optimus.learning.LearningSession;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.FoodStats;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.EnumDifficulty;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import sun.misc.Unsafe;

/** Real END-tick combat, advisor, recorder and kill/death feed; no game socket or window. */
public final class LearningClientSelfTest {
    private static int checks;

    private static void expect(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        field(object.getClass(), name).set(object, value);
    }

    private static Object get(Object object, String name) throws Exception {
        return field(object.getClass(), name).get(object);
    }

    private static final class Player extends EntityPlayerSP {
        String label;
        UUID id;
        boolean sprint;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public String getName() {
            return label;
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }

        @Override
        public boolean isEntityAlive() {
            return true;
        }

        @Override
        public boolean isPotionActive(Potion potion) {
            return false;
        }

        @Override
        public boolean isInWater() {
            return false;
        }

        @Override
        public boolean isInLava() {
            return false;
        }

        @Override
        public boolean isSneaking() {
            return false;
        }

        @Override
        public boolean isSprinting() {
            return sprint;
        }

        @Override
        public void setSprinting(boolean value) {
            sprint = value;
        }

        @Override
        public float getAIMoveSpeed() {
            return sprint ? .13f : .1f;
        }

        @Override
        public float getEyeHeight() {
            return 1.62f;
        }

        @Override
        public void swingItem() {}

        @Override
        public boolean canEntityBeSeen(Entity target) {
            return true;
        }

        @Override
        public void sendChatMessage(String text) {}

        @Override
        public void addChatMessage(IChatComponent text) {}

        @Override
        public AxisAlignedBB getEntityBoundingBox() {
            return new AxisAlignedBB(posX - .3, posY, posZ - .3, posX + .3, posY + 1.8, posZ + .3);
        }
    }

    private static final class Controller extends PlayerControllerMP {
        int attacks;

        private Controller() {
            super(null, null);
        }

        @Override
        public void updateController() {}

        @Override
        public void attackEntity(EntityPlayer player, Entity target) {
            attacks++;
        }
    }

    private static final class Floor extends Block {
        private Floor() {
            super(Material.rock);
        }
    }

    private static final class World extends WorldClient {
        private static final IBlockState FLOOR = new Floor().getDefaultState();

        private World() {
            super(null, null, 0, EnumDifficulty.NORMAL, null);
        }

        @Override
        public boolean isBlockLoaded(BlockPos point) {
            return true;
        }

        @Override
        public IBlockState getBlockState(BlockPos point) {
            return FLOOR;
        }

        @Override
        public MovingObjectPosition rayTraceBlocks(Vec3 from, Vec3 to) {
            return null;
        }
    }

    public static void main(String[] arguments) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Field singleton = field(Minecraft.class, "theMinecraft");
        Object oldMinecraft = singleton.get(null);
        ClientSettings oldSettings = OptimusMod.settings;
        ClientEvents oldEvents = OptimusMod.events;
        EmbeddedChannel channel = null;
        LearningSession learning = null;
        CombatTrace trace = null;
        try {
            Path game = Files.createTempDirectory("optimus-learning-client-");
            Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            singleton.set(null, mc);
            mc.gameSettings = new GameSettings();
            set(mc, "mcDataDir", game.toFile());
            mc.theWorld = (World) unsafe.allocateInstance(World.class);
            Player bot = (Player) unsafe.allocateInstance(Player.class),
                    enemy = (Player) unsafe.allocateInstance(Player.class);
            int index = 0;
            for (Player player : Arrays.asList(bot, enemy)) {
                player.label = index++ == 0 ? "LearningBot" : "LearningEnemy";
                player.id = UUID.randomUUID();
                player.worldObj = mc.theWorld;
                DataWatcher data = new DataWatcher(player);
                data.addObject(6, Float.valueOf(20));
                data.addObject(17, Float.valueOf(0));
                data.addObject(0, Byte.valueOf((byte) 0));
                set(player, "dataWatcher", data);
                player.inventory = new InventoryPlayer(player);
                player.capabilities = new PlayerCapabilities();
                player.jumpMovementFactor = .02f;
                set(player, "foodStats", new FoodStats());
                player.posX = player.prevPosX = .5;
                player.posY = player.prevPosY = 1;
                player.posZ = player.prevPosZ = player == bot ? .5 : 2.5;
                player.serverPosX = 16;
                player.serverPosY = 32;
                player.serverPosZ = (int) (player.posZ * 32);
                player.onGround = true;
            }
            enemy.inventory.mainInventory[0] = new ItemStack(new ItemSword(Item.ToolMaterial.IRON));
            enemy.rotationYawHead = 180;
            enemy.isSwingInProgress = true;
            mc.thePlayer = bot;
            Controller controller = (Controller) unsafe.allocateInstance(Controller.class);
            mc.playerController = controller;
            set(
                    mc.theWorld,
                    "playerEntities",
                    new ArrayList<EntityPlayer>(Arrays.asList(bot, enemy)));
            set(
                    mc,
                    "currentServerData",
                    new ServerData("Disconnected fixture", "mc.hypixel.net", false));
            NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient handler =
                    (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
            set(handler, "netManager", manager);
            set(bot, "sendQueue", handler);
            HashMap<UUID, NetworkPlayerInfo> profiles = new HashMap<>();
            for (Player player : Arrays.asList(bot, enemy)) {
                NetworkPlayerInfo info =
                        new NetworkPlayerInfo(
                                new com.mojang.authlib.GameProfile(player.id, player.label));
                set(info, "responseTime", player == bot ? 250 : 90);
                profiles.put(player.id, info);
            }
            set(handler, "playerInfoMap", profiles);
            OptimusMod.settings = new ClientSettings(game.toFile());
            ClientSettings settings = OptimusMod.settings;
            settings.hypixelPit.setCornerA(new Vec3Point(-10, 0, -10));
            settings.hypixelPit.setCornerB(new Vec3Point(10, 10, 10));
            settings.pitEntrance = new Vec3Point(.5, 1, .5);
            settings.pitLobby = "duelslobby9";
            settings.pitInstance = "dynamiclobbyTEST";
            settings.targetAll = true;
            settings.swordEnabled = false;
            ClientEvents events = new ClientEvents();
            OptimusMod.events = events;
            LobbyNavigator navigator = (LobbyNavigator) get(events, "navigator");
            set(
                    navigator,
                    "geometry",
                    new CollisionGeometry(
                            (x, y, z) ->
                                    new CollisionGeometry.Cell(
                                            true,
                                            false,
                                            y == 0
                                                    ? Collections.singletonList(
                                                            new CollisionGeometry.Box(
                                                                    x, 0, z, x + 1, 1, z + 1))
                                                    : Collections.emptyList())));
            LobbySafety safety = (LobbySafety) get(events, "lobbySafety");
            TransitionWatch watch = new TransitionWatch(safety, manager);
            channel = new EmbeddedChannel(watch);
            set(manager, "channel", channel);
            set(events, "transitionWatch", watch);
            set(events, "watchedManager", manager);
            set(events, "sessionWorld", mc.theWorld);
            set(events, "sessionPlayer", bot);
            set(events, "armed", true);
            set(events, "enteredPit", true);
            set(events, "seenSerial", watch.serial());
            set(events, "seenHard", watch.hardSerial());
            set(events, "seenFatal", watch.fatalSerial());
            long now = System.nanoTime() / 1000000L;
            safety.request("mc.hypixel.net", mc.theWorld, now);
            safety.confirm(
                    "mc.hypixel.net",
                    mc.theWorld,
                    now,
                    "DUELS",
                    "duelslobby9",
                    "dynamiclobbyTEST",
                    false);
            RunSafety run = (RunSafety) get(events, "run");
            run.startChecking(now);
            run.activate("duelslobby9", "dynamiclobbyTEST", mc.theWorld);
            trace = new CombatTrace(game.resolve("trace").toFile());
            set(events, "trace", trace);
            learning = (LearningSession) get(events, "learning");
            learning.loggingCommand("on");
            learning.start();
            int[] advice = {0};
            set(
                    get(events, "combat"),
                    "advisor",
                    (MovementAdvisor)
                            (tick, features, baseline, safe) -> {
                                expect(
                                        features.length == 16,
                                        "controller supplies exact model feature shape");
                                advice[0]++;
                                return baseline;
                            });
            for (int tick = 0; tick < 12; tick++)
                events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            expect(
                    advice[0] > 0 && controller.attacks > 0,
                    "the real combat loop consults the advisor and retains native attacks");
            String kill = enemy.label + " was killed by " + bot.label + "! 1 KILL STREAK!";
            events.onChat(new ClientChatReceivedEvent((byte) 0, new ChatComponentText(kill)));
            events.onChat(new ClientChatReceivedEvent((byte) 0, new ChatComponentText(kill)));
            bot.posX = -12;
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            learning.close();
            Path capture;
            try (java.util.stream.Stream<Path> files =
                    Files.list(game.resolve("config/optimus-ml-data/fights"))) {
                capture = files.findFirst().get();
            }
            String data = new String(Files.readAllBytes(capture), StandardCharsets.UTF_8);
            expect(
                    !data.contains(bot.label)
                            && !data.contains(enemy.label)
                            && !data.contains("mc.hypixel.net"),
                    "native recordings omit account names and server identity");
            expect(
                    data.contains("policy_features") && data.contains("\"policy_eligible\":true"),
                    "real eligible frames retain exact runtime features");
            expect(
                    data.contains("\"ping_ms\":250")
                            && data.contains("\"opponent_ping_ms_observed\":90"),
                    "native decisions retain both reported pings");
            expect(
                    data.contains("\"attack_requested\":true") && data.contains("yaw_error_after"),
                    "actual requests and aim observations reach the writer");
            int kills = 0, deaths = 0;
            for (String line : data.split("\\r?\\n")) {
                JsonObject row = new JsonParser().parse(line).getAsJsonObject();
                if (row.get("type").getAsString().equals("combat_outcome")
                        && row.get("outcome").getAsString().equals("kill")) kills++;
                if (row.get("type").getAsString().equals("life_end")
                        && row.get("death").getAsBoolean()) deaths++;
            }
            expect(
                    kills == 1 && deaths == 1,
                    "server feed deduplicates kills and native alive pit return records one death");
            String fixture = System.getProperty("optimus.learningFixture");
            if (fixture != null)
                Files.write(
                        java.nio.file.Paths.get(fixture),
                        data.replaceAll("[\\r\\n]+$", "").getBytes(StandardCharsets.UTF_8));
        } finally {
            if (learning != null) learning.close();
            if (trace != null) trace.close();
            if (channel != null) channel.finish();
            singleton.set(null, oldMinecraft);
            OptimusMod.settings = oldSettings;
            OptimusMod.events = oldEvents;
        }
        System.out.println(
                "PASS: " + checks + " native learning advisor, recorder, ping and outcome checks");
    }
}