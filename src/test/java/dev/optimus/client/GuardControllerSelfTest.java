package dev.optimus.client;

import dev.optimus.client.guard.GuardController;
import dev.optimus.client.guard.GuardNavigation;
import dev.optimus.client.guard.GuardTestInputs;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardAssist;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.guard.GuardSwingSource;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.SprintGap;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.*;
import net.minecraft.entity.player.*;
import net.minecraft.item.*;
import net.minecraft.network.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.world.EnumDifficulty;
import sun.misc.Unsafe;

/** Disconnected native client: real guard controller, key ownership and attack adapter. */
public final class GuardControllerSelfTest {
    private static int checks;
    private static boolean wall;

    private static void expect(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object object, String name, Object value) throws Exception {
        if (object instanceof GuardController) object = GuardTestInputs.owner(object, name);
        field(object.getClass(), name).set(object, value);
    }

    private static Object get(Object object, String name) throws Exception {
        if (object instanceof GuardController) object = GuardTestInputs.owner(object, name);
        return field(object.getClass(), name).get(object);
    }

    private static final class Player extends EntityPlayerSP {
        String name, reply;
        UUID id;
        boolean sprint;
        int swings, replyCount, localMessages;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public String getName() {
            return name;
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
        public void addChatMessage(IChatComponent message) {
            localMessages++;
        }

        @Override
        public void sendChatMessage(String text) {
            reply = text;
            replyCount++;
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
        public void swingItem() {
            swings++;
        }

        @Override
        public AxisAlignedBB getEntityBoundingBox() {
            return new AxisAlignedBB(posX - .3, posY, posZ - .3, posX + .3, posY + 1.8, posZ + .3);
        }
    }

    private static final class Controller extends PlayerControllerMP {
        int attacks;
        Entity last;

        private Controller() {
            super(null, null);
        }

        @Override
        public void updateController() {}

        @Override
        public void attackEntity(EntityPlayer self, Entity target) {
            attacks++;
            last = target;
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
        public boolean isBlockLoaded(BlockPos position) {
            return true;
        }

        @Override
        public IBlockState getBlockState(BlockPos position) {
            return FLOOR;
        }

        @Override
        public MovingObjectPosition rayTraceBlocks(Vec3 a, Vec3 b) {
            return wall ? new MovingObjectPosition(b, EnumFacing.NORTH, new BlockPos(b)) : null;
        }
    }

    private static void position(Player p, double x, double z) {
        p.posX = p.prevPosX = x;
        p.posY = p.prevPosY = 1;
        p.posZ = p.prevPosZ = z;
        p.onGround = true;
        p.serverPosX = (int) Math.floor(x * 32);
        p.serverPosY = 32;
        p.serverPosZ = (int) Math.floor(z * 32);
    }

    private static void swing(
            GuardController guard, Player leader, Player rival, PitRegion pit, long tick)
            throws Exception {
        Method observe =
                get(guard, "combat")
                        .getClass()
                        .getDeclaredMethod(
                                "observeSwing",
                                String.class,
                                String.class,
                                PitRegion.class,
                                long.class,
                                GuardSwingSource.class);
        observe.setAccessible(true);
        observe.invoke(
                get(guard, "combat"),
                "swing-" + tick,
                rival == null ? "" : rival.id.toString(),
                pit,
                tick,
                GuardSwingSource.SERVER_OBSERVED);
    }

    private static void step(ClientEvents events, GuardController guard, PitRegion pit, long tick)
            throws Exception {
        set(events, "tickNo", tick);
        guard.step(pit, tick);
    }

    private static boolean remote(ClientEvents events, String sender, String command)
            throws Exception {
        ChatComponentText header = new ChatComponentText(sender);
        header.setChatStyle(
                new ChatStyle()
                        .setChatClickEvent(
                                new net.minecraft.event.ClickEvent(
                                        net.minecraft.event.ClickEvent.Action.RUN_COMMAND,
                                        "/socialoptions " + sender)));
        header.appendText(": !o " + command);
        Method receive =
                ClientEvents.class.getDeclaredMethod(
                        "remoteChat", IChatComponent.class, byte.class);
        receive.setAccessible(true);
        return (boolean) receive.invoke(events, header, (byte) 0);
    }

    public static void main(String[] arguments) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Field singleton = field(Minecraft.class, "theMinecraft");
        Object old = singleton.get(null);
        ClientSettings oldSettings = OptimusMod.settings;
        ClientEvents oldEvents = OptimusMod.events;
        boolean oldHints = OptimusMod.routeHintsEnabled;
        EmbeddedChannel channel = null;
        GuardController guard = null;
        dev.optimus.learning.LearningSession logging = null;
        try {
            Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            singleton.set(null, mc);
            mc.gameSettings = new GameSettings();
            mc.theWorld = (World) unsafe.allocateInstance(World.class);
            set(mc, "mcDataDir", Files.createTempDirectory("guard-game").toFile());
            Player bot = (Player) unsafe.allocateInstance(Player.class),
                    leader = (Player) unsafe.allocateInstance(Player.class),
                    rival = (Player) unsafe.allocateInstance(Player.class);
            int index = 0;
            for (Player p : Arrays.asList(bot, leader, rival)) {
                p.name = new String[] {"GuardBot", "rocket770", "Opponent"}[index++];
                p.id =
                        p.name.equals("rocket770")
                                ? UUID.fromString(ClientSettings.DEFAULT_CONTROLLER_UUID)
                                : UUID.randomUUID();
                p.worldObj = mc.theWorld;
                DataWatcher watcher = new DataWatcher(p);
                watcher.addObject(6, Float.valueOf(20));
                watcher.addObject(17, Float.valueOf(0));
                watcher.addObject(0, Byte.valueOf((byte) 0));
                set(p, "dataWatcher", watcher);
                p.inventory = new InventoryPlayer(p);
                p.inventory.mainInventory[0] = new ItemStack(new ItemSword(Item.ToolMaterial.IRON));
                p.capabilities = new PlayerCapabilities();
                p.jumpMovementFactor = .02f;
                set(p, "foodStats", new FoodStats());
                position(p, 0, 0);
            }
            mc.thePlayer = bot;
            Controller controller = (Controller) unsafe.allocateInstance(Controller.class);
            mc.playerController = controller;
            set(
                    mc.theWorld,
                    "playerEntities",
                    new ArrayList<EntityPlayer>(Arrays.asList(bot, leader, rival)));
            set(mc, "currentServerData", new ServerData("Offline", "mc.hypixel.net", false));
            NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient handler =
                    (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
            set(handler, "netManager", manager);
            set(bot, "sendQueue", handler);
            Map<UUID, net.minecraft.client.network.NetworkPlayerInfo> profiles = new HashMap<>();
            profiles.put(
                    leader.id,
                    new net.minecraft.client.network.NetworkPlayerInfo(
                            new com.mojang.authlib.GameProfile(leader.id, leader.name)));
            set(handler, "playerInfoMap", profiles);
            ClientSettings settings =
                    new ClientSettings(Files.createTempDirectory("guard-settings").toFile());
            OptimusMod.settings = settings;
            settings.guardName = leader.name;
            settings.guardUuid = leader.id.toString();
            settings.targetAll = true;
            settings.whitelist.add(leader.name);
            PitRegion pit = settings.hypixelPit;
            pit.setCornerA(new Vec3Point(-20, 0, -20));
            pit.setCornerB(new Vec3Point(20, 10, 20));
            settings.pitLobby = "duelslobby9";
            settings.pitInstance = "dynamiclobbyTEST";
            settings.pitEntrance = new Vec3Point(0, 1, 0);
            ClientEvents events = new ClientEvents();
            ((Random) get(events, "patrolRandom")).setSeed(731);
            guard = (GuardController) get(events, "guard");
            set(guard, "listening", true);
            set(events, "locationRoundTrip", 250);
            LobbyNavigator navigator = (LobbyNavigator) get(events, "navigator");
            final Map<String, Integer> geometryReads = new HashMap<>();
            CollisionGeometry floor =
                    new CollisionGeometry(
                            (x, y, z) -> {
                                String cell = x + ":" + y + ":" + z;
                                geometryReads.put(cell, geometryReads.getOrDefault(cell, 0) + 1);
                                return new CollisionGeometry.Cell(
                                        true,
                                        false,
                                        y == 0
                                                ? Collections.singletonList(
                                                        new CollisionGeometry.Box(
                                                                x, 0, z, x + 1, 1, z + 1))
                                                : Collections.emptyList());
                            });
            set(navigator, "geometry", floor);
            LobbySafety safety = (LobbySafety) get(events, "lobbySafety");
            TransitionWatch watch = new TransitionWatch(safety, manager);
            channel = new EmbeddedChannel(watch);
            set(manager, "channel", channel);
            set(events, "transitionWatch", watch);
            set(events, "sessionWorld", mc.theWorld);
            set(events, "sessionPlayer", bot);
            set(events, "armed", true);
            long now = System.nanoTime() / 1000000L;
            safety.request("mc.hypixel.net", mc.theWorld, now);
            expect(
                    safety.confirm(
                            "mc.hypixel.net",
                            mc.theWorld,
                            now,
                            "DUELS",
                            "duelslobby9",
                            "dynamiclobbyTEST",
                            false),
                    "real lobby verification");
            expect(
                    events.guardCommand(new String[] {"guard", leader.name})
                            .startsWith("Guarding rocket770"),
                    "guard command binds current server profile");
            expect(
                    settings.guardUuid.equals(leader.id.toString())
                            && (boolean) get(events, "startRequested"),
                    "choosing guard target pins UUID and automatically queues a start");
            OptimusMod.events = events;
            UUID helper = UUID.randomUUID();
            settings.operators.put("Helper_One", helper.toString());
            profiles.put(
                    helper,
                    new net.minecraft.client.network.NetworkPlayerInfo(
                            new com.mojang.authlib.GameProfile(helper, "Helper_One")));
            profiles.put(
                    rival.id,
                    new net.minecraft.client.network.NetworkPlayerInfo(
                            new com.mojang.authlib.GameProfile(rival.id, rival.name)));
            expect(
                    remote(events, "Helper_One", "@GuardBot guard Opponent")
                            && settings.guardUuid.equals(rival.id.toString())
                            && (boolean) get(events, "startRequested")
                            && bot.reply.equals("Optimus: Guarding Opponent."),
                    "authenticated operator can replace the single guard and receives a brief concrete reply");
            String oldKeys = settings.guardKeys;
            for (String denied :
                    new String[] {
                        "guard keys on",
                        "guard keys off",
                        "guard keys auto",
                        "GUARD KEYS OFF",
                        "guard keys"
                    }) {
                int before = bot.replyCount;
                expect(
                        remote(events, "Helper_One", denied)
                                && settings.guardKeys.equals(oldKeys)
                                && settings.guardUuid.equals(rival.id.toString())
                                && bot.replyCount == before + 1
                                && bot.reply.equals("Optimus: Controller only."),
                        "operator key request is rejected before changing guard settings: "
                                + denied);
            }
            expect(
                    remote(events, leader.name, "guard keys off")
                            && settings.guardKeys.equals("off")
                            && bot.reply.equals("Optimus: Guard keys: off."),
                    "authenticated controller retains guard key control with a brief acknowledgement");
            expect(
                    remote(events, "Helper_One", "guard off")
                            && !guard.enabled()
                            && (boolean) get(events, "armed")
                            && bot.reply.equals("Optimus: Guard off."),
                    "operator can disable guard without an unsolicited instruction paragraph");
            expect(
                    remote(events, "Helper_One", "guard rocket770")
                            && settings.guardUuid.equals(leader.id.toString()),
                    "operator can choose the original guard again");
            int beforeDiagnostics = bot.localMessages;
            expect(
                    remote(events, "Helper_One", "guard status")
                            && bot.reply.equals("Optimus: Guard: rocket770 | paused")
                            && bot.localMessages == beforeDiagnostics,
                    "remote guard status omits local timing and key diagnostics");
            int beforeStatus = bot.replyCount;
            expect(
                    remote(events, leader.name, "status")
                            && bot.replyCount == beforeStatus + 1
                            && bot.reply.length() <= 100
                            && !bot.reply.contains("...")
                            && bot.localMessages == beforeDiagnostics,
                    "remote status is one concise complete reply");
            logging = (dev.optimus.learning.LearningSession) get(events, "learning");
            logging.start();
            set(events, "armed", true);
            boolean wasArmed = (boolean) get(events, "armed");
            expect(
                    remote(events, leader.name, "logging on")
                            && logging.recording().active()
                            && bot.reply.equals("Optimus: Logging on.")
                            && (boolean) get(events, "armed") == wasArmed
                            && (boolean) get(events, "startRequested")
                            && settings.guardUuid.equals(leader.id.toString()),
                    "controller logging command starts recording immediately without changing guard or run intent");
            for (String denied : new String[] {"logging off", "record off"})
                expect(
                        remote(events, "Helper_One", denied)
                                && logging.recording().active()
                                && bot.reply.equals("Optimus: Controller only."),
                        "operators cannot disable recording through either command");
            expect(
                    remote(events, leader.name, "logging on extra")
                            && logging.recording().active()
                            && bot.reply.equals("Optimus: Usage: /o logging on|off|status"),
                    "malformed logging commands do not change the recorder");
            expect(
                    remote(events, leader.name, "record off")
                            && !logging.recording().active()
                            && bot.reply.equals("Optimus: Logging off.")
                            && (boolean) get(events, "armed") == wasArmed
                            && settings.guardUuid.equals(leader.id.toString()),
                    "record alias finalizes logging without stopping guard");
            expect(
                    remote(events, leader.name, "logging")
                            && bot.reply.equals("Optimus: Logging off."),
                    "bare logging command reports a brief status");
            new OptimusCommand().processCommand(bot, new String[] {"record", "on"});
            expect(
                    logging.loggingCommand("status").startsWith("Logging on")
                            && logging.recording().active(),
                    "local in-game recording alias shares the live command implementation");
            new OptimusCommand().processCommand(bot, new String[] {"logging", "off"});
            logging.close();
            expect(
                    new OptimusCommand()
                            .addTabCompletionOptions(bot, new String[] {"logging", "o"}, null)
                            .containsAll(Arrays.asList("on", "off")),
                    "logging options are available through tab completion");
            settings.operators.put("Helper_One", UUID.randomUUID().toString());
            int beforeSpoof = bot.replyCount;
            expect(
                    !remote(events, "Helper_One", "guard Opponent")
                            && bot.replyCount == beforeSpoof
                            && settings.guardUuid.equals(leader.id.toString()),
                    "guard permission still requires the paired operator UUID");
            settings.operators.remove("Helper_One");
            set(events, "startRequested", false);
            set(events, "sessionWorld", mc.theWorld);
            set(events, "sessionPlayer", bot);
            set(events, "armed", true);
            set(guard, "listening", true);
            RunSafety run = (RunSafety) get(events, "run");
            run.startChecking(now);
            expect(
                    run.activate("duelslobby9", "dynamiclobbyTEST", mc.theWorld),
                    "real run activation");
            logging.start();
            logging.loggingCommand("on");
            Object session = get(get(logging, "recorder"), "session");
            OptimusCommand commands = new OptimusCommand();
            long originalSeed = settings.combatSeed;
            boolean originalHints = OptimusMod.routeHintsEnabled;
            for (String[] change :
                    new String[][] {
                        {"guard", "off"},
                        {"guard", leader.name},
                        {"profile", "paced"},
                        {"profile", "balanced"},
                        {"seed", "123"},
                        {"routes", "off"},
                        {"routes", "on"}
                    }) {
                commands.processCommand(bot, change);
                expect(
                        (boolean) get(events, "armed")
                                && run.phase() == RunSafety.Phase.ACTIVE
                                && !(boolean) get(events, "startRequested")
                                && logging.recording().active()
                                && get(get(logging, "recorder"), "session") == session,
                        "changing "
                                + Arrays.toString(change)
                                + " preserves active control and the recording session");
            }
            commands.processCommand(bot, new String[] {"seed", Long.toString(originalSeed)});
            OptimusMod.routeHintsEnabled = originalHints;
            events.requestLocraw();
            expect(
                    (boolean) get(events, "armed")
                            && run.phase() == RunSafety.Phase.ACTIVE
                            && safety.pending()
                            && get(get(logging, "recorder"), "session") == session,
                    "location refresh pauses verification without ending the run or recording");
            long verifiedNow = System.nanoTime() / 1000000L;
            expect(
                    safety.confirm(
                            "mc.hypixel.net",
                            mc.theWorld,
                            verifiedNow,
                            "DUELS",
                            "duelslobby9",
                            "dynamiclobbyTEST",
                            false),
                    "same-lobby verification restores the active run");
            logging.loggingCommand("off");
            logging.close();
            set(guard, "listening", true);
            settings.guardKeys = "off";
            position(bot, 0, -GuardFollow.OFFSET);
            position(leader, 0, 0);
            position(rival, 1.2, 0);
            leader.rotationYawHead = 0;
            step(events, guard, pit, 1);
            expect(
                    !geometryReads.isEmpty() && Collections.max(geometryReads.values()) == 1,
                    "one guard decision reads each collision cell at most once");
            geometryReads.clear();
            step(events, guard, pit, 4);
            expect(
                    !geometryReads.isEmpty() && Collections.max(geometryReads.values()) == 1,
                    "the next guard decision refreshes collision data instead of retaining stale cells");
            expect(
                    !mc.gameSettings.keyBindForward.isKeyDown()
                            && !mc.gameSettings.keyBindBack.isKeyDown(),
                    "stationary anchor holds without jitter");
            position(bot, 0, -2.3);
            step(events, guard, pit, 4);
            expect(
                    mc.gameSettings.keyBindForward.isKeyDown(),
                    "guard closes the last two blocks instead of stopping at combat-route arrival tolerance");
            position(bot, 0, -GuardFollow.OFFSET);
            step(events, guard, pit, 4);
            Method request =
                    CombatController.class.getDeclaredMethod(
                            "requestAttack", EntityPlayer.class, CombatProfile.class);
            request.setAccessible(true);
            request.invoke(get(events, "combat"), leader, CombatProfile.BALANCED);
            expect(
                    controller.attacks == 0,
                    "guarded UUID cannot be attacked even in target-all and named list");
            logging.loggingCommand("on");
            logging.start();
            swing(guard, leader, rival, pit, 5);
            rival.hurtTime = 10;
            step(events, guard, pit, 5);
            expect(controller.attacks == 0, "pending assist waits for observed hit window");
            rival.hurtTime = 0;
            CombatClicks clicks = (CombatClicks) get(get(events, "combat"), "combatClicks");
            clicks.defer(6, 3);
            step(events, guard, pit, 8);
            expect(controller.attacks == 0, "pending assist also waits for bot attack pacing");
            for (long tick = 9; tick <= 11; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks == 1 && controller.last == rival,
                    "ready assist uses actual native attack adapter on opponent");
            int attacks = controller.attacks;
            for (long tick = 12; tick < 18; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks == attacks + 1,
                    "one unacknowledged swing permits one bounded retry without a burst");
            attacks = controller.attacks;
            expect(
                    guard.status().contains("phase: fighting"),
                    "consuming a click does not immediately switch fighting back to standing guard");
            set(navigator, "gapPreparing", unsafe.allocateInstance(SprintGap.Candidate.class));
            bot.rotationYaw = 180;
            step(events, guard, pit, 18);
            expect(
                    !navigator.preparingGap() && Math.abs(CombatTurn.wrap(bot.rotationYaw)) < 180,
                    "safely reachable ground combat cancels a prepared runway and owns aiming even between click intents");
            leader.inventory.currentItem = 1;
            step(events, guard, pit, 20);
            expect(
                    bot.inventory.currentItem == 1 && bot.getHeldItem() == null,
                    "non-sword leader selects empty fist slot immediately");
            step(events, guard, pit, 22);
            expect(!guard.sword(), "fist selection survives subsequent ticks");
            leader.inventory.currentItem = 0;
            step(events, guard, pit, 23);
            expect(
                    bot.getHeldItem() != null && guard.sword(),
                    "sword held by leader restores bot sword");
            step(events, guard, pit, 26);
            clicks.defer(26, 1);
            attacks = controller.attacks;
            wall = true;
            swing(guard, leader, rival, pit, 27);
            for (long tick = 27; tick < 32; tick++) step(events, guard, pit, tick);
            expect(controller.attacks == attacks, "intervening blocks prevent assistance");
            wall = false;
            position(rival, 0, 1.5);
            bot.rotationYaw = 0;
            bot.rotationPitch = 0;
            clicks.defer(32, 1);
            swing(guard, leader, rival, pit, 33);
            step(events, guard, pit, 33);
            expect(
                    controller.attacks == attacks + 1 && controller.last == rival,
                    "guard assistance retains existing target ray rules with another player nearby");
            attacks = controller.attacks;
            position(rival, 8, 0);
            swing(guard, leader, rival, pit, 34);
            step(events, guard, pit, 34);
            expect(
                    controller.attacks == attacks,
                    "out-of-reach swing cannot cause an entity attack");
            int beforeUnresolvedAir = bot.swings;
            clicks.defer(34, 1);
            swing(guard, leader, null, pit, 35);
            step(events, guard, pit, 35);
            expect(
                    bot.swings == beforeUnresolvedAir && controller.attacks == attacks,
                    "an unresolved click waits for target packets instead of immediately swinging into air");
            for (long tick = 36; tick <= 43; tick++) step(events, guard, pit, tick);
            expect(
                    bot.swings == beforeUnresolvedAir + 1 && controller.attacks == attacks,
                    "a genuinely unresolved swing keeps one delayed animation without an entity attack");
            position(leader, 30, 0);
            step(events, guard, pit, 43);
            expect(
                    (boolean) get(guard, "waiting")
                            && get(guard, "assistTarget") == null
                            && !((GuardAssist) get(guard, "assist")).pending(43)
                            && controller.attacks == attacks,
                    "missing/outside guard target cancels combat before waiting patrol takes over");
            position(leader, 0, 0);
            position(bot, 0, -GuardFollow.OFFSET);
            position(rival, 8, 0);
            step(events, guard, pit, 40);
            step(events, guard, pit, 639);
            expect(
                    !mc.gameSettings.keyBindJump.isKeyDown(),
                    "idle reset does not start before thirty seconds");
            step(events, guard, pit, 640);
            expect(
                    mc.gameSettings.keyBindJump.isKeyDown(),
                    "stationary guard performs checked idle jump at thirty seconds");
            expect(
                    mc.gameSettings.keyBindBack.isKeyDown(),
                    "idle reset moves slightly behind the normal anchor");
            position(bot, 0, -GuardFollow.OFFSET - .10);
            step(events, guard, pit, 643);
            expect(
                    mc.gameSettings.keyBindForward.isKeyDown(),
                    "idle reset reverses toward the original anchor");
            position(bot, 0, -GuardFollow.OFFSET);
            step(events, guard, pit, 649);
            expect(
                    !mc.gameSettings.keyBindJump.isKeyDown(),
                    "idle action ends and returns to normal guard positioning");
            position(leader, .2, 0);
            step(events, guard, pit, 650);
            expect(
                    (long) get(guard, "idleAt") == Long.MIN_VALUE,
                    "guarded movement cancels idle reset");
            logging.close();
            StringBuilder guardRecording = new StringBuilder();
            try (java.util.stream.Stream<java.nio.file.Path> files =
                    Files.list(mc.mcDataDir.toPath().resolve("config/optimus-ml-data/fights"))) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator)
                    guardRecording.append(
                            new String(
                                    Files.readAllBytes(file),
                                    java.nio.charset.StandardCharsets.UTF_8));
            }
            String guardData = guardRecording.toString();
            String guardFixture = System.getProperty("optimus.guardRecordingFixture");
            if (guardFixture != null)
                Files.write(
                        java.nio.file.Paths.get(guardFixture),
                        guardData
                                .replaceAll("[\\r\\n]+$", "")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            expect(
                    guardData.contains("\"combat_mode\":\"guard\"")
                            && guardData.contains("\"gate\":\"hit_window\"")
                            && guardData.contains("\"attack_requested\":true"),
                    "real guard recording distinguishes blocked hit windows and actual attack requests");
            expect(
                    guardData.contains("\"type\":\"combat_animation\"")
                            && guardData.contains("\"gate\":\"target_unresolved\""),
                    "unresolved guard air swings remain visible in the anonymous combat recording");
            expect(
                    guardData.contains("\"clear_body_ray\":true")
                            && guardData.contains("\"yaw_error_after\"")
                            && guardData.contains("\"ping_ms\"")
                            && guardData.contains("\"body\""),
                    "guard decisions record aim, real body geometry, ping and ray observations");
            guard.reset();
            position(bot, 0, -GuardFollow.OFFSET);
            position(leader, 30, 0);
            bot.rotationYaw = 0;
            bot.motionX = bot.motionZ = 0;
            ((Random) get(events, "patrolRandom")).setSeed(731);
            bot.onGround = false;
            bot.motionY = 0;
            mc.inGameHasFocus = false;
            net.minecraft.client.settings.KeyBinding.unPressAllKeys();
            int waitingAttacks = controller.attacks;
            step(events, guard, pit, 660);
            expect(
                    get(events, "roamGoal") != null && (boolean) get(guard, "waiting"),
                    "absent leader starts the shared random patrol without a thirty-second delay");
            expect(
                    !bot.onGround,
                    "unfocused patrol selection does not manufacture a native landing");
            bot.onGround = true;
            boolean walking = false;
            for (long tick = 661; tick <= 675; tick++) {
                step(events, guard, pit, tick);
                walking |=
                        ((MovementInput) get(events, "input")).snapshot().direction
                                != NearbySteering.Input.NONE;
            }
            expect(
                    walking && controller.attacks == waitingAttacks,
                    "waiting patrol issues real movement without acquiring combat targets");
            position(leader, 0, 0);
            step(events, guard, pit, 676);
            expect(
                    !(boolean) get(guard, "waiting")
                            && get(events, "roamGoal") == null
                            && get(guard, "leader") == leader,
                    "returning leader cancels the patrol and immediately resumes following");
            position(leader, 30, 0);
            step(events, guard, pit, 1900);
            guard.suspend();
            expect(
                    !(boolean) get(guard, "waiting")
                            && get(events, "roamGoal") == null
                            && ((MovementInput) get(events, "input")).snapshot().direction
                                    == NearbySteering.Input.NONE,
                    "suspension cancels patrol ownership and held movement");
            mc.currentScreen = new net.minecraft.client.gui.GuiChat();
            step(events, guard, pit, 1901);
            expect(
                    get(events, "roamGoal") == null && !(boolean) get(guard, "waiting"),
                    "an open menu cannot restart waiting patrol movement");
            mc.currentScreen = null;
            position(leader, 0, 0);
            guard.reset();
            step(events, guard, pit, 650);
            bot.inventory.mainInventory[1] = new ItemStack(new Item());
            leader.inventory.currentItem = 1;
            int swings = bot.swings;
            swing(guard, leader, rival, pit, 651);
            step(events, guard, pit, 651);
            expect(
                    bot.swings == swings && controller.attacks == attacks,
                    "occupied fist slot never swings a lobby item");
            position(bot, .2, -GuardFollow.OFFSET);
            leader.posY = 1.9;
            leader.serverPosY = 61;
            leader.onGround = false;
            step(events, guard, pit, 652);
            expect(
                    mc.gameSettings.keyBindJump.isKeyDown(),
                    "observed rising jump is mirrored instead of stopping at the route's elevation branch");
            leader.hurtTime = 10;
            leader.posY = 2;
            leader.serverPosY = 64;
            step(events, guard, pit, 653);
            expect(
                    !mc.gameSettings.keyBindJump.isKeyDown()
                            && (long) get(guard, "knockbackUntil") > 653,
                    "incoming knockback is not mirrored as a jump");
            leader.hurtTime = 0;
            position(leader, .2, 0);
            position(bot, .2, -GuardFollow.OFFSET);
            CollisionGeometry ceiling =
                    new CollisionGeometry(
                            (x, y, z) ->
                                    new CollisionGeometry.Cell(
                                            true,
                                            false,
                                            y == 0 || y == 3
                                                    ? Collections.singletonList(
                                                            new CollisionGeometry.Box(
                                                                    x, y, z, x + 1, y + 1, z + 1))
                                                    : Collections.emptyList()));
            set(navigator, "geometry", ceiling);
            expect(
                    !((GuardNavigation) get(guard, "navigator"))
                            .safeGuardJump(NearbySteering.Input.NONE, false, pit),
                    "low headroom skips idle jump");
            set(navigator, "geometry", floor);
            guard.suspend();
            expect(
                    !mc.gameSettings.keyBindForward.isKeyDown()
                            && !mc.gameSettings.keyBindJump.isKeyDown(),
                    "suspension releases owned follow and jump keys");
            settings.save();
            ClientSettings reloaded = new ClientSettings(settings.traceDirectory().getParentFile());
            expect(
                    reloaded.guardName.equals(leader.name)
                            && reloaded.guardUuid.equals(leader.id.toString()),
                    "saved guard identity retains UUID binding");
            final boolean[] pressed = new boolean[256];
            final int[] keyReads = {0};
            GuardTestInputs.install(
                    guard,
                    () -> true,
                    key -> {
                        keyReads[0]++;
                        return pressed[key];
                    });
            expect(
                    events.guardCommand(new String[] {"guard", "keys", "auto"}).contains("auto"),
                    "guard command selects automatic bot-only input mode");
            set(events, "locationRoundTrip", 250);
            bot.inventory.mainInventory[1] = null;
            leader.inventory.currentItem = 0;
            expect(
                    events.guardPingMillis() == 250,
                    "guard input delay uses measured server round trip when tab ping is zero");
            guard.suspend();
            position(leader, 0, 0);
            position(bot, 0, -GuardFollow.OFFSET);
            step(events, guard, pit, 655);
            pressed[0x57] = true;
            set(events, "tickNo", 655L);
            guard.step(pit, 655, true);
            expect(
                    mc.gameSettings.keyBindForward.isKeyDown(),
                    "Windows W is applied before movement on the same tick without a server position update");
            leader.sprint = true;
            guard.step(pit, 655, true);
            expect(
                    bot.isSprinting(),
                    "an already sprinting main is followed at sprint speed without requiring held Ctrl");
            leader.sprint = false;
            pressed[0x57] = false;
            guard.step(pit, 655, true);
            expect(
                    !mc.gameSettings.keyBindForward.isKeyDown(),
                    "Windows release clears forward immediately even while observed position has not changed");
            pressed[0x41] = true;
            guard.step(pit, 655, true);
            expect(
                    mc.gameSettings.keyBindLeft.isKeyDown(),
                    "Windows strafe does not wait for observed velocity");
            pressed[0x41] = false;
            pressed[0x20] = true;
            guard.step(pit, 655, true);
            expect(
                    mc.gameSettings.keyBindJump.isKeyDown(),
                    "Windows jump is mirrored before the guarded player rises in server updates");
            expect(
                    guard.status().contains("Windows keys"),
                    "status identifies the active input source");
            pressed[0x20] = false;
            pressed[0x57] = true;
            set(events, "enteredPit", true);
            events.onGuardPlayerTick(
                    new net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent(
                            net.minecraftforge.fml.common.gameevent.TickEvent.Phase.START, bot));
            expect(
                    mc.gameSettings.keyBindForward.isKeyDown(),
                    "player pre-tick event samples input before native movement");
            long beforePause = (long) get(guard, "directMoves");
            guard.suspend();
            expect(
                    guard.status().contains("input: paused; last=Windows keys")
                            && (long) get(guard, "directMoves") == beforePause
                            && !guard.status().contains("moving=0"),
                    "opening chat preserves the last input source and sampled/applied movement evidence");
            step(events, guard, pit, 655);
            expect(
                    (long) get(guard, "directMoves") >= beforePause
                            && !guard.status().contains("moving=0"),
                    "resuming the same guard preserves diagnostic history");
            pressed[0x57] = false;
            pressed[0x01] = true;
            step(events, guard, pit, 656);
            pressed[0x01] = false;
            leader.isSwingInProgress = true;
            leader.swingProgressInt = 0;
            step(events, guard, pit, 661);
            expect(
                    ((String) get(get(guard, "assist"), "observed")).startsWith("windows:"),
                    "delayed server echo cannot duplicate an already mirrored physical click");
            position(bot, .32, -.16);
            position(leader, 0, 0);
            leader.isSwingInProgress = false;
            step(events, guard, pit, 900);
            expect(
                    mc.gameSettings.keyBindForward.isKeyDown()
                            || mc.gameSettings.keyBindBack.isKeyDown()
                            || mc.gameSettings.keyBindLeft.isKeyDown()
                            || mc.gameSettings.keyBindRight.isKeyDown(),
                    "released physical keys do not strand the bot in an old combat flank instead of returning behind the player");
            guard.suspend();
            position(leader, 0, 0);
            position(bot, 0, 1);
            position(rival, 0, 3.6);
            leader.rotationYawHead = 0;
            leader.isSwingInProgress = false;
            step(events, guard, pit, 920);
            clicks.defer(920, 1);
            int beforeAir = controller.attacks;
            leader.isSwingInProgress = true;
            leader.swingProgressInt = 0;
            step(events, guard, pit, 921);
            expect(
                    controller.attacks == beforeAir + 1 && controller.last == rival,
                    "leader air swing selects the looked-at player when only the bot is in actual reach");
            guard.suspend();
            position(leader, 0, 0);
            position(bot, 0, -GuardFollow.OFFSET);
            position(rival, 0, 1.5);
            leader.rotationYawHead = 90;
            leader.isSwingInProgress = false;
            pressed[0x01] = true;
            clicks.defer(930, 1);
            step(events, guard, pit, 930);
            pressed[0x01] = false;
            step(events, guard, pit, 931);
            int beforeRepair = controller.attacks;
            leader.rotationYawHead = 0;
            for (long tick = 932; tick <= 934; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks > beforeRepair && controller.last == rival,
                    "a fresh aim update converts an air swing to a real request without owner damage or another swing");
            int beforeEcho = controller.attacks;
            leader.rotationYawHead = 0;
            leader.isSwingInProgress = true;
            leader.swingProgressInt = 0;
            for (long tick = 935; tick <= 938; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks == beforeEcho && controller.last == rival,
                    "server echo refines the physical intent without duplicating an already sent attack");
            profiles.put(
                    rival.id,
                    new net.minecraft.client.network.NetworkPlayerInfo(
                            new com.mojang.authlib.GameProfile(rival.id, rival.name)));
            events.guardCommand(new String[] {"guard", rival.name});
            expect(
                    settings.guardUuid.equals(rival.id.toString())
                            && guard.protects(rival)
                            && !guard.protects(leader)
                            && get(guard, "assistTarget") == null
                            && !(boolean) get(events, "startRequested")
                            && (boolean) get(events, "armed"),
                    "selecting another player replaces the only guard slot and clears the previous fight without restarting");
            settings.guardKeys = "on";
            pressed[0x57] = pressed[0x20] = pressed[0x01] = true;
            int priorReads = keyReads[0], priorAttacks = controller.attacks;
            position(rival, 0, 0);
            rival.rotationYawHead = 0;
            rival.isSwingInProgress = false;
            position(bot, 0, -GuardFollow.OFFSET);
            position(leader, 8, 0);
            set(events, "startRequested", false);
            set(events, "armed", true);
            set(events, "sessionWorld", mc.theWorld);
            set(events, "sessionPlayer", bot);
            set(guard, "listening", true);
            run.startChecking(now);
            run.activate("duelslobby9", "dynamiclobbyTEST", mc.theWorld);
            step(events, guard, pit, 950);
            step(events, guard, pit, 954);
            expect(
                    keyReads[0] == priorReads
                            && controller.attacks == priorAttacks
                            && !mc.gameSettings.keyBindForward.isKeyDown()
                            && !mc.gameSettings.keyBindJump.isKeyDown()
                            && guard.status().contains("input: observed movement"),
                    "forced Windows keys never sample or apply the old owner's movement, jump or attack to another guarded player");
            expect(
                    (long) get(guard, "directMoves") == 0 && !(boolean) get(guard, "desktopAttack"),
                    "guard switch clears direct-input counters and held attack state");
            Arrays.fill(pressed, false);
            position(leader, 0, 3.6);
            position(bot, 0, 1);
            rival.isSwingInProgress = true;
            rival.swingProgressInt = 0;
            clicks.defer(955, 1);
            step(events, guard, pit, 956);
            expect(
                    controller.attacks > priorAttacks && controller.last == leader,
                    "another guarded player's observed air swing authorizes a bot-in-range attack without damage");
            Player second = (Player) unsafe.allocateInstance(Player.class);
            second.name = "SecondOpponent";
            second.id = UUID.randomUUID();
            second.worldObj = mc.theWorld;
            DataWatcher secondData = new DataWatcher(second);
            secondData.addObject(6, Float.valueOf(20));
            secondData.addObject(0, Byte.valueOf((byte) 0));
            set(second, "dataWatcher", secondData);
            second.inventory = new InventoryPlayer(second);
            second.capabilities = new PlayerCapabilities();
            position(second, 1.5, 0);
            mc.theWorld.playerEntities.add(second);
            rival.isSwingInProgress = false;
            step(events, guard, pit, 957);
            rival.rotationYawHead = -90;
            rival.isSwingInProgress = true;
            rival.swingProgressInt = 0;
            int beforeSwitch = controller.attacks;
            clicks.defer(958, 1);
            for (long tick = 958; tick <= 959; tick++) step(events, guard, pit, tick);
            expect(
                    get(guard, "assistTarget") == second && controller.attacks == beforeSwitch,
                    "a fresh swing switches the locked opponent while awaiting the owner's hit feedback");
            for (long tick = 960; tick <= 962; tick++) step(events, guard, pit, tick);
            expect(
                    get(guard, "assistTarget") == second
                            && controller.attacks > beforeSwitch
                            && controller.last == second
                            && second.getHealth() == 20,
                    "an unanswered owner swing still permits an attack after the bounded feedback wait");
            position(second, 0, 3.4);
            position(bot, 0, -GuardFollow.OFFSET);
            bot.rotationYaw = 180;
            rival.rotationYawHead = 90;
            swing(guard, rival, second, pit, 970);
            step(events, guard, pit, 970);
            expect(
                    Math.abs(CombatTurn.wrap(bot.rotationYaw)) < 180,
                    "combat aims toward an out-of-reach opponent instead of keeping the owner's unrelated heading");
            Method combatGoal =
                    get(guard, "combat")
                            .getClass()
                            .getDeclaredMethod(
                                    "goal",
                                    Vec3Point.class,
                                    Vec3Point.class,
                                    Vec3Point.class,
                                    PitRegion.class,
                                    long.class,
                                    Vec3Point.class);
            combatGoal.setAccessible(true);
            Vec3Point self = new Vec3Point(bot.posX, bot.posY, bot.posZ),
                    owner = new Vec3Point(0, 1, 0);
            Vec3Point firstGoal =
                    (Vec3Point)
                            combatGoal.invoke(
                                    get(guard, "combat"),
                                    self,
                                    owner,
                                    GuardFollow.anchor(owner, 90),
                                    pit,
                                    970L,
                                    new Vec3Point(0, 0, 0));
            rival.rotationYawHead = -90;
            Vec3Point nextGoal =
                    (Vec3Point)
                            combatGoal.invoke(
                                    get(guard, "combat"),
                                    self,
                                    owner,
                                    GuardFollow.anchor(owner, -90),
                                    pit,
                                    970L,
                                    new Vec3Point(0, 0, 0));
            expect(
                    firstGoal.horizontalDistanceSq(nextGoal) < .001,
                    "combat positioning is stable when the guarded player looks away in opposite directions");
            // A hit can displace the leader before its attacker's head packet.
            settings.guardName = leader.name;
            settings.guardUuid = leader.id.toString();
            guard.suspend();
            set(guard, "listening", true);
            position(second, 15, 15);
            position(bot, 0, -GuardFollow.OFFSET);
            position(leader, 0, 0);
            position(rival, 0, 1.5);
            leader.isSwingInProgress = rival.isSwingInProgress = false;
            leader.hurtTime = 0;
            bot.rotationYaw = 0;
            rival.rotationYawHead = 0;
            step(events, guard, pit, 1000);
            position(leader, 3, 0);
            leader.hurtTime = 10;
            rival.isSwingInProgress = true;
            rival.swingProgressInt = 0;
            int beforeDefense = controller.attacks;
            clicks.defer(1001, 1);
            step(events, guard, pit, 1001);
            expect(
                    get(guard, "assistTarget") == null,
                    "a wrong-facing swing alone cannot nominate an attacker");
            rival.rotationYawHead = 180;
            for (long tick = 1002; tick <= 1005; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks > beforeDefense && controller.last == rival,
                    "late attacker aim matches pre-knockback leader position and attacks while the leader is displaced");
            // An outlying leader still needs navigation, with independent aim.
            guard.suspend();
            set(guard, "listening", true);
            position(leader, 6, 0);
            leader.hurtTime = 0;
            position(bot, 0, -.44);
            position(rival, 0, 1.5);
            rival.isSwingInProgress = false;
            bot.rotationYaw = 180;
            step(events, guard, pit, 1020);
            int beforeRouteAttack = controller.attacks;
            clicks.defer(1021, 1);
            swing(guard, leader, rival, pit, 1021);
            for (long tick = 1021; tick <= 1027; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks > beforeRouteAttack && controller.last == rival,
                    "route following keeps a real in-range assist instead of giving navigation exclusive aim");
            expect(
                    ((MovementInput) get(events, "input")).snapshot().direction
                            != NearbySteering.Input.NONE,
                    "combat aiming preserves navigation movement in world space");
            Vec3Point travel =
                    ((MovementInput) get(events, "input"))
                            .snapshot()
                            .direction
                            .point(positionPoint(bot), bot.rotationYaw, 1);
            expect(
                    travel.x > bot.posX + .5,
                    "combat-relative navigation keys retain the route's eastward direction");
            String previousKeys = settings.guardKeys;
            settings.guardKeys = "off";
            long routeTick = 5000;
            for (int distance : new int[] {6, 12})
                for (float head : new float[] {-90, 90, 180})
                    for (boolean identified : new boolean[] {false, true}) {
                        guard.suspend();
                        set(guard, "listening", true);
                        position(bot, 0, 0);
                        position(leader, 0, distance);
                        position(rival, 1, distance + 1.5);
                        bot.motionX = bot.motionZ = 0;
                        bot.rotationYaw = 90;
                        leader.rotationYawHead = leader.rotationYaw = head;
                        leader.hurtTime = rival.hurtTime = 0;
                        step(events, guard, pit, routeTick++);
                        swing(guard, leader, identified ? rival : null, pit, routeTick);
                        for (int phase = 0; phase < 3; phase++) {
                            float before = bot.rotationYaw;
                            guard.step(pit, routeTick, true);
                            expect(
                                    Math.abs(CombatTurn.wrap(bot.rotationYaw - before)) <= 12.01,
                                    "distant swing cannot take navigation aim before movement");
                            before = bot.rotationYaw;
                            step(events, guard, pit, routeTick++);
                            expect(
                                    Math.abs(CombatTurn.wrap(bot.rotationYaw - before)) <= 12.01,
                                    "distant swing cannot snap navigation aim after movement");
                            NearbySteering.Input held =
                                    ((MovementInput) get(events, "input")).snapshot().direction;
                            if (held != NearbySteering.Input.NONE)
                                expect(
                                        held.point(positionPoint(bot), bot.rotationYaw, 1).z
                                                > bot.posZ + .5,
                                        "distant combat intent preserves movement toward the guarded player");
                        }
                    }
            settings.guardKeys = previousKeys;
            guard.suspend();
            position(bot, 0, 0);
            bot.motionX = bot.motionZ = 0;
            bot.rotationYaw = 0;
            navigator.reset();
            checks +=
                    dev.optimus.client.guard.GuardSteeringChecks.verify(
                            mc,
                            (dev.optimus.client.guard.GuardHost) get(events, "guardHost"),
                            settings,
                            leader,
                            pit);
            NavigationController navigation = (NavigationController) get(events, "navigation");
            Vec3Point cornerGoal = new Vec3Point(6, 1, 0);
            LobbyNavigator.Step corner =
                    new LobbyNavigator.Step(
                            LobbyNavigator.Status.MOVING, positionPoint(bot), false);
            for (long tick = 6000; tick <= 6011; tick++) {
                set(events, "tickNo", tick);
                navigation.navigateStep(
                        cornerGoal, pit, corner, (CombatController) get(events, "combat"));
            }
            LobbyNavigator.Step escape = navigator.step(cornerGoal, pit, false, 6012);
            expect(
                    escape.input != null && escape.input.forward < 0,
                    "a motionless corner correction triggers a checked back-out instead of looping");
            navigator.reset();
            CollisionGeometry stepDown =
                    new CollisionGeometry(
                            (x, y, z) ->
                                    new CollisionGeometry.Cell(
                                            true,
                                            false,
                                            y == 0 || y == 1 && z < 0
                                                    ? Collections.singletonList(
                                                            new CollisionGeometry.Box(
                                                                    x, y, z, x + 1, y + 1, z + 1))
                                                    : Collections.emptyList()));
            set(navigator, "geometry", stepDown);
            position(bot, 0, -.45);
            bot.posY = 2;
            position(leader, 0, .6);
            Vec3Point destination =
                    ((GuardNavigation) get(guard, "navigator"))
                            .guardDestination(
                                    new Vec3Point(0, 1, .16), positionPoint(leader), false, pit);
            expect(
                    destination.horizontalDistanceSq(positionPoint(leader)) < .001,
                    "blocked rear offset resolves to valid leader feet at a descending edge");
            expect(
                    ((GuardNavigation) get(guard, "navigator"))
                            .guardDrop(positionPoint(bot), destination, pit),
                    "adjacent one-block drop uses a checked walking fall");
            bot.rotationYaw = 0;
            bot.motionX = bot.motionZ = 0;
            expect(
                    ((GuardNavigation) get(guard, "navigator"))
                            .safeGuardInput(NearbySteering.Input.FORWARD, true, false, pit),
                    "guard permits native forward momentum beside a safe drop");
            guard.suspend();
            set(guard, "listening", true);
            step(events, guard, pit, 1040);
            expect(
                    !mc.gameSettings.keyBindJump.isKeyDown()
                            && ((MovementInput) get(events, "input")).snapshot().direction
                                    != NearbySteering.Input.NONE,
                    "following down a simple edge moves immediately without a jump or route-search pause");
            set(navigator, "geometry", floor);
            position(bot, 0, -.44);
            position(leader, 0, 0);
            // Nomination tolerates one remote quantization error, actual hits do not.
            position(rival, .4, 2);
            leader.rotationYawHead = 0;
            guard.suspend();
            set(guard, "listening", true);
            step(events, guard, pit, 1060);
            Method nominate =
                    get(guard, "combat")
                            .getClass()
                            .getDeclaredMethod("findVictim", String.class, PitRegion.class);
            nominate.setAccessible(true);
            expect(
                    nominate.invoke(get(guard, "combat"), "", pit) == rival,
                    "near-miss owner ray can nominate a real opponent despite small packet misalignment");
            guard.suspend();
            set(guard, "listening", true);
            position(bot, 0, 0);
            position(leader, 0, -.44);
            position(rival, 2.88, 1.1);
            bot.rotationYaw = -65;
            step(events, guard, pit, 1070);
            clicks.defer(1071, 1);
            int beforeCorner = controller.attacks;
            swing(guard, leader, rival, pit, 1071);
            for (long tick = 1071; tick <= 1074; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks > beforeCorner && controller.last == rival,
                    "a reachable body corner is used when the usual body-center ray is beyond melee reach");
            expect(
                    GuardFollow.travelGoal(
                                    new Vec3Point(0, 1, 0),
                                    new Vec3Point(0, 1, .8),
                                    new Vec3Point(0, 0, .28))
                            == null,
                    "close following does not invent a travel jump endpoint");
            Vec3Point forecast =
                    GuardFollow.travelGoal(
                            new Vec3Point(0, 1, 0),
                            new Vec3Point(0, 1, 2.5),
                            new Vec3Point(0, 0, .28));
            expect(
                    forecast != null && forecast.z > 4.5 && forecast.z <= 6,
                    "moving leader gets a bounded forward travel estimate");
            expect(
                    GuardFollow.travelGoal(
                                    new Vec3Point(0, 1, 0),
                                    new Vec3Point(0, 1, 2.5),
                                    new Vec3Point(.28, 0, 0))
                            == null,
                    "lateral leader movement does not authorize a speculative straight jump");
            expect(
                    GuardFollow.travelGoal(
                                    new Vec3Point(0, 1, 0),
                                    new Vec3Point(0, 1, 2.5),
                                    new Vec3Point(0, 0, 0))
                            == null,
                    "nearby stationary leader is approached on foot");
            expect(
                    GuardFollow.travelGoal(
                                    new Vec3Point(0, 1, 0),
                                    new Vec3Point(0, .5, 3),
                                    new Vec3Point(0, 0, .28))
                            == null,
                    "downhill following does not become a travel jump");
            events.controlOptionsChanged();
            settings.guardName = leader.name;
            settings.guardUuid = leader.id.toString();
            settings.guardKeys = "off";
            set(navigator, "geometry", floor);
            position(bot, .5, .5);
            position(leader, .5, 3.5);
            position(rival, 8, 0);
            bot.rotationYaw = 0;
            bot.motionX = 0;
            bot.motionZ = .153;
            bot.sprint = true;
            bot.jumpMovementFactor = .026f;
            leader.isSwingInProgress = false;
            bot.sprint = false;
            GuardBridge host = (GuardBridge) get(events, "guardHost");
            host.guardNavigate(new Vec3Point(.5, 1, 8.5), pit, false, true);
            expect(
                    ((List<?>) get(navigator, "path")).isEmpty()
                            && get(navigator, "search") == null
                            && mc.gameSettings.keyBindForward.isKeyDown()
                            && mc.gameSettings.keyBindSprint.isKeyDown(),
                    "checked clear-ground catch-up starts sprinting without a path search");
            host.guardHold();
            bot.sprint = true;
            step(events, guard, pit, 1100);
            position(leader, .5, 3.78);
            step(events, guard, pit, 1101);
            position(leader, .5, 4.06);
            step(events, guard, pit, 1102);
            expect(
                    navigator.travelJumpInProgress() && mc.gameSettings.keyBindJump.isKeyDown(),
                    "native guard launches a checked catch-up jump behind a moving leader before the old distance threshold");
            bot.onGround = false;
            bot.posY = 1.4;
            bot.motionY = .2;
            position(rival, 0, 1.8);
            swing(guard, leader, rival, pit, 1103);
            clicks.defer(1102, 1);
            step(events, guard, pit, 1103);
            expect(
                    !navigator.travelJumpInProgress() && !mc.gameSettings.keyBindJump.isKeyDown(),
                    "direct melee releases travel-jump ownership and prevents another catch-up launch");
            expect(
                    Math.abs(bot.motionZ - .153) < 1e-9 && bot.motionY == .2,
                    "combat handoff preserves ordinary airborne velocity");
            navigator.reset();
            guard.suspend();
            position(bot, 0, -GuardFollow.OFFSET);
            position(leader, 0, 0);
            position(rival, 0, 2.2);
            bot.motionX = bot.motionZ = 0;
            bot.rotationYaw = leader.rotationYawHead = 0;
            leader.isSwingInProgress = false;
            for (long tick = 1200; tick <= 1203; tick++) step(events, guard, pit, tick);
            int beforeRetreat = controller.attacks;
            position(rival, 0, 2.6);
            swing(guard, leader, rival, pit, 1204);
            step(events, guard, pit, 1204);
            expect(
                    controller.attacks == beforeRetreat && get(guard, "assistTarget") == rival,
                    "a selected victim is approached before spending an attack at the edge of reach");
            position(bot, 0, 1.2);
            for (long tick = 1205; tick <= 1211; tick++) step(events, guard, pit, tick);
            expect(
                    controller.attacks > beforeRetreat,
                    "the pending assist attacks after closing into contact reach");
            float assistedYaw = bot.rotationYaw;
            int assistedAttacks = controller.attacks;
            bot.rotationYaw += 90;
            step(events, guard, pit, 1211);
            expect(
                    Math.abs(dev.optimus.core.combat.CombatTurn.wrap(bot.rotationYaw - assistedYaw))
                            < .01,
                    "combat aim is restored after navigation changes view in the same physical tick");
            expect(
                    controller.attacks == assistedAttacks,
                    "restoring combat aim cannot duplicate a physical-tick attack");
            position(second, 0, 1.5);
            if (!mc.theWorld.playerEntities.contains(second))
                mc.theWorld.playerEntities.add(second);
            leader.rotationYawHead = 0;
            step(events, guard, pit, 1212);
            step(events, guard, pit, 1213);
            expect(
                    get(guard, "assistTarget") == rival,
                    "an explicit hit victim stays locked when a bystander crosses the guarded player's view");
            leader.rotationYawHead = 180;
            swing(guard, leader, null, pit, 1214);
            guard.step(pit, 1214, true);
            expect(
                    get(guard, "assistTarget") == rival
                            && Math.abs(CombatTurn.wrap(bot.rotationYaw)) < 45,
                    "combat target and aim survive the pre-movement phase of an unresolved swing");
            int beforeAimRefresh = controller.attacks;
            step(events, guard, pit, 1214);
            expect(
                    controller.attacks == beforeAimRefresh,
                    "reasserting combat aim cannot duplicate a physical-tick attack");
            expect(
                    get(guard, "assistTarget") == rival,
                    "an unresolved swing during an active fight retains the opponent");
            expect(
                    Math.abs(CombatTurn.wrap(bot.rotationYaw)) < 45,
                    "an unresolved swing cannot replace combat aim with the guarded player's backward view");
            safety.invalidate();
            step(events, guard, pit, 1080);
            expect(
                    !mc.gameSettings.keyBindForward.isKeyDown()
                            && !((GuardAssist) get(guard, "assist")).pending(1080),
                    "verification loss cannot keep guard keys or a queued assist");
            events.guardCommand(new String[] {"guard", "off"});
            expect(
                    !guard.enabled()
                            && settings.targetAll
                            && settings.swordEnabled
                            && !events.controlAllowed(),
                    "guard off retains target/weapon settings without bypassing failed verification");
            events.stop("Manual stop");
            for (String[] change :
                    new String[][] {
                        {"guard", "off"},
                        {"profile", "balanced"},
                        {"seed", "770"},
                        {"routes", "off"}
                    }) {
                commands.processCommand(bot, change);
                expect(
                        !(boolean) get(events, "armed") && !(boolean) get(events, "startRequested"),
                        "manual stop remains stopped after " + Arrays.toString(change));
            }
            boolean emergencyBlocked = false;
            try {
                OptimusMod.terminalCommand(new String[] {"guard", leader.name}, true);
            } catch (IllegalArgumentException expected) {
                emergencyBlocked = true;
            }
            expect(
                    emergencyBlocked && !(boolean) get(events, "startRequested"),
                    "F9 also blocks a terminal guard command that would start a stopped bot");
            set(guard, "localSwingId", 37L);
            guard.reset();
            expect(
                    (long) get(guard, "localSwingId") == 37L,
                    "reset preserves the publisher's swing sequence for existing subscribers");
        } finally {
            if (logging != null) logging.close();
            if (guard != null) guard.reset();
            if (channel != null) channel.finish();
            singleton.set(null, old);
            OptimusMod.settings = oldSettings;
            OptimusMod.events = oldEvents;
            OptimusMod.routeHintsEnabled = oldHints;
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " real guard equipment, cooldown, protection, ray, idle and input checks");
    }

    private static Vec3Point positionPoint(Player player) {
        return new Vec3Point(player.posX, player.posY, player.posZ);
    }
}