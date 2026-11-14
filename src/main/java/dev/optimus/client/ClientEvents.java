package dev.optimus.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.optimus.client.guard.GuardController;
import dev.optimus.client.learning.CombatTelemetry;
import dev.optimus.core.combat.KillFeed;
import dev.optimus.core.control.ChatMessage;
import dev.optimus.core.control.RemoteCommand;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.LobbyTravel;
import dev.optimus.core.safety.ControlPolicy;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;
import dev.optimus.core.safety.ServerSafety;
import dev.optimus.learning.LearningSession;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Verify a Duels lobby, navigate, fight, and recover actual pit deaths/respawns. */
public final class ClientEvents {
    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random patrolRandom = new Random();
    private final NavigationOverlay navigationOverlay = new NavigationOverlay();
    private final LobbySafety lobbySafety = new LobbySafety();
    private final RunSafety run = new RunSafety();
    private final RemoteControl remoteControl = new RemoteControl();
    private final HeadlessRecovery recovery;
    private final HeadlessRecovery.Host recoveryHost = new HeadlessRecovery.Host() {
        @Override
        public boolean connected() {
            return watchReady() && mc.thePlayer != null && mc.theWorld != null
                    && ServerSafety.isHypixel(connectedServer()) && now() >= settleUntil;
        }

        @Override
        public void probe() {
            sendLocationRequest();
        }

        @Override
        public void lobbyCommand(String command) {
            sendLobbyCommand(command);
        }

        @Override
        public void resume() {
            start(true, true);
            if (!armed) recovery.cancel();
        }

        @Override
        public void fail(String reason) {
            stop(reason);
        }
    };
    private final PitRegion lobbyTravelArea = LobbyTravel.area();
    private final LobbyNavigator navigator = new LobbyNavigator(mc);
    private CombatTrace trace;
    private boolean guiPaused;
    private boolean armed, startRequested, remoteStartRequested, announceVerification, enteredPit;
    private boolean returningAfterRespawn;
    private long tickNo, settleUntil;
    private long seenSerial, seenHard, seenFatal;
    private TransitionWatch transitionWatch;
    private NetworkManager watchedManager;
    private Object sessionWorld;
    private EntityPlayer sessionPlayer;
    private Vec3Point entryGoal;
    private Vec3Point roamGoal;
    private long nextRoamAt, roamUntil;
    private long nextEntryResolveAt;
    private long lastChatAt = Long.MIN_VALUE;
    private String lastRemote = "none";
    private String lastRemoteDetail = "No controller command observed yet.";
    private long lastRemoteNoticeAt = Long.MIN_VALUE;

    private final MovementInput input;
    private final CombatController combat;
    private final NavigationController navigation;
    private final LearningSession learning;
    private final CombatTelemetry telemetry;
    private final GuardController guard;
    private final GuardBridge guardHost;
    private int locationRoundTrip;
    private long locationSentAt;
    private final KillFeed killFeed = new KillFeed();

    ClientEvents() {
        this(new HeadlessRecovery(OptimusMod.runtime.headless(), Minecraft.getMinecraft()));
    }

    ClientEvents(HeadlessRecovery recovery) {
        this.recovery = recovery;
        input = new MovementInput(mc);
        learning =
                new LearningSession(new java.io.File(mc.mcDataDir, "config"), OptimusMod.VERSION);
        telemetry = new CombatTelemetry(mc, learning.recording());
        combat = new CombatController(mc, navigator, input, this, learning, telemetry);
        navigation = new NavigationController(mc, navigator, input, this);
        GuardNavigationAdapter guardQueries = new GuardNavigationAdapter(mc, navigator);
        guardHost = new GuardBridge(mc, this, input, combat, navigator, guardQueries, telemetry);
        guard =
                new GuardController(
                        guardHost, mc, guardQueries, combat.clicks(), () -> OptimusMod.settings);
    }

    String guardCommand(String[] args) {
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status"))
            return guard.status();
        if (args.length == 2) return guard.configure(args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("keys"))
            return guard.keys(args[2].toLowerCase(java.util.Locale.ROOT));
        if (args.length == 3
                && args[1].equalsIgnoreCase("share")
                && (args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off")))
            return guard.share(args[2].equalsIgnoreCase("on"));
        return "Usage: /o guard <player>|off|status | keys auto|on|off|bind | share on|off";
    }

    String guardSummary() {
        return guard.summary();
    }

    void startGuardIfNeeded() {
        if (!armed || run.phase() == RunSafety.Phase.OFF) requestStart(false);
    }

    boolean protectsGuardedPlayer(EntityPlayer player) {
        return guard.protects(player);
    }

    String guardLobby() {
        return lobbySafety.lobby();
    }

    String guardInstance() {
        return lobbySafety.instance();
    }

    int guardPingMillis() {
        NetworkPlayerInfo info =
                mc.thePlayer == null || mc.getNetHandler() == null
                        ? null
                        : mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return Math.max(locationRoundTrip, info == null ? 0 : info.getResponseTime());
    }

    void controlOptionsChanged() {
        guard.reset();
        input.clearCombatInput();
        releaseKeys();
        navigator.reset();
        navigation.resetSteering();
        combat.reconfigure(OptimusMod.settings.combatSeed, tickNo);
        telemetry.reset();
        learning.recording().end(tickNo, "settings_changed");
        roamGoal = null;
        nextRoamAt = roamUntil = 0;
    }

    @SubscribeEvent
    public void onGuardedAttack(net.minecraftforge.event.entity.player.AttackEntityEvent event) {
        guard.attack(event);
    }

    @SubscribeEvent
    public void onGuardPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.START
                && event.player == mc.thePlayer
                && enteredPit
                && guard.enabled()
                && controlAllowed()) guard.step(OptimusMod.settings.hypixelPit, tickNo, true);
    }

    String mlCommand(String value) {
        return learning.mlCommand(value);
    }

    void closeRecording() {
        learning.shutdown();
    }

    String loggingCommand(String value) {
        return learning.loggingCommand(value);
    }

    long tick() {
        return tickNo;
    }

    boolean running() {
        return armed;
    }

    CombatTrace trace() {
        return trace;
    }

    LobbyNavigator.Status navigate(Vec3Point goal, PitRegion area, boolean target) {
        return navigation.navigate(goal, area, target, combat);
    }

    LobbyNavigator.Status navigateStep(Vec3Point goal, PitRegion area, LobbyNavigator.Step step) {
        return navigation.navigateStep(goal, area, step, combat);
    }

    void cancelRoaming() {
        roamGoal = null;
        nextRoamAt = 0;
    }

    private long now() {
        return System.nanoTime() / 1000000L;
    }

    String connectedServer() {
        ServerData data = mc.getCurrentServerData();
        return data == null ? "" : data.serverIP;
    }

    private NetworkManager networkManager() {
        return mc.getNetHandler() == null ? null : mc.getNetHandler().getNetworkManager();
    }

    private boolean watchReady() {
        return transitionWatch != null && transitionWatch.ready(networkManager());
    }

    private void ensureTransitionWatch() {
        NetworkManager manager = networkManager();
        if (manager == watchedManager) return;
        if (recovery.interrupt(now())) stopInternal("Connection changed; locating lobby", true);
        else stop("Connection changed");
        lobbySafety.invalidate();
        watchedManager = manager;
        transitionWatch = null;
        if (manager == null || mc.isSingleplayer()) return;
        try {
            transitionWatch = TransitionWatch.install(lobbySafety, manager);
        } catch (RuntimeException error) {
            OptimusMod.notice("Safety observer unavailable: " + error.getMessage());
        }
    }

    boolean isVerifiedLobby() {
        return !mc.isSingleplayer()
                && watchReady()
                && lobbySafety.verified(connectedServer(), mc.theWorld, now());
    }

    private boolean boundariesBound() {
        return isVerifiedLobby() && OptimusMod.settings.pitCalibrated();
    }

    private boolean alive() {
        return mc.thePlayer != null && mc.thePlayer.isEntityAlive() && mc.thePlayer.getHealth() > 0;
    }

    private boolean playerInPit() {
        return mc.thePlayer != null
                && OptimusMod.settings.hypixelPit.containsBody(position(mc.thePlayer), 0.3, 1.8);
    }

    boolean controlAllowed() {
        return run.mayControl()
                && mc.theWorld == sessionWorld
                && mc.thePlayer == sessionPlayer
                && ControlPolicy.allowControl(
                        armed,
                        isVerifiedLobby(),
                        boundariesBound(),
                        playerInPit(),
                        alive(),
                        mc.currentScreen != null);
    }

    boolean navigationAllowed(PitRegion area) {
        return run.mayControl()
                && mc.theWorld == sessionWorld
                && mc.thePlayer == sessionPlayer
                && OptimusMod.settings.arenaConfigured()
                && ControlPolicy.allowNavigation(
                        armed,
                        isVerifiedLobby(),
                        boundariesBound(),
                        mc.thePlayer != null && area.containsBody(position(mc.thePlayer), 0.3, 1.8),
                        alive(),
                        mc.currentScreen != null);
    }

    boolean targetAllowed(PitRegion pit, EntityPlayer target) {
        return target != null
                && target != mc.thePlayer
                && !OptimusMod.settings.exceptions.contains(target.getName())
                && target.isEntityAlive()
                && target.worldObj == mc.theWorld
                && pit.containsBody(position(target), 0.3, 1.8)
                && !guard.protects(target)
                && (guard.enabled()
                        ? guard.permits(target, guard.tick())
                        : OptimusMod.settings.targets(target.getName()));
    }

    private Vec3Point position(EntityPlayer entity) {
        return PlayerGeometry.position(entity);
    }

    void releaseKeys() {
        combat.release();
        navigation.resetSteering();
        input.releaseMovement();
        if (armed) input.clearCombatInput();
    }

    void clearVisuals() {
        navigationOverlay.clear();
        navigator.clearDebug();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        if (!armed || mc.currentScreen != null || event.button < 0) return;
        int code = event.button - 100;
        if (code == mc.gameSettings.keyBindAttack.getKeyCode()
                || code == mc.gameSettings.keyBindUseItem.getKeyCode()) {
            event.setCanceled(true);
            input.clearCombatInput();
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (armed && mc.currentScreen == null) input.clearCombatInput();
    }

    private void parkLobbyHotbar() {
        if (mc.thePlayer == null
                || mc.currentScreen != null
                || mc.theWorld != sessionWorld
                || !run.mayControl() && run.phase() != RunSafety.Phase.RECOVERING
                || controlAllowed()) return;
        combat.resetEquipment();
        if (CombatEquipment.selectEmpty(mc.thePlayer)) mc.playerController.updateController();
    }

    private void observePitReturn() {
        if (!enteredPit || mc.theWorld != sessionWorld || !alive() || playerInPit()) return;
        telemetry.death(tickNo, "self_death_pit_return");
        enteredPit = false;
        returningAfterRespawn = true;
        releaseKeys();
        guard.suspend();
        navigator.reset();
        combat.resetFocus();
        combat.deferClicks(tickNo);
        entryGoal = roamGoal = null;
        nextEntryResolveAt = nextRoamAt = roamUntil = 0;
        traceEvent("pit return: returning to entry; clearing lobby menu if open");
    }

    private void closeRespawnMenu() {
        if (returningAfterRespawn && run.mayControl() && enteredPit && playerInPit())
            returningAfterRespawn = false;
        if (!RemoteControl.closeAfterRespawn(returningAfterRespawn, mc.currentScreen)
                || mc.thePlayer == null
                || mc.theWorld != sessionWorld
                || !run.mayControl() && run.phase() != RunSafety.Phase.RECOVERING
                || mc.thePlayer.openContainer == null
                || mc.thePlayer.openContainer == mc.thePlayer.inventoryContainer
                || mc.thePlayer.openContainer.windowId <= 0) return;
        // Close through vanilla so the server receives the container close too.
        // Check both tick phases: a menu can arrive between START and END or
        // after verification has already resumed return-to-pit navigation.
        mc.thePlayer.closeScreen();
        input.clearCombatInput();
        traceEvent("respawn: closed lobby menu; returning to pit");
    }

    void traceEvent(String event) {
        if (trace == null) return;
        try {
            trace.row(
                    tickNo,
                    event,
                    connectedServer(),
                    OptimusMod.settings.combatProfile.id(),
                    OptimusMod.settings.combatSeed,
                    "",
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    false);
        } catch (IOException error) {
            stop("Trace write failed: " + error.getMessage());
        }
    }

    void stop(String explanation) {
        stopInternal(explanation, false);
    }

    /** Releases gameplay immediately; controlled interruptions may retain explicit run intent. */
    private void stopInternal(String explanation, boolean preserveRecovery) {
        boolean recovering = recovery.recovering();
        if (!preserveRecovery) recovery.cancel();
        boolean previous = armed;
        if (previous) input.clearCombatInput();
        armed = false;
        startRequested = remoteStartRequested = false;
        enteredPit = false;
        sessionWorld = null;
        sessionPlayer = null;
        entryGoal = null;
        navigationOverlay.clear();
        run.stop();
        releaseKeys();
        // Disable control and release keys before closing input/recording transports.
        guard.reset();
        learning.close();
        telemetry.reset();
        killFeed.reset();
        navigator.reset();
        combat.resetFocus();
        navigation.resetSteering();
        roamGoal = null;
        nextRoamAt = roamUntil = 0;
        guiPaused = false;
        navigation.clearIssue();
        returningAfterRespawn = false;
        mc.gameSettings.pauseOnLostFocus =
                remoteControl.restorePause(mc.gameSettings.pauseOnLostFocus);
        if (trace != null) {
            try {
                trace.row(
                        tickNo,
                        "stop: " + explanation,
                        connectedServer(),
                        OptimusMod.settings.combatProfile.id(),
                        OptimusMod.settings.combatSeed,
                        "",
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false,
                        false,
                        false);
            } catch (IOException error) {
                OptimusMod.notice("Trace write failed: " + error.getMessage());
            }
            try {
                trace.close();
            } catch (IOException error) {
                OptimusMod.notice("Trace close failed: " + error.getMessage());
            }
            trace = null;
        }
        // The stop command supplies its own acknowledgement.
        if ((previous || recovering) && !"Manual stop".equals(explanation))
            OptimusMod.notice("Stopped: " + explanation);
    }

    void requestStart(boolean fromController) {
        startRequested = true;
        remoteStartRequested = fromController;
    }

    void processRequestedStart() {
        if (startRequested) {
            boolean fromController = remoteStartRequested;
            startRequested = remoteStartRequested = false;
            start(fromController);
        }
    }

    void targetsChanged() {
        combat.targetsChanged();
        roamGoal = null;
        nextRoamAt = 0;
        if (mc.thePlayer != null && mc.thePlayer.onGround && !navigator.jumpInProgress()) {
            navigator.reset();
            input.releaseMovement();
            navigation.resetSteering();
        }
    }

    void say(String raw) {
        String message = ChatMessage.validate(raw);
        if (!ControlPolicy.allowSpeech(isVerifiedLobby(), mc.thePlayer != null)) {
            throw new IllegalArgumentException(
                    "Say blocked: run /o locraw in a Duels lobby, then wait for it to finish.");
        }
        long current = now();
        if (lastChatAt != Long.MIN_VALUE && current - lastChatAt < 1000) {
            throw new IllegalArgumentException("Wait a second before sending another message.");
        }
        if (!isVerifiedLobby())
            throw new IllegalArgumentException("Say blocked: lobby verification changed.");
        mc.thePlayer.sendChatMessage(message);
        lastChatAt = current;
    }

    void start(boolean fromController) {
        start(fromController, false);
    }

    private void start(boolean fromController, boolean resuming) {
        stopInternal("New session", resuming);
        if (fromController && RemoteControl.closeOnStart(mc.currentScreen))
            mc.displayGuiScreen(null);
        recovery.prepareStart(fromController);
        if (!watchReady()
                || mc.isSingleplayer()
                || !ServerSafety.isHypixel(connectedServer())
                || !alive()
                || mc.theWorld == null
                || mc.currentScreen != null) {
            OptimusMod.notice(
                    "Denied: "
                            + (mc.currentScreen != null
                                    ? "bot screen "
                                            + mc.currentScreen.getClass().getSimpleName()
                                            + " is open"
                                    : "join Hypixel with an alive player and working safety observer")
                            + ".");
            return;
        }
        if (!OptimusMod.settings.arenaConfigured()) {
            OptimusMod.notice("Denied: calibrate Duels lobby pit and entrance first.");
            return;
        }
        try {
            trace = new CombatTrace(OptimusMod.settings.traceDirectory());
        } catch (IOException error) {
            OptimusMod.notice("Denied: cannot create trace: " + error.getMessage());
            return;
        }
        mc.gameSettings.pauseOnLostFocus =
                remoteControl.keepActive(mc.gameSettings.pauseOnLostFocus);
        armed = true;
        if (!resuming) recovery.arm();
        learning.start();
        run.startChecking(now());
        patrolRandom.setSeed(OptimusMod.settings.combatSeed);
        combat.begin(OptimusMod.settings.combatSeed, tickNo);
        seenSerial = transitionWatch.serial();
        seenHard = transitionWatch.hardSerial();
        seenFatal = transitionWatch.fatalSerial();
        traceEvent("start: verifying current location");
        if (!armed) return;
        settleUntil = now();
        sendLocationRequest();
        if (armed) OptimusMod.notice("Checking lobby...");
    }

    private void sendLobbyCommand(String command) {
        if ((!armed && !recovery.recovering()) || !watchReady()
                || !transitionWatch.authorizeLobbyCommand(command)) {
            stop("Cannot issue authorized lobby command");
            return;
        }
        releaseKeys();
        navigator.reset();
        enteredPit = false;
        lobbySafety.invalidate();
        settleUntil = now() + 1500;
        mc.thePlayer.sendChatMessage(command);
        traceEvent("lobby command: " + command);
    }

    void printStatus() {
        OptimusMod.notice(learning.status());
        OptimusMod.notice(guard.summary());
        OptimusMod.notice(OptimusMod.settings.configStatus());
        ClientSettings s = OptimusMod.settings;
        OptimusMod.notice(
                "Bot: "
                        + runLabel()
                        + "; verified: "
                        + isVerifiedLobby()
                        + "; checking: "
                        + lobbySafety.pending());
        OptimusMod.notice(
                "Lobby: "
                        + lobbySafety.lobby()
                        + " / "
                        + lobbySafety.instance()
                        + "; observer: "
                        + watchReady());
        OptimusMod.notice(
                "Profile: "
                        + s.combatProfile.id()
                        + "; calibrated pit usable: "
                        + boundariesBound()
                        + "; arena configured: "
                        + s.arenaConfigured());
        OptimusMod.notice(
                "Pit A=" + point(s.hypixelPit.cornerA()) + " B=" + point(s.hypixelPit.cornerB()));
        OptimusMod.notice(
                "Travel: all safe loaded Duels lobby terrain; entrance="
                        + point(s.pitEntrance)
                        + "; current position="
                        + (mc.thePlayer == null ? "unset" : point(position(mc.thePlayer))));
        OptimusMod.notice(
                "Targets: "
                        + s.targetSummary()
                        + "; weapon="
                        + (s.swordEnabled ? "sword" : "fist (slot 2)")
                        + "; calibration origin: "
                        + s.pitLobby
                        + " / "
                        + s.pitInstance);
        OptimusMod.notice(
                "Spawn="
                        + point(s.spawnPoint)
                        + "; drop rim="
                        + point(s.dropRim)
                        + "; floor="
                        + s.dropFloor
                        + "; live landing="
                        + point(entryGoal));
        OptimusMod.notice(
                "Controller="
                        + s.controllerName
                        + " / "
                        + s.controllerUuid
                        + "; operators="
                        + s.operators.keySet()
                        + "; remote="
                        + lastRemote);
        if (armed)
            OptimusMod.notice(
                    "Activity: "
                            + activityLabel()
                            + (combat.detail().isEmpty() ? "" : "; " + combat.detail()));
    }

    private String point(Vec3Point p) {
        return p == null ? "unset" : p.serialize();
    }

    void recordPit(boolean first) {
        if (!isVerifiedLobby() || !alive()) {
            OptimusMod.notice("Verify a Duels lobby with /o locraw before calibration.");
            return;
        }
        stop("Pit calibration changed");
        ClientSettings s = OptimusMod.settings;
        if (first) {
            s.hypixelPit.clear();
            s.navigationArea.clear();
            s.pitEntrance = null;
            s.dropRim = s.spawnPoint = null;
            s.pitLobby = lobbySafety.lobby();
            s.pitInstance = lobbySafety.instance();
        } else if (s.hypixelPit.cornerA() == null
                || !s.pitLobby.equals(lobbySafety.lobby())
                || !s.pitInstance.equals(lobbySafety.instance())) {
            OptimusMod.notice("Record A in this lobby first.");
            return;
        }
        Vec3Point p =
                new Vec3Point(
                        mc.thePlayer.posX,
                        mc.thePlayer.posY + (first ? -1.5 : 4.0),
                        mc.thePlayer.posZ);
        if (first) s.hypixelPit.setCornerA(p);
        else s.hypixelPit.setCornerB(p);
        s.save();
        OptimusMod.notice("Saved pit " + (first ? "A" : "B") + ": " + p.serialize());
    }

    void recordEntrance() {
        if (!isVerifiedLobby() || !boundariesBound() || !alive() || !mc.thePlayer.onGround) {
            OptimusMod.notice(
                    "Stand on the ground just inside the pit entrance in a verified Duels lobby.");
            return;
        }
        stop("Entrance changed");
        ClientSettings s = OptimusMod.settings;
        Vec3Point p =
                new Vec3Point(
                        MathHelper.floor_double(mc.thePlayer.posX) + 0.5,
                        Math.round(mc.thePlayer.posY * 2) / 2.0,
                        MathHelper.floor_double(mc.thePlayer.posZ) + 0.5);
        if (!s.hypixelPit.containsBody(p, 0.3, 1.8)) {
            OptimusMod.notice("Entrance must be inside the pit.");
            return;
        }
        s.pitEntrance = p;
        s.dropRim = null;
        s.save();
        OptimusMod.notice("Saved entrance: " + p.serialize());
    }

    void applySuppliedArena() {
        if (!isVerifiedLobby() || !alive()) {
            OptimusMod.notice("Verify a Duels lobby with /o locraw first.");
            return;
        }
        stop("Supplied coordinate preset installed");
        OptimusMod.settings.applySuppliedArena(lobbySafety.lobby(), lobbySafety.instance());
        OptimusMod.settings.save();
        OptimusMod.notice("Pit preset loaded. F8 starts.");
    }

    void clearController() {
        stop("Remote controller disabled");
        OptimusMod.settings.controllerName = OptimusMod.settings.controllerUuid = "";
        OptimusMod.settings.operators.clear();
        OptimusMod.settings.save();
        lastRemote = "disabled";
        OptimusMod.notice("Controller off.");
    }

    void setController(String username) {
        if (!username.matches("[A-Za-z0-9_]{3,16}")
                || !isVerifiedLobby()
                || mc.getNetHandler() == null
                || mc.thePlayer == null) {
            OptimusMod.notice("Pair in a verified Duels lobby: /o controller <main account name>");
            return;
        }
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(username);
        if (info == null || info.getGameProfile().getId() == null) {
            OptimusMod.notice("Your main account needs to be in this lobby to pair.");
            return;
        }
        if (info.getGameProfile().getId().equals(mc.thePlayer.getUniqueID())
                || username.equalsIgnoreCase(mc.thePlayer.getName())) {
            OptimusMod.notice("Pair your main account, not this bot.");
            return;
        }
        stop("Remote controller paired");
        OptimusMod.settings.controllerName = info.getGameProfile().getName();
        OptimusMod.settings.controllerUuid = info.getGameProfile().getId().toString();
        OptimusMod.settings.operators.clear();
        OptimusMod.settings.save();
        lastRemote = "paired; waiting for !o command";
        OptimusMod.notice(
                "Controller: "
                        + OptimusMod.settings.controllerName
                        + ". Use !o from that account.");
    }

    String addOperator(String username) {
        ClientSettings settings = OptimusMod.settings;
        if (settings.controllerName.isEmpty() || settings.controllerUuid.isEmpty())
            return "Pair a main controller first: /o controller <name>";
        if (!username.matches("[A-Za-z0-9_]{3,16}")
                || !isVerifiedLobby()
                || mc.getNetHandler() == null
                || mc.thePlayer == null)
            return "Add an operator in a verified Duels lobby: /o operator add <name>";
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(username);
        if (info == null || info.getGameProfile().getId() == null)
            return "That player must be in this lobby to pair.";
        String uuid = info.getGameProfile().getId().toString(),
                name = info.getGameProfile().getName();
        if (info.getGameProfile().getId().equals(mc.thePlayer.getUniqueID())
                || name.equalsIgnoreCase(mc.thePlayer.getName()))
            return "Choose another player, not this bot.";
        if (uuid.equalsIgnoreCase(settings.controllerUuid)
                || name.equalsIgnoreCase(settings.controllerName))
            return "That player is already the main controller.";
        settings.operators.put(name, uuid);
        settings.save();
        return "Operator added: " + name + ".";
    }

    void weaponChanged() {
        combat.resetEquipment();
        if (isVerifiedLobby()
                && playerInPit()
                && alive()
                && mc.playerController != null
                && CombatEquipment.select(mc.thePlayer, OptimusMod.settings.swordEnabled))
            mc.playerController.updateController();
    }

    private void remoteReply(String text) {
        if (!ControlPolicy.allowSpeech(isVerifiedLobby(), mc.thePlayer != null)) {
            OptimusMod.notice("Couldn't reply: lobby check needed.");
            return;
        }
        String reply = "Optimus: " + text.replace("/o start", "!o start");
        if (reply.length() > 100) reply = reply.substring(0, 97) + "...";
        try {
            mc.thePlayer.sendChatMessage(ChatMessage.validate(reply));
        } catch (IllegalArgumentException error) {
            OptimusMod.notice("Remote reply blocked: " + error.getMessage());
        }
    }

    void printRemoteDebug() {
        OptimusMod.notice("Remote: " + lastRemoteDetail);
        OptimusMod.notice(
                "Rejected controller headers are recorded as OPTIMUS_REMOTE in logs/latest.log.");
    }

    private void remoteRejected(IChatComponent message, byte type, String reason) {
        lastRemote = "ignored: " + reason;
        lastRemoteDetail = "type=" + type + "; " + reason;
        org.apache.logging.log4j.LogManager.getLogger("Optimus")
                .info(
                        "OPTIMUS_REMOTE type="
                                + type
                                + " reason="
                                + reason
                                + " json="
                                + RemoteChat.diagnosticJson(message));
        long current = now();
        if (lastRemoteNoticeAt == Long.MIN_VALUE || current - lastRemoteNoticeAt >= 2000) {
            OptimusMod.notice(
                    "Controller command ignored: " + reason + ". See /o controller debug.");
            lastRemoteNoticeAt = current;
        }
    }

    private boolean remoteChat(IChatComponent message, byte type) {
        ClientSettings settings = OptimusMod.settings;
        if (RemoteChat.commandBody(message) == null
                || settings.controllerName.isEmpty()
                || settings.controllerUuid.isEmpty()) return false;
        String hint = RemoteChat.headerHint(message);
        boolean ownerHint =
                settings.controllerName.equalsIgnoreCase(hint)
                        || hint != null && settings.operators.containsKey(hint);
        if (mc.thePlayer == null
                || mc.getNetHandler() == null
                || mc.theWorld == null
                || !watchReady()
                || mc.isSingleplayer()) {
            if (ownerHint) remoteRejected(message, type, "connection/safety observer unavailable");
            return false;
        }
        RemoteChat.Envelope envelope = RemoteChat.extract(message);
        if (envelope == null) {
            if (ownerHint) remoteRejected(message, type, "unrecognized sender metadata");
            return false;
        }
        if (envelope.sender.equalsIgnoreCase(mc.thePlayer.getName())) return false;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(envelope.sender);
        String uuid =
                info == null || info.getGameProfile().getId() == null
                        ? null
                        : info.getGameProfile().getId().toString();
        boolean owner =
                RemoteCommand.ownerMatches(
                        settings.controllerName, settings.controllerUuid, envelope.sender, uuid);
        if (!owner && !settings.operatorMatches(envelope.sender, uuid)) {
            if (ownerHint)
                remoteRejected(
                        message,
                        type,
                        info == null
                                ? "controller missing from player list"
                                : "controller/operator UUID mismatch");
            return false;
        }
        try {
            String[] args = RemoteCommand.parse(envelope.body, mc.thePlayer.getName(), !owner);
            if (args == null) return false;
            lastRemote = args[0];
            lastRemoteDetail =
                    "Authenticated " + args[0] + " from " + envelope.sender + "; type=" + type;
            List<String> replies = new OptimusCommand().processRemote(mc.thePlayer, args);
            if (!RemoteControl.replyTo(args[0])) return true;
            if (args[0].equals("say")) {
                if (!replies.isEmpty()) remoteReply(replies.get(replies.size() - 1));
            } else if (args[0].equals("status")) {
                remoteReply(
                        runLabel()
                                + " | "
                                + (guard.enabled()
                                        ? "guarding " + settings.guardName
                                        : settings.targetAll
                                                ? "all pit players"
                                                : settings.whitelist.all().size() + " targets")
                                + " | "
                                + (settings.swordEnabled ? "sword" : "fist")
                                + " | "
                                + settings.combatProfile.id()
                                + " | "
                                + lobbySafety.lobby());
            } else if (args[0].equals("list")) {
                remoteReply("Targets: " + settings.targetSummary());
            } else if (args[0].equals("help")) {
                remoteReply(
                        "!o start|stop|status | guard <player>/off | target | weapon sword/fist");
            } else if (!replies.isEmpty()) remoteReply(replies.get(replies.size() - 1));
            else remoteReply("Done.");
            return true;
        } catch (IllegalArgumentException error) {
            lastRemote = "rejected: " + error.getMessage();
            lastRemoteDetail = lastRemote;
            remoteReply(error.getMessage());
            return true;
        }
    }

    void requestLocraw() {
        releaseKeys();
        navigator.reset();
        lobbySafety.invalidate();
        announceVerification = true;
        sendLocationRequest();
    }

    private void sendLocationRequest() {
        if (!watchReady()
                || mc.theWorld == null
                || mc.thePlayer == null
                || mc.isSingleplayer()
                || !ServerSafety.isHypixel(connectedServer())) return;
        releaseKeys();
        lobbySafety.request(connectedServer(), mc.theWorld, now());
        locationSentAt = now();
        mc.thePlayer.sendChatMessage("/locraw");
    }

    private void refreshLocation() {
        if (watchReady()
                && !mc.isSingleplayer()
                && mc.thePlayer != null
                && lobbySafety.refresh(connectedServer(), mc.theWorld, now()))
            mc.thePlayer.sendChatMessage("/locraw");
    }

    private String stringField(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (!RemoteChat.chatType(event.type) || !ServerSafety.isHypixel(connectedServer())) return;
        if (remoteChat(event.message, event.type)) return;
        if (armed
                && mc.thePlayer != null
                && mc.theWorld == sessionWorld
                && watchReady()
                && transitionWatch.hardSerial() == seenHard
                && transitionWatch.fatalSerial() == seenFatal
                && run.observingArena(mc.theWorld)
                && learning.recording().active()) {
            String line =
                    net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(
                            event.message.getUnformattedText());
            KillFeed.Outcome outcome = killFeed.accept(line, mc.thePlayer.getName(), tickNo);
            if (outcome != null)
                learning.recording().confirmedOutcome(tickNo, outcome.death, outcome.other);
        }
        if (!lobbySafety.pending()) return;
        String raw = event.message.getUnformattedText().trim();
        if (!raw.startsWith("{") || !raw.endsWith("}")) return;
        try {
            JsonElement parsed = new JsonParser().parse(raw);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("not an object");
            JsonObject json = parsed.getAsJsonObject();
            if (watchReady() && lobbySafety.awaitingReply(connectedServer(), mc.theWorld, now())) {
                if ("limbo".equals(stringField(json, "server")) && recovery.interrupt(now()))
                    stopInternal("Limbo observed; recovering lobby", true);
                if (recovery.recovering()) {
                    recovery.location(stringField(json, "server"), stringField(json, "gametype"),
                            stringField(json, "lobbyname"), json.has("mode") || json.has("map"), now());
                    lobbySafety.invalidate();
                    return;
                }
            }
            boolean accepted =
                    watchReady()
                            && lobbySafety.confirm(
                                    connectedServer(),
                                    mc.theWorld,
                                    now(),
                                    stringField(json, "gametype"),
                                    stringField(json, "lobbyname"),
                                    stringField(json, "server"),
                                    json.has("mode") || json.has("map"));
            if (accepted && locationSentAt > 0)
                locationRoundTrip = (int) Math.max(0, Math.min(6000, now() - locationSentAt));
            if (!accepted) {
                lobbySafety.invalidate();
                if (armed
                        && run.checking()
                        && stringField(json, "gametype") != null
                        && !stringField(json, "gametype").isEmpty()
                        && stringField(json, "server") != null
                        && !stringField(json, "server").isEmpty()) {
                    run.joinDuels();
                    sendLobbyCommand("/lobby duels");
                    return;
                }
                stop("Location denied: match, other game, changed instance or invalid response");
                return;
            }
            if (armed && run.phase() != RunSafety.Phase.JOIN_DUELS && !boundariesBound()) {
                stop("Duels lobby or pit calibration unavailable");
                return;
            }
            if (announceVerification)
                OptimusMod.notice(
                        "Verified " + lobbySafety.lobby() + " / " + lobbySafety.instance());
            announceVerification = false;
        } catch (RuntimeException error) {
            lobbySafety.invalidate();
            stop("Malformed location response");
        }
    }

    private void worldChanged() {
        lobbySafety.invalidate();
        releaseKeys();
        navigator.reset();
        settleUntil = now() + 1000;
        if (recovery.recovering()) {
            recovery.interrupt(now());
            return;
        }
        if (!run.joining() && recovery.interrupt(now())) {
            stopInternal("World changed; locating lobby", true);
            return;
        }
        run.worldTransfer();
        if (!run.running()) stop("Unexpected world transfer");
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world.isRemote) worldChanged();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote) worldChanged();
    }

    private void transitions() {
        if (!watchReady()) {
            if (recovery.interrupt(now())) stopInternal("Connection interrupted; recovering", true);
            else stop("Connection/observer unavailable");
            return;
        }
        long serial = transitionWatch.serial(),
                hard = transitionWatch.hardSerial(),
                fatal = transitionWatch.fatalSerial();
        if (fatal != seenFatal) {
            stop("Manual server command, disconnect or observer failure");
            return;
        }
        if (hard != seenHard && !run.joining()) {
            if (recovery.interrupt(now())) stopInternal("Server changed; locating lobby", true);
            else stop("Unexpected server join");
            return;
        }
        if (serial != seenSerial) {
            if (!run.joining()) telemetry.death(tickNo, "self_death_respawn");
            releaseKeys();
            navigator.reset();
            enteredPit = false;
            settleUntil = now() + 750;
            if (!run.joining()) {
                run.respawn(now());
                returningAfterRespawn = run.phase() == RunSafety.Phase.RECOVERING;
                traceEvent("respawn: waiting for same-lobby verification");
            }
        }
        seenSerial = serial;
        seenHard = hard;
        seenFatal = fatal;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        guard.publish();
        if (event.phase == TickEvent.Phase.START) {
            OptimusMod.runtime.tick(mc);
            ensureTransitionWatch();
            int stopCode = OptimusMod.stopKey.getKeyCode();
            boolean emergency =
                    stopCode > 0
                            && org.lwjgl.input.Keyboard.isCreated()
                            && org.lwjgl.input.Keyboard.isKeyDown(stopCode);
            if (emergency) stop("Emergency F9");
            while (OptimusMod.stopKey.isPressed()) {
                emergency = true;
                stop("Emergency F9");
            }
            if (OptimusMod.mapExports != null) {
                if (emergency) OptimusMod.mapExports.cancel();
                else OptimusMod.mapExports.tick();
            }
            if (OptimusMod.terminal != null) {
                if (emergency) OptimusMod.terminal.emergencyStop();
                final boolean emergencyHeld = emergency;
                OptimusMod.terminal.pump(args -> OptimusMod.terminalCommand(args, emergencyHeld));
            }
            if (emergency) {
                while (OptimusMod.toggleKey.isPressed()) {}
                return;
            }
            while (OptimusMod.toggleKey.isPressed()) {
                if (armed) stop("F8 toggle");
                else start(false);
            }
            processRequestedStart();
        } else {
            tickNo++;
            if (armed
                    && !OptimusMod.runtime.headless()
                    && OptimusMod.settings.debugVisuals
                    && mc.theWorld != null
                    && mc.thePlayer != null)
                navigationOverlay.observe(mc.theWorld, position(mc.thePlayer), tickNo);
        }
        if (recovery.recovering()) {
            releaseKeys();
            if (event.phase == TickEvent.Phase.START) recovery.tick(now(), recoveryHost);
            return;
        }
        if (event.phase == TickEvent.Phase.START && armed
                && (isVerifiedLobby() || run.phase() == RunSafety.Phase.RECOVERING)
                && !recovery.menus(now())) {
            stop("Unknown menu blocked headless navigation for 30 seconds");
            return;
        }
        if (!armed) {
            releaseKeys();
            if (lobbySafety.pending()
                    && !lobbySafety.awaitingReply(connectedServer(), mc.theWorld, now()))
                lobbySafety.invalidate();
            if (event.phase == TickEvent.Phase.START) refreshLocation();
            return;
        }
        // Vanilla processes held/queued mouse and keyboard clicks between START
        // and END. Bot attacks use the guarded direct path, never those queues.
        input.clearCombatInput();
        transitions();
        if (!armed) return;
        observePitReturn();
        if (run.expired(now())) {
            stop("Lobby join/recovery timed out");
            return;
        }
        closeRespawnMenu();
        if (!armed) return;
        parkLobbyHotbar();
        if (event.phase == TickEvent.Phase.START) {
            mc.gameSettings.pauseOnLostFocus =
                    remoteControl.keepActive(mc.gameSettings.pauseOnLostFocus);
            if (RemoteControl.closeWhileRunning(armed, mc.currentScreen)) mc.displayGuiScreen(null);
        }
        if (lobbySafety.pending()) {
            if (!lobbySafety.awaitingReply(connectedServer(), mc.theWorld, now())) {
                lobbySafety.invalidate();
                stop("Location response timed out");
                return;
            }
            if (lobbySafety.blocking()) {
                releaseKeys();
                return;
            }
        }
        if (run.checking() || run.joining()) {
            releaseKeys();
            if (mc.theWorld == null || mc.thePlayer == null || now() < settleUntil) return;
            if (!isVerifiedLobby()) {
                if (event.phase == TickEvent.Phase.START) sendLocationRequest();
                return;
            }
            if (run.phase() == RunSafety.Phase.JOIN_DUELS) {
                run.joiningOne();
                sendLobbyCommand("/swaplobby 1");
                return;
            }
            if (!isVerifiedLobby()
                    || !boundariesBound()
                    || !OptimusMod.settings.arenaConfigured()
                    || !run.activate(lobbySafety.lobby(), lobbySafety.instance(), mc.theWorld)) {
                stop("Verified Duels lobby or arena calibration unavailable");
                return;
            }
            sessionWorld = mc.theWorld;
            sessionPlayer = mc.thePlayer;
            enteredPit = false;
            entryGoal = null;
            nextEntryResolveAt = 0;
            combat.resetFocus();
            traceEvent(
                    "verified "
                            + lobbySafety.lobby()
                            + ": walking to pit from "
                            + point(position(mc.thePlayer)));
            if (!armed) return;
        }
        if (mc.theWorld != sessionWorld
                || mc.thePlayer == null
                || (mc.thePlayer != sessionPlayer && run.phase() != RunSafety.Phase.RECOVERING)) {
            stop("World/player changed");
            return;
        }
        if (!alive()) {
            telemetry.death(tickNo, "self_dead_observed");
            releaseKeys();
            navigator.reset();
            if (run.phase() == RunSafety.Phase.ACTIVE) {
                lobbySafety.invalidate();
                run.respawn(now());
                returningAfterRespawn = true;
                settleUntil = now() + 750;
            }
        }
        if (run.phase() == RunSafety.Phase.RECOVERING) {
            releaseKeys();
            if (now() < settleUntil) return;
            if (!isVerifiedLobby()) {
                if (event.phase == TickEvent.Phase.START) sendLocationRequest();
                return;
            }
            if (!alive()) return;
            if (!run.recover(lobbySafety.lobby(), lobbySafety.instance(), mc.theWorld)
                    || !boundariesBound()) {
                stop("Respawn did not return to the same verified Duels lobby/instance");
                return;
            }
            sessionPlayer =
                    mc.thePlayer; // replacement permitted only after same-world, exact-instance
            // verification
            navigator.reset();
            combat.resetFocus();
            roamGoal = null;
            nextRoamAt = 0;
            enteredPit = false;
            entryGoal = null;
            nextEntryResolveAt = 0;
            combat.deferClicks(tickNo);
            traceEvent(
                    "recovered same lobby: walking to pit from " + point(position(mc.thePlayer)));
            if (!armed) return;
        }
        if (!isVerifiedLobby() || !boundariesBound()) {
            stop("Lobby verification lost or expired");
            return;
        }
        if (event.phase == TickEvent.Phase.START) refreshLocation();
        if (mc.currentScreen != null) {
            if (guard.enabled()) guard.suspend();
            releaseKeys();
            if (!guiPaused) {
                guiPaused = true;
                navigator.reset();
                traceEvent("menu: paused, run intent retained");
            }
            return;
        }
        if (guiPaused) {
            guiPaused = false;
            navigator.reset();
            enteredPit = false;
            combat.deferClicks(tickNo);
            traceEvent("menu closed: resuming verified lobby run");
            if (!armed) return;
        }
        if (event.phase != TickEvent.Phase.END) {
            if (enteredPit && guard.enabled() && controlAllowed())
                guard.step(OptimusMod.settings.hypixelPit, tickNo, true);
            input.reassert();
            return;
        }
        if (mc.thePlayer.capabilities.isFlying) {
            landGrantedFlight();
            return;
        }
        if (!playerInPit()) enteredPit = false;
        if (!enteredPit
                && ControlPolicy.allowPitEntry(
                        playerInPit(),
                        navigator.onSafeGround(OptimusMod.settings.hypixelPit),
                        navigator.jumpInProgress())) {
            enteredPit = true;
            navigator.reset();
        }
        if (enteredPit) {
            if (controlAllowed()) {
                learning.recording().beginLife(tickNo);
                if (guard.enabled()) guard.step(OptimusMod.settings.hypixelPit, tickNo - 1);
                else combat.fight(OptimusMod.settings.hypixelPit, position(mc.thePlayer));
                if (!guard.enabled())
                    telemetry.end(
                            tickNo,
                            input.snapshot(),
                            armed && controlAllowed(),
                            navigator.jumpInProgress()
                                    ? "jump"
                                    : mc.thePlayer.onGround ? "ground" : "air");
            } else stop(navigationFailure(OptimusMod.settings.hypixelPit));
            return;
        }
        if (entryGoal == null) {
            releaseKeys();
            if (now() < nextEntryResolveAt) return;
            entryGoal = navigator.resolveEntry(OptimusMod.settings, lobbyTravelArea);
            if (entryGoal == null) {
                navigator.activity = "Waiting for loaded, clear pit entry";
                nextEntryResolveAt = now() + 1000;
                return;
            }
            traceEvent("validated entrance landing: " + entryGoal.serialize());
            if (!armed) return;
        }
        LobbyNavigator.Status status = navigate(entryGoal, lobbyTravelArea, false);
        if (status == LobbyNavigator.Status.ARRIVED && playerInPit()) enteredPit = true;
    }

    private void landGrantedFlight() {
        releaseKeys();
        navigator.reset();
        enteredPit = false;
        if (!navigationAllowed(lobbyTravelArea) || !mc.thePlayer.capabilities.allowFlying) {
            navigator.activity = "Waiting for server flight state";
            return;
        }
        Vec3Point floor = navigator.flightLanding(lobbyTravelArea);
        if (floor == null) {
            navigator.activity = "Waiting for safe floor below flight";
            return;
        }
        if (!navigationAllowed(lobbyTravelArea) || !mc.thePlayer.capabilities.allowFlying) return;
        if (mc.thePlayer.posY - floor.y <= .12) {
            // Only turn off flight already granted by the server, using vanilla abilities.
            mc.thePlayer.capabilities.isFlying = false;
            mc.thePlayer.sendPlayerAbilities();
            navigator.activity = "Landed; resuming ground route";
        } else {
            navigator.activity = "Landing from server-granted flight";
            input.move(false, false, false, false, false, true);
        }
    }

    String navigationFailure(PitRegion area) {
        String reason;
        if (!armed || !run.mayControl()) reason = "run is not active";
        else if (mc.theWorld != sessionWorld || mc.thePlayer != sessionPlayer)
            reason = "world/player changed";
        else if (!OptimusMod.settings.arenaConfigured())
            reason = "pit/entry calibration incomplete";
        else if (!isVerifiedLobby()) reason = "Duels lobby verification unavailable";
        else if (!boundariesBound()) reason = "arena calibration unavailable";
        else if (mc.thePlayer == null || !area.containsBody(position(mc.thePlayer), 0.3, 1.8))
            reason =
                    "outside "
                            + (area == lobbyTravelArea
                                    ? "world travel limits"
                                    : "pit pursuit bounds");
        else if (!alive()) reason = "player is not alive";
        else if (mc.currentScreen != null)
            reason = "screen " + mc.currentScreen.getClass().getSimpleName() + " is open";
        else reason = "safety changed during navigation";
        return "Navigation blocked: "
                + reason
                + "; position="
                + (mc.thePlayer == null ? "unset" : point(position(mc.thePlayer)));
    }

    void roamPit(PitRegion pit) {
        combat.roaming();
        if (roamGoal != null && tickNo >= roamUntil && mc.thePlayer.onGround) {
            roamGoal = null;
            navigator.reset();
        }
        if (roamGoal == null) {
            input.releaseMovement();
            if (tickNo < nextRoamAt) return;
            if (!navigator.onSafeGround(pit)) {
                navigator.activity = "Roaming pit; waiting for supported footing";
                return;
            }
            roamGoal = navigator.beginPatrol(pit, patrolRandom);
            if (roamGoal == null) {
                nextRoamAt = tickNo + 20;
                navigator.activity = "Waiting for a safe pit roaming route";
                return;
            }
            roamUntil = tickNo + 200;
        }
        LobbyNavigator.Status status = navigate(roamGoal, pit, false);
        if (status == LobbyNavigator.Status.ARRIVED || !navigator.failure.isEmpty()) {
            roamGoal = null;
            nextRoamAt = tickNo + 10;
            navigator.reset();
            input.releaseMovement();
            navigator.activity = "Roaming pit; choosing next destination";
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (OptimusMod.runtime.headless()
                || !armed
                || OptimusMod.settings == null
                || !OptimusMod.settings.debugVisuals
                || mc.theWorld == null
                || mc.thePlayer == null
                || mc.theWorld != sessionWorld) return;
        navigationOverlay.render(mc, navigator.debugView(), event.partialTicks);
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Text event) {
        if (OptimusMod.runtime.headless()) return;
        if (OptimusMod.mapExports != null && OptimusMod.mapExports.active())
            event.left.add("[O] " + OptimusMod.mapExports.status());
        if (mc.theWorld == null || mc.thePlayer == null) return;
        event.left.add(
                "[O] Duels lobby bot: "
                        + runLabel()
                        + " / "
                        + (isVerifiedLobby()
                                ? "VERIFIED " + lobbySafety.lobby()
                                : lobbySafety.blocking()
                                        ? "CHECKING / NO INPUT"
                                        : "UNVERIFIED / NO INPUT"));
        event.left.add(
                "[O] "
                        + OptimusMod.settings.combatProfile.id()
                        + " / "
                        + (playerInPit() ? "PIT" : "OUTSIDE PIT / NO ATTACKS")
                        + " / F9 stops");
        if (armed) {
            event.left.add("[O] " + activityLabel());
            if (OptimusMod.settings.debugVisuals)
                event.left.add(
                        "[O] Visuals: cyan route / green next / orange jump / pink goal / grey trail");
            if (!combat.detail().isEmpty() && !lobbySafety.blocking() && !guiPaused)
                event.left.add("[O] " + combat.detail());
        }
    }

    private String activityLabel() {
        if (lobbySafety.blocking()) return "Waiting for fresh lobby verification";
        if (guiPaused) return "Menu paused; resumes when closed";
        if (run.checking() || run.joining() || run.phase() == RunSafety.Phase.RECOVERING)
            return runLabel();
        return (roamGoal != null ? "Roaming pit: " : "") + navigator.activity;
    }

    private String runLabel() {
        if (recovery.recovering()) return "Recovering connection/lobby";
        switch (run.phase()) {
            case CHECK_LOBBY:
                return "Checking current lobby";
            case JOIN_DUELS:
                return "Joining Duels";
            case JOIN_ONE:
                return "Joining lobby 1";
            case RECOVERING:
                return "Recovering after respawn";
            case ACTIVE:
                return enteredPit && playerInPit() ? "Pit active" : "Walking to pit";
            default:
                return "Off";
        }
    }
}