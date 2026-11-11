package dev.optimus.client;

import static dev.optimus.client.NativeGuardFixture.position;

import dev.optimus.client.NativeGuardFixture.Controller;
import dev.optimus.client.NativeGuardFixture.Player;
import dev.optimus.client.NativeGuardFixture.World;
import dev.optimus.client.guard.GuardController;
import dev.optimus.client.guard.GuardTestInputs;
import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.*;
import net.minecraft.entity.*;
import net.minecraft.entity.player.*;
import net.minecraft.item.*;
import net.minecraft.network.*;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.*;
import sun.misc.Unsafe;

/**
 * Delayed packet model around the actual guard controller and native movement. Models equal-damage
 * vanilla immunity and a conservative 2.75-block server body-distance cap; it does not claim to
 * reproduce Hypixel's private rules.
 */
public final class GuardCombatSimulationSelfTest {
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

    private static void set(Object o, String name, Object value) throws Exception {
        if (o instanceof GuardController) o=GuardTestInputs.owner(o,name);
        field(o.getClass(), name).set(o, value);
    }

    private static Object get(Object o, String name) throws Exception {
        if (o instanceof GuardController) o=GuardTestInputs.owner(o,name);
        return field(o.getClass(), name).get(o);
    }

    private static Vec3Point point(Player p) {
        return new Vec3Point(p.posX, p.posY, p.posZ);
    }

    private static CollisionGeometry.Box body(Vec3Point p) {
        return CollisionGeometry.body(p);
    }

    private static final class Event implements Comparable<Event> {
        final int at, order;
        final Runnable action;

        Event(int at, int order, Runnable action) {
            this.at = at;
            this.order = order;
            this.action = action;
        }

        @Override
        public int compareTo(Event other) {
            int time = Integer.compare(at, other.at);
            return time == 0 ? Integer.compare(order, other.order) : time;
        }
    }

    private static final class Scenario {
        final String name;
        final int ping, phase, jitter;
        final double gap, kick;
        final boolean sword, moving, single;

        Scenario(
                String name,
                int ping,
                int phase,
                int jitter,
                double gap,
                double kick,
                boolean sword,
                boolean moving,
                boolean single) {
            this.name = name;
            this.ping = ping;
            this.phase = phase;
            this.jitter = jitter;
            this.gap = gap;
            this.kick = kick;
            this.sword = sword;
            this.moving = moving;
            this.single = single;
        }
    }

    private static final class Simulation implements AutoCloseable {
        final Scenario scenario;
        final Minecraft mc;
        final World world;
        final Player bot, leader, opponent, main, enemy;
        Player bystander;
        final ClientEvents events;
        final GuardController guard;
        final LobbySafety safety;
        final Controller controller;
        final EmbeddedChannel channel;
        final PriorityQueue<Event> packets = new PriorityQueue<>();
        final Random jitter;
        final List<String> trace;
        Vec3Point serverMain, serverBot;
        int now, serial, nextDamageAt, mainHits, guardHits, rejectedImmune, rejectedRange;
        final Map<String, Integer> arrivals = new HashMap<>();
        int mainRequests, guardRequests, secondAt = -1, lastGuardRequest;
        String secondOwner = "", firstOwner = "";
        float health = 40;
        double maximumSeparation, maximumAhead;
        boolean physicalForward, physicalAttack, physicalJump;
        int physicalSide;
        boolean physicalBack;
        float serverYaw;
        boolean serverGround = true, serverSprint;
        String lastIntent = "";
        int lastIntentAt = -1, previousRequest = -1;
        int blockedView,
                blockedRun,
                maximumBlockedRun,
                lateMovement,
                combatCorrections,
                firstGuardRequest = -1,
                phaseChanges;
        int desktopReads, wrongAim, combatViewRun, maximumCombatViewRun;
        int responseAt = -1, observedResponseAt = -1, releaseAt = -1;
        double responseBotZ, responseOwnerZ;
        boolean fighting;

        boolean defensive() {
            return scenario.name.startsWith("defense_");
        }

        boolean unidentified() {
            return scenario.name.contains("unknown")
                    || scenario.name.contains("wrong_facing")
                    || scenario.name.contains("no_damage")
                    || scenario.name.contains("ambiguous")
                    || scenario.name.contains("stale");
        }

        boolean movementOnly() {
            return scenario.name.startsWith("movement_");
        }

        boolean airReach() {
            return scenario.name.contains("air_reach") || scenario.name.contains("unacknowledged");
        }

        boolean delayedAim() {
            return scenario.name.contains("echo_target")
                    || scenario.name.contains("damage_target")
                    || scenario.name.contains("aim_update");
        }

        boolean otherGuarded() {
            return scenario.name.contains("other_guarded");
        }

        boolean independentAim() {
            return scenario.name.startsWith("combat_head_independent");
        }

        boolean responseTest() {
            return scenario.name.contains("response_");
        }

        Simulation(Scenario scenario, Unsafe unsafe, List<String> trace) throws Exception {
            this.scenario = scenario;
            this.trace = trace;
            jitter = new Random(731 + scenario.phase);
            mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
            field(Minecraft.class, "theMinecraft").set(null, mc);
            mc.gameSettings = new GameSettings();
            set(mc, "mcDataDir", Files.createTempDirectory("guard-combat-game").toFile());
            world = (World) unsafe.allocateInstance(World.class);
            mc.theWorld = world;
            set(world, "theProfiler", new Profiler());
            world.cells = new HashMap<>();
            for (int x = -10; x <= 10; x++)
                for (int z = -126; z <= -78; z++) world.add(x, 62, z, x, 62, z, x + 1, 63, z + 1);
            if (scenario.name.contains("step_down"))
                for (int x = -10; x <= 10; x++)
                    for (int z = -126; z <= -115; z++)
                        world.add(x, 63, z, x, 63, z, x + 1, 64, z + 1);
            if (scenario.name.contains("step_up"))
                for (int x = -10; x <= 10; x++)
                    for (int z = -114; z <= -78; z++)
                        world.add(x, 63, z, x, 63, z, x + 1, 63.5, z + 1);
            if (scenario.name.equals("blocked_flank_250"))
                for (int y = 63; y <= 65; y++)
                    for (int z = -116; z <= -115; z++) {
                        world.add(0, y, z, .35, y, z, 1, y + 1, z + 1);
                        world.add(-1, y, z, -1, y, z, -.35, y + 1, z + 1);
                    }
            if (scenario.name.equals("movement_blocked_resume_250"))
                for (int x = -1; x <= 1; x++)
                    for (int y = 63; y <= 65; y++)
                        world.add(x, y, -114, x, y, -113.7, x + 1, y + 1, -113);
            bot = player(unsafe, "GuardBot");
            leader = player(unsafe, "rocket770");
            opponent = player(unsafe, "Opponent");
            if (otherGuarded()) {
                leader.label = "OtherGuarded";
                set(leader, "entityUniqueID", UUID.randomUUID());
            }
            main = player(unsafe, "ActualMain");
            enemy = player(unsafe, "ActualOpponent");
            mc.thePlayer = bot;
            position(main, new Vec3Point(0, 63, -115), 0);
            position(enemy, new Vec3Point(0, 63, -115 + scenario.gap), 0);
            if (scenario.name.contains("defense_side"))
                position(enemy, new Vec3Point(1.8, 63, -115), 0);
            position(bot, new Vec3Point(0, 63, -115 - GuardFollow.OFFSET), 0);
            position(leader, point(main), 0);
            position(opponent, point(enemy), 0);
            if (scenario.name.contains("step_down")) {
                position(main, new Vec3Point(0, 64, -115), 0);
                position(leader, point(main), 0);
                position(bot, new Vec3Point(0, 64, -115 - GuardFollow.OFFSET), 0);
            }
            if (scenario.name.contains("running_catchup"))
                position(bot, new Vec3Point(0, 63, -118), 0);
            else if (scenario.name.contains("catchup"))
                position(bot, new Vec3Point(0, 63, -123), 0);
            if (delayedAim()) {
                main.rotationYaw = 90;
                leader.rotationYawHead = 90;
            }
            if (!scenario.sword) {
                main.inventory.currentItem = leader.inventory.currentItem = 1;
            }
            set(
                    world,
                    "playerEntities",
                    new ArrayList<EntityPlayer>(Arrays.asList(bot, leader, opponent)));
            controller = (Controller) unsafe.allocateInstance(Controller.class);
            mc.playerController = controller;
            set(
                    mc,
                    "currentServerData",
                    new ServerData("Guard latency fixture", "mc.hypixel.net", false));
            NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient handler =
                    (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
            set(handler, "netManager", manager);
            set(
                    handler,
                    "playerInfoMap",
                    new HashMap<UUID, net.minecraft.client.network.NetworkPlayerInfo>());
            set(bot, "sendQueue", handler);
            ClientSettings settings =
                    new ClientSettings(Files.createTempDirectory("guard-combat-settings").toFile());
            OptimusMod.settings = settings;
            settings.guardName = leader.label;
            settings.guardUuid = leader.getUniqueID().toString();
            settings.guardKeys = "on";
            if (scenario.name.contains("observed") || scenario.name.contains("still_noise"))
                settings.guardKeys = "off";
            if (scenario.name.contains("bystander") || scenario.name.contains("ambiguous")) {
                bystander = player(unsafe, "Bystander");
                position(bystander, new Vec3Point(1, 63, -114), 135);
                bystander.rotationYawHead = 135;
                world.playerEntities.add(bystander);
            }
            settings.pitLobby = "duelslobby9";
            settings.pitInstance = "dynamiclobbyTEST";
            settings.hypixelPit.setCornerA(new Vec3Point(-25, 62.5, -127));
            settings.hypixelPit.setCornerB(new Vec3Point(25, 78, -77));
            settings.pitEntrance = new Vec3Point(0, 63, -115);
            events = new ClientEvents();
            guard = (GuardController) get(events, "guard");
            set(guard, "listening", true);
            ((Random) get(get(events, "combat"), "random")).setSeed(settings.combatSeed);
            GuardTestInputs.install(
                    guard,
                    () -> true,
                    key -> {
                        desktopReads++;
                        if (otherGuarded()) return key == 0x57 || key == 0x20 || key == 0x01;
                        return key == 0x57 && physicalForward
                                || key == 0x53 && physicalBack
                                || key == 0x41 && physicalSide > 0
                                || key == 0x44 && physicalSide < 0
                                || key == 0x20 && physicalJump
                                || key == 0x11 && physicalForward
                                || key == 0x01 && physicalAttack;
                    });
            LobbyNavigator navigator = (LobbyNavigator) get(events, "navigator");
            set(
                    navigator,
                    "geometry",
                    new CollisionGeometry(
                            (x, y, z) -> {
                                List<CollisionGeometry.Box> boxes = new ArrayList<>();
                                List<AxisAlignedBB> nativeBoxes =
                                        world.cells.get(x + ":" + y + ":" + z);
                                if (nativeBoxes != null)
                                    for (AxisAlignedBB b : nativeBoxes)
                                        boxes.add(
                                                new CollisionGeometry.Box(
                                                        b.minX, b.minY, b.minZ, b.maxX, b.maxY,
                                                        b.maxZ));
                                return new CollisionGeometry.Cell(true, false, boxes);
                            }));
            world.combatRays = true;
            safety = (LobbySafety) get(events, "lobbySafety");
            TransitionWatch watch = new TransitionWatch(safety, manager);
            channel = new EmbeddedChannel(watch);
            set(manager, "channel", channel);
            set(events, "transitionWatch", watch);
            set(events, "sessionWorld", world);
            set(events, "sessionPlayer", bot);
            set(events, "armed", true);
            set(events, "enteredPit", true);
            set(events, "locationRoundTrip", scenario.ping);
            RunSafety run = (RunSafety) get(events, "run");
            run.startChecking(0);
            run.activate("duelslobby9", "dynamiclobbyTEST", world);
            serverMain = point(main);
            serverBot = point(bot);
            controller.attackObserver =
                    (player, target) -> {
                        if (target != opponent)
                            throw new AssertionError("guard attacked protected/other entity");
                        if (previousRequest >= 0
                                && now - previousRequest
                                        < OptimusMod.settings.combatProfile.minimumAttackTicks * 50)
                            throw new AssertionError(
                                    "guard attack pacing advanced across two phases of one physical tick");
                        previousRequest = now;
                        if (firstGuardRequest < 0) firstGuardRequest = now;
                        guardRequests++;
                        lastGuardRequest = now;
                        if (!scenario.name.contains("unacknowledged") || guardRequests > 1)
                            ordered("bot_out", serverTime(now + latency()), () -> attack("GUARD"));
                    };
        }

        Player player(Unsafe unsafe, String name) throws Exception {
            Player player = (Player) unsafe.allocateInstance(Player.class);
            player.label = name;
            player.worldObj = world;
            player.eyeHeight = player.getDefaultEyeHeight();
            set(
                    player,
                    "entityUniqueID",
                    name.equals("rocket770")
                            ? UUID.fromString(ClientSettings.DEFAULT_CONTROLLER_UUID)
                            : UUID.randomUUID());
            set(player, "foodStats", new FoodStats());
            player.capabilities = new PlayerCapabilities();
            DataWatcher watcher = new DataWatcher(player);
            watcher.addObject(6, Float.valueOf(40));
            watcher.addObject(0, Byte.valueOf((byte) 0));
            set(player, "dataWatcher", watcher);
            player.inventory = new InventoryPlayer(player);
            player.inventory.mainInventory[0] =
                    new ItemStack(new ItemSword(Item.ToolMaterial.IRON));
            return player;
        }

        int latency() {
            return Math.max(
                    0,
                    scenario.ping / 2
                            + (scenario.jitter == 0
                                    ? 0
                                    : jitter.nextInt(scenario.jitter * 2 + 1) - scenario.jitter));
        }

        int serverTime(int time) {
            return (time + 49) / 50 * 50;
        }

        void packet(int at, Runnable action) {
            packets.add(new Event(at, serial++, action));
        }

        void ordered(String stream, int at, Runnable action) {
            int arrival = Math.max(at, arrivals.containsKey(stream) ? arrivals.get(stream) : 0);
            arrivals.put(stream, arrival);
            packet(arrival, action);
        }

        void observed(Player player, Vec3Point at) {
            player.prevPosX = player.posX;
            player.prevPosY = player.posY;
            player.prevPosZ = player.posZ;
            player.setPosition(at.x, at.y, at.z);
            player.serverPosX = (int) Math.floor(at.x * 32);
            player.serverPosY = (int) Math.floor(at.y * 32);
            player.serverPosZ = (int) Math.floor(at.z * 32);
        }

        void physics(Player player, boolean forward) {
            physics(player, forward, false, 0, false);
        }

        void physics(Player player, boolean forward, boolean back, int side, boolean jump) {
            KeyBinding[] keys = {
                mc.gameSettings.keyBindForward,
                mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft,
                mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump,
                mc.gameSettings.keyBindSneak,
                mc.gameSettings.keyBindSprint
            };
            boolean[] previous = new boolean[keys.length];
            boolean[] desired = {forward, back, side > 0, side < 0, jump, false, forward};
            for (int i = 0; i < keys.length; i++) {
                previous[i] = keys[i].isKeyDown();
                KeyBinding.setKeyBindState(keys[i].getKeyCode(), desired[i]);
            }
            player.physics(mc.gameSettings);
            for (int i = 0; i < keys.length; i++)
                KeyBinding.setKeyBindState(keys[i].getKeyCode(), previous[i]);
        }

        void attack(String owner) {
            Vec3Point self = owner.equals("MAIN") ? serverMain : serverBot;
            Vec3Point eye = new Vec3Point(self.x, self.y + bot.getEyeHeight(), self.z);
            String outcome;
            if (health <= 0) outcome = "dead";
            else if (CombatAim.distanceSquared(eye, body(point(enemy)))
                    > CombatClicks.ATTACK_REACH * CombatClicks.ATTACK_REACH) {
                outcome = "range";
                rejectedRange++;
            } else if (world.rayTraceBlocks(
                            new Vec3(eye.x, eye.y, eye.z),
                            new Vec3(enemy.posX, enemy.posY + enemy.getEyeHeight(), enemy.posZ))
                    != null) outcome = "blocked";
            else if (now < nextDamageAt) {
                outcome = "immune";
                rejectedImmune++;
            } else {
                outcome = "accepted";
                if (owner.equals("MAIN")) mainHits++;
                else guardHits++;
                if (mainHits + guardHits == 1) firstOwner = owner;
                if (mainHits + guardHits == 2) {
                    secondOwner = owner;
                    secondAt = now;
                }
                health -= scenario.sword ? 6 : 1;
                nextDamageAt = now + 500;
                enemy.motionZ = scenario.kick;
                enemy.motionY = scenario.kick == 0 ? enemy.motionY : .32;
                final float damageHealth = health;
                ordered(
                        "server_in",
                        now + latency(),
                        () -> {
                            opponent.hurtTime = 10;
                            opponent.getDataWatcher()
                                    .updateObject(6, Float.valueOf(Math.max(0, damageHealth)));
                        });
            }
            trace.add(
                    scenario.name
                            + ","
                            + now
                            + ",attack,"
                            + owner
                            + ","
                            + outcome
                            + ","
                            + self.x
                            + ","
                            + self.z
                            + ","
                            + enemy.posZ
                            + ","
                            + nextDamageAt);
        }

        boolean run() throws Exception {
            for (now = 0; now <= 5000; now += 25) {
                while (!packets.isEmpty() && packets.peek().at <= now)
                    packets.remove().action.run();
                if (now % 50 == 0) {
                    if (scenario.name.equals("guarded_knockback_fighting_250") && now == 1000) {
                        main.motionZ = -.72;
                        main.motionY = .42;
                        ordered(
                                "server_in",
                                now + latency(),
                                () -> {
                                    leader.hurtTime = 10;
                                    leader.getDataWatcher().updateObject(6, Float.valueOf(34));
                                });
                    }
                    if (independentAim())
                        main.rotationYaw = now < 1000 ? 0 : now < 1400 ? 90 : now < 1800 ? -90 : 0;
                    if (airReach() && now == 450)
                        position(
                                bot,
                                new Vec3Point(0, 63, scenario.name.contains("far") ? -113.5 : -114),
                                0);
                    if (otherGuarded() && airReach() && now == 500)
                        packet(
                                serverTime(now + scenario.ping / 2) + scenario.ping / 2,
                                () -> {
                                    leader.isSwingInProgress = true;
                                    leader.swingProgressInt = 0;
                                });
                    if (delayedAim() && now == 500) main.rotationYaw = 0;
                    if (defensive() && scenario.name.contains("stale") && now == 100)
                        ordered(
                                "server_swing",
                                now + latency(),
                                () -> {
                                    opponent.rotationYawHead = 180;
                                    opponent.isSwingInProgress = true;
                                    opponent.swingProgressInt = 0;
                                });
                    physicalAttack =
                            !movementOnly()
                                    && !defensive()
                                    && now >= 500
                                    && now < (scenario.single ? 575 : 2500);
                    physicalForward =
                            scenario.moving
                                    && now >= 500
                                    && now < 2500
                                    && opponent.posZ - main.posZ > scenario.gap;
                    if (responseTest()) physicalForward = now >= 1000 && now < 1200;
                    if (movementOnly()) {
                        physicalForward = physicalBack = false;
                        physicalSide = 0;
                        physicalJump = false;
                        if (responseTest()) {
                            physicalForward = now >= 1000 && now < 1200;
                        } else if (scenario.name.contains("turns")) {
                            physicalForward = now >= 500 && now < 2500;
                            main.rotationYaw =
                                    now < 1000 ? 0 : now < 1500 ? 90 : now < 2000 ? 180 : -90;
                        } else if (scenario.name.contains("strafe")) {
                            physicalSide =
                                    now >= 500 && now < 1100
                                            ? 1
                                            : now >= 1100 && now < 1700 ? -1 : 0;
                        } else if (scenario.name.contains("jump")) {
                            physicalJump = now >= 500 && now < 575;
                        } else if (scenario.name.contains("leader_knockback")) {
                            if (now == 500) {
                                main.motionZ = .72;
                                main.motionY = .42;
                                ordered(
                                        "server_in",
                                        now + latency(),
                                        () -> {
                                            leader.hurtTime = 10;
                                            leader.getDataWatcher()
                                                    .updateObject(6, Float.valueOf(34));
                                        });
                            }
                        } else if (scenario.name.contains("running_catchup")) {
                            physicalForward = now < 2000;
                        } else if (scenario.name.contains("step_")
                                || scenario.name.contains("catchup")) {
                            physicalForward =
                                    !scenario.name.contains("catchup") && now >= 500 && now < 1500;
                        } else if (!scenario.name.contains("still_noise") && !otherGuarded()) {
                            physicalForward = now >= 500 && now < 1500;
                            physicalBack = now >= 1500 && now < 2100;
                        }
                    }
                    if (responseTest() && now == 1000) {
                        responseBotZ = bot.posZ;
                        responseOwnerZ = leader.posZ;
                    }
                    if (responseTest()
                            && scenario.name.contains("observed")
                            && physicalAttack
                            && (now - 500) % 200 == 0)
                        packet(
                                serverTime(now + scenario.ping / 2) + scenario.ping / 2,
                                () -> {
                                    leader.isSwingInProgress = true;
                                    leader.swingProgressInt = 0;
                                });
                    if (defensive() && now == 500) {
                        if (scenario.name.contains("moving_")) {
                            main.motionX = .4;
                            main.motionY = .32;
                        }
                        if (!scenario.name.contains("no_damage"))
                            ordered(
                                    "server_in",
                                    now + latency(),
                                    () -> {
                                        leader.hurtTime = 10;
                                        leader.getDataWatcher().updateObject(6, Float.valueOf(34));
                                    });
                        if (!scenario.name.contains("unknown")
                                && !scenario.name.contains("stale")) {
                            int offset =
                                    scenario.name.contains("late_swing")
                                            ? 50
                                            : scenario.name.contains("early_swing") ? -50 : 0;
                            final float attackYaw =
                                    (float)
                                            Math.toDegrees(
                                                    Math.atan2(
                                                            opponent.posX - leader.posX,
                                                            leader.posZ - opponent.posZ));
                            ordered(
                                    "server_swing",
                                    now + latency() + offset,
                                    () -> {
                                        opponent.rotationYawHead =
                                                scenario.name.contains("wrong_facing")
                                                                || scenario.name.contains(
                                                                        "late_aim")
                                                        ? 0
                                                        : attackYaw;
                                        opponent.isSwingInProgress = true;
                                        opponent.swingProgressInt = 0;
                                        if (bystander != null
                                                && scenario.name.contains("ambiguous")) {
                                            bystander.isSwingInProgress = true;
                                            bystander.swingProgressInt = 0;
                                        }
                                    });
                            if (scenario.name.contains("late_aim"))
                                ordered(
                                        "server_swing",
                                        now + latency() + 50,
                                        () -> opponent.rotationYawHead = attackYaw);
                        }
                    }
                    if (!movementOnly()
                            && !defensive()
                            && now >= 500
                            && now < 2500
                            && (scenario.single ? now == 500 : (now - 500) % 200 == 0)) {
                        Vec3Point eye =
                                new Vec3Point(
                                        main.posX, main.posY + main.getEyeHeight(), main.posZ);
                        if (CombatAim.hit(
                                        eye,
                                        body(point(opponent)),
                                        main.rotationYaw,
                                        0f,
                                        CombatClicks.ATTACK_REACH)
                                != null) {
                            mainRequests++;
                            ordered("main_out", serverTime(now + latency()), () -> attack("MAIN"));
                            if (scenario.name.contains("echo_target"))
                                packet(
                                        serverTime(now + scenario.ping / 2) + scenario.ping / 2,
                                        () -> {
                                            leader.isSwingInProgress = true;
                                            leader.swingProgressInt = 0;
                                        });
                        }
                    }
                    physics(main, physicalForward, physicalBack, physicalSide, physicalJump);
                    Vec3Point sent = point(main);
                    final float sentYaw = main.rotationYaw;
                    final boolean sentGround = main.onGround, sentSprint = main.sprint;
                    ordered(
                            "main_out",
                            serverTime(now + latency()),
                            () -> {
                                serverMain = sent;
                                serverYaw = sentYaw;
                                serverGround = sentGround;
                                serverSprint = sentSprint;
                            });
                }
                if (now % 50 == 0) {
                    physics(enemy, false);
                    Vec3Point mainPosition = serverMain, enemyPosition = point(enemy);
                    boolean leaderSprint = serverSprint,
                            grounded = enemy.onGround,
                            mainGround = serverGround;
                    float leaderYaw = serverYaw;
                    double noise =
                            scenario.name.contains("noise")
                                    ? ((now / 50) % 2 == 0 ? .025 : -.025)
                                    : 0;
                    ordered(
                            "server_in",
                            now + latency(),
                            () -> {
                                observed(
                                        leader,
                                        new Vec3Point(
                                                mainPosition.x + noise,
                                                mainPosition.y,
                                                mainPosition.z));
                                leader.sprint = leaderSprint;
                                leader.rotationYawHead = leaderYaw;
                                leader.onGround = mainGround;
                            });
                    ordered(
                            "server_in",
                            now + latency(),
                            () -> {
                                observed(opponent, enemyPosition);
                                opponent.onGround = grounded;
                            });
                }
                if (now % 50 == scenario.phase) {
                    long tick = now / 50;
                    long time = System.nanoTime() / 1000000L;
                    safety.request("mc.hypixel.net", world, time);
                    if (!safety.confirm(
                            "mc.hypixel.net",
                            world,
                            time,
                            "DUELS",
                            "duelslobby9",
                            "dynamiclobbyTEST",
                            false)) throw new AssertionError("fixture verification");
                    if (responseTest()
                            && now >= 1000
                            && observedResponseAt < 0
                            && Math.abs(leader.posZ - responseOwnerZ) > .02)
                        observedResponseAt = now;
                    set(events, "tickNo", tick);
                    guard.step(OptimusMod.settings.hypixelPit, tick, true);
                    if (responseTest()
                            && responseAt >= 0
                            && now >= 1200
                            && releaseAt < 0
                            && !mc.gameSettings.keyBindForward.isKeyDown()) releaseAt = now;
                    bot.physics(mc.gameSettings);
                    if (responseTest()
                            && now >= 1000
                            && responseAt < 0
                            && bot.posZ - responseBotZ > .02) responseAt = now;
                    if (now >= 3500
                            && (mc.gameSettings.keyBindForward.isKeyDown()
                                    || mc.gameSettings.keyBindBack.isKeyDown()
                                    || mc.gameSettings.keyBindLeft.isKeyDown()
                                    || mc.gameSettings.keyBindRight.isKeyDown())) lateMovement++;
                    if (now >= 1500
                            && now <= 2500
                            && !scenario.moving
                            && (mc.gameSettings.keyBindForward.isKeyDown()
                                    || mc.gameSettings.keyBindBack.isKeyDown()
                                    || mc.gameSettings.keyBindLeft.isKeyDown()
                                    || mc.gameSettings.keyBindRight.isKeyDown()))
                        combatCorrections++;
                    Vec3Point sent = point(bot);
                    ordered(
                            "bot_out",
                            serverTime(now + latency()),
                            () -> {
                                serverBot = sent;
                            });
                    set(events, "tickNo", tick + 1);
                    guard.step(OptimusMod.settings.hypixelPit, tick);
                    boolean phase = guard.status().contains("phase: fighting");
                    if (phase != fighting) phaseChanges++;
                    fighting = phase;
                    if (independentAim() && fighting && now >= 1000 && now <= 2000) {
                        float expected =
                                (float)
                                        Math.toDegrees(
                                                Math.atan2(
                                                        bot.posX - opponent.posX,
                                                        opponent.posZ - bot.posZ));
                        if (Math.abs(CombatTurn.wrap(bot.rotationYaw - expected)) > 45) wrongAim++;
                    }
                    String intent = (String) get(get(guard, "assist"), "observed");
                    if (intent.startsWith("windows:") && !intent.equals(lastIntent)) {
                        if (lastIntentAt >= 0 && physicalAttack && now - lastIntentAt < 200)
                            throw new AssertionError(
                                    "held-click intent repeated before four physical ticks");
                        lastIntent = intent;
                        lastIntentAt = now;
                    }
                    opponent.hurtTime = Math.max(0, opponent.hurtTime - 1);
                    if (leader.isSwingInProgress && ++leader.swingProgressInt >= 6) {
                        leader.isSwingInProgress = false;
                        leader.swingProgressInt = 0;
                    }
                    maximumSeparation =
                            Math.max(
                                    maximumSeparation,
                                    Math.sqrt(point(bot).horizontalDistanceSq(point(main))));
                    double angle = Math.toRadians(main.rotationYaw);
                    maximumAhead =
                            Math.max(
                                    maximumAhead,
                                    (bot.posX - main.posX) * -Math.sin(angle)
                                            + (bot.posZ - main.posZ) * Math.cos(angle));
                    Vec3Point mainEye =
                            new Vec3Point(main.posX, main.posY + main.getEyeHeight(), main.posZ);
                    if (!movementOnly()
                            && CombatAim.hit(mainEye, body(point(bot)), main.rotationYaw, 0, 3)
                                    != null) {
                        blockedView++;
                        blockedRun++;
                        combatViewRun = fighting ? combatViewRun + 1 : 0;
                    } else {
                        blockedRun = 0;
                        combatViewRun = 0;
                    }
                    maximumBlockedRun = Math.max(maximumBlockedRun, blockedRun);
                    maximumCombatViewRun = Math.max(maximumCombatViewRun, combatViewRun);
                    trace.add(
                            scenario.name
                                    + ","
                                    + now
                                    + ",movement,GUARD,"
                                    + guard.status().replace(',', ';')
                                    + ","
                                    + bot.posX
                                    + ","
                                    + bot.posZ
                                    + ","
                                    + enemy.posZ
                                    + ","
                                    + nextDamageAt);
                    if (!OptimusMod.settings.hypixelPit.containsBody(point(bot), .3, 1.8))
                        throw new AssertionError("guard left pit");
                }
            }
            boolean success =
                    guardHits > 0
                            && (scenario.jitter == 0 && !scenario.moving
                                    ? secondOwner.equals("GUARD")
                                    : true)
                            && maximumAhead < .8
                            && maximumSeparation
                                    < (responseTest() && scenario.name.contains("observed")
                                            ? 2
                                            : 1.3);
            double settledError =
                    Math.sqrt(
                            point(bot)
                                    .horizontalDistanceSq(
                                            GuardFollow.anchor(point(main), main.rotationYaw)));
            // Abrupt, unseen camera turns can separate the clients during the
            // round trip. Bound recovery within the existing three-block
            // approach handoff, then require actual close settling.
            // Observed-only strafe includes five ticks of unseen movement at
            // this RTT. Its separation bound includes that physical delay.
            double separationLimit =
                    scenario.name.contains("leader_knockback")
                            ? 8
                            : scenario.name.contains("turns")
                                    ? 3
                                    : scenario.name.contains("observed") || otherGuarded()
                                            ? 2
                                            : 1.3;
            if (movementOnly())
                success =
                        guardRequests == 0
                                && maximumSeparation < separationLimit
                                && settledError < .12
                                && (scenario.name.contains("catchup")
                                        ? bot.jumps >= 1
                                        : bot.jumps == (scenario.name.contains("jump") ? 1 : 0));
            if (scenario.name.startsWith("movement_step_"))
                success =
                        guardRequests == 0
                                && maximumSeparation
                                        < (scenario.name.contains("down")
                                                ? 3.25
                                                : scenario.name.contains("observed") ? 2.25 : 1.3)
                                && settledError < .12
                                && bot.jumps == 0
                                && bot.posZ > -112
                                && Math.abs(bot.posY - main.posY) < .05;
            if (scenario.name.contains("catchup"))
                success = guardRequests == 0 && bot.jumps >= 1 && settledError < .12;
            if (scenario.name.contains("running_catchup")) success &= maximumSeparation < 4.5;
            if (scenario.name.equals("blocked_flank_250"))
                success = guardRequests == 0 && guardHits == 0 && maximumSeparation < .6;
            if (scenario.name.equals("too_far_250"))
                success = guardRequests == 0 && guardHits == 0 && maximumSeparation < .6;
            if (defensive())
                success =
                        unidentified()
                                ? guardRequests == 0
                                : guardHits > 0
                                        && firstGuardRequest >= 625
                                        && firstGuardRequest
                                                <= (scenario.name.contains("behind")
                                                                || scenario.name.contains("side")
                                                        ? 1000
                                                        : 800);
            if (airReach())
                success =
                        mainHits == 0
                                && guardHits > 0
                                && maximumSeparation
                                        <= (scenario.name.contains("far") ? 1.55 : 1.05);
            if (scenario.name.equals("guarded_knockback_fighting_250"))
                success = guardHits > 0 && maximumSeparation < 8 && bot.jumps == 0;
            if (scenario.name.contains("noise")) success &= lateMovement == 0;
            if (scenario.name.startsWith("edge_") || scenario.name.startsWith("minimal_"))
                success &= combatCorrections <= 1;
            if (!movementOnly()) success &= phaseChanges <= 2;
            if (otherGuarded()) success &= desktopReads == 0;
            if (independentAim())
                success =
                        guardHits > 0
                                && wrongAim == 0
                                && phaseChanges <= 2
                                && maximumSeparation < 1.3
                                && settledError < .12;
            // Brief return shortcuts through the central view are permitted;
            // sustained obstruction while holding the side position is not.
            // Knockback can displace the owner before its position arrives.
            // Keep combat's 8-tick bound; allow up to one second to walk back
            // through view afterward instead of stealing aim to sprint away.
            if (!movementOnly())
                success &=
                        settledError < .12
                                && lastGuardRequest < 4000
                                && (independentAim()
                                        || (otherGuarded() && airReach()
                                                ? maximumCombatViewRun <= 8
                                                : scenario.name.equals(
                                                                "guarded_knockback_fighting_250")
                                                        ? maximumCombatViewRun <= 8
                                                                && maximumBlockedRun <= 20
                                                        : maximumBlockedRun <= 8));
            if (responseTest())
                success &=
                        responseAt >= 0
                                && (scenario.name.contains("observed")
                                        ? observedResponseAt >= 0
                                                && responseAt - observedResponseAt <= 50
                                        : responseAt - 1000 <= 50
                                                && releaseAt >= 0
                                                && releaseAt - 1200 <= 50);
            System.out.println(
                    scenario.name
                            + ": "
                            + (success ? "PASS" : "FAILED")
                            + " main="
                            + mainHits
                            + " guard="
                            + guardHits
                            + " second="
                            + secondOwner
                            + "@"
                            + secondAt
                            + " requests="
                            + guardRequests
                            + " immune="
                            + rejectedImmune
                            + " range="
                            + rejectedRange
                            + " separation="
                            + String.format(java.util.Locale.ROOT, "%.3f", maximumSeparation)
                            + " settled="
                            + String.format(java.util.Locale.ROOT, "%.3f", settledError)
                            + " jumps="
                            + bot.jumps
                            + " blocked_view="
                            + blockedView
                            + " late_movement="
                            + lateMovement
                            + " combat_corrections="
                            + combatCorrections
                            + " first_request="
                            + firstGuardRequest
                            + " longest_view_crossing="
                            + maximumBlockedRun
                            + " phase_changes="
                            + phaseChanges
                            + " desktop_reads="
                            + desktopReads
                            + " wrong_aim="
                            + wrongAim
                            + " combat_view_crossing="
                            + maximumCombatViewRun
                            + " response_ms="
                            + (responseAt < 0 ? -1 : responseAt - 1000)
                            + " observed_reaction_ms="
                            + (responseAt < 0 || observedResponseAt < 0
                                    ? -1
                                    : responseAt - observedResponseAt)
                            + " release_ms="
                            + (releaseAt < 0 ? -1 : releaseAt - 1200));
            return success;
        }

        @Override
        public void close() {
            guard.reset();
            channel.finish();
        }
    }

    public static void main(String[] args) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Field singleton = field(Minecraft.class, "theMinecraft");
        Object old = singleton.get(null);
        ClientSettings previousSettings = OptimusMod.settings;
        List<String> trace = new ArrayList<>();
        trace.add("case,time_ms,event,actor,outcome,x,z,target_z,server_ready_ms");
        List<String> failures = new ArrayList<>();
        List<Scenario> scenarios = new ArrayList<>();
        for (int ping : new int[] {200, 250, 300})
            for (int phase : new int[] {0, 25}) {
                scenarios.add(
                        new Scenario(
                                "sword_" + ping + "_" + phase,
                                ping,
                                phase,
                                0,
                                2,
                                0,
                                true,
                                false,
                                false));
                scenarios.add(
                        new Scenario(
                                "edge_" + ping + "_" + phase,
                                ping,
                                phase,
                                0,
                                2.9,
                                0,
                                true,
                                false,
                                false));
            }
        scenarios.add(new Scenario("single_click_250", 250, 0, 0, 2, 0, true, false, true));
        scenarios.add(new Scenario("air_reach_250", 250, 0, 0, 3.6, 0, true, false, true));
        scenarios.add(new Scenario("air_reach_far_250", 250, 0, 0, 4.1, 0, true, false, true));
        scenarios.add(
                new Scenario("other_guarded_air_reach_250", 250, 0, 0, 3.6, 0, true, false, true));
        scenarios.add(
                new Scenario("aim_update_air_reach_250", 250, 0, 0, 3.6, 0, true, false, true));
        scenarios.add(
                new Scenario("combat_head_independent_250", 250, 0, 0, 2, 0, true, false, false));
        scenarios.add(
                new Scenario(
                        "combat_head_independent_edge_250", 250, 0, 0, 3.1, 0, true, false, false));
        scenarios.add(
                new Scenario("combat_response_local_250", 250, 0, 0, 2, 0, true, false, false));
        scenarios.add(
                new Scenario("combat_response_observed_250", 250, 0, 0, 2, 0, true, false, false));
        scenarios.add(
                new Scenario("movement_response_local_250", 250, 0, 0, 2, 0, true, false, false));
        scenarios.add(new Scenario("unacknowledged_air_250", 250, 0, 0, 3.6, 0, true, false, true));
        scenarios.add(new Scenario("echo_target_single_250", 250, 0, 0, 2, 0, true, false, true));
        scenarios.add(new Scenario("damage_target_single_250", 250, 0, 0, 2, 0, true, false, true));
        scenarios.add(new Scenario("knockback_single_250", 250, 0, 0, 1.9, .28, true, true, true));
        scenarios.add(
                new Scenario(
                        "guarded_knockback_fighting_250", 250, 0, 0, 2, 0, true, false, false));
        scenarios.add(new Scenario("fist_250_jitter", 250, 25, 25, 2, 0, false, false, false));
        scenarios.add(new Scenario("knockback_250", 250, 0, 0, 1.9, .28, true, true, false));
        scenarios.add(
                new Scenario("knockback_250_jitter", 250, 25, 25, 1.9, .28, true, true, false));
        scenarios.add(new Scenario("blocked_flank_250", 250, 0, 0, 2.9, 0, true, false, false));
        scenarios.add(new Scenario("minimal_step_250", 250, 0, 0, 3.1, 0, true, false, false));
        scenarios.add(new Scenario("too_far_250", 250, 0, 0, 4.2, 0, true, false, false));
        for (String defense :
                new String[] {
                    "normal",
                    "unknown",
                    "wrong_facing",
                    "no_damage",
                    "ambiguous",
                    "bystander",
                    "late_swing",
                    "early_swing",
                    "stale",
                    "behind",
                    "side",
                    "moving_knockback",
                    "moving_late_aim"
                })
            scenarios.add(
                    new Scenario(
                            "defense_" + defense + "_250",
                            250,
                            0,
                            0,
                            defense.equals("behind") ? -1.8 : 2,
                            0,
                            true,
                            false,
                            false));
        for (String movement :
                new String[] {
                    "turns",
                    "strafe",
                    "jump",
                    "blocked_resume",
                    "still_noise",
                    "strafe_observed",
                    "strafe_observed_noise",
                    "strafe_keys_noise"
                })
            scenarios.add(
                    new Scenario(
                            "movement_" + movement + "_250", 250, 0, 0, 2, 0, true, false, false));
        for (String input : new String[] {"keys", "observed"})
            scenarios.add(
                    new Scenario(
                            "movement_leader_knockback_" + input + "_250",
                            250,
                            0,
                            0,
                            2,
                            0,
                            true,
                            false,
                            false));
        for (String input : new String[] {"keys", "observed"})
            for (String edge : new String[] {"step_down", "step_up"})
                scenarios.add(
                        new Scenario(
                                "movement_" + edge + "_" + input + "_250",
                                250,
                                0,
                                0,
                                2,
                                0,
                                true,
                                false,
                                false));
        scenarios.add(new Scenario("movement_catchup_250", 250, 0, 0, 2, 0, true, false, false));
        for (String input : new String[] {"keys", "observed"})
            scenarios.add(
                    new Scenario(
                            "movement_running_catchup_" + input + "_250",
                            250,
                            0,
                            0,
                            2,
                            0,
                            true,
                            false,
                            false));
        for (String movement : new String[] {"other_guarded", "other_guarded_strafe"})
            scenarios.add(
                    new Scenario(
                            "movement_" + movement + "_250", 250, 0, 0, 2, 0, true, false, false));
        try {
            for (Scenario scenario : scenarios)
                try (Simulation simulation = new Simulation(scenario, unsafe, trace)) {
                    if (!simulation.run()) failures.add(scenario.name);
                }
        } finally {
            singleton.set(null, old);
            OptimusMod.settings = previousSettings;
            Files.write(
                    Paths.get("diagnostics/guard-combat-latency.csv"),
                    String.join("\n", trace).getBytes(StandardCharsets.UTF_8));
        }
        if (!failures.isEmpty())
            throw new AssertionError("Guard latency/combat scenarios failed: " + failures);
        System.out.println(
                "PASS: "
                        + scenarios.size()
                        + " delayed guard combat scenarios with native movement and modeled server damage acceptance");
    }
}