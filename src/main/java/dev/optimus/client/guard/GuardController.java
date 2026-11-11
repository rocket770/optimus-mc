package dev.optimus.client.guard;

import dev.optimus.client.ClientSettings;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.safety.ServerSafety;
import java.io.File;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.player.AttackEntityEvent;

/** Guard commands and lifecycle; input, follow movement and combat each have a separate owner. */
public final class GuardController {
    private final GuardContext ctx;
    private final GuardCombat combat;
    private final GuardInputTracker input;
    private final GuardInputPublisher publisher;
    private final GuardIdle idle;
    private final GuardMotion motion;
    private final GuardDecisionTiming timing = new GuardDecisionTiming();
    private EntityPlayer leader;
    private boolean paused = true, guardSword;
    private long controlTick;

    public GuardController(
            GuardHost host,
            Minecraft mc,
            GuardNavigation navigator,
            CombatClicks clicks,
            Supplier<ClientSettings> settings) {
        ctx = new GuardContext(mc, host, navigator, settings);
        combat = new GuardCombat(ctx, clicks);
        GuardInputRelay relay =
                new GuardInputRelay(
                        new File(System.getProperty("user.home"), ".optimus/guard-input"));
        input = new GuardInputTracker(ctx, relay, combat);
        publisher = new GuardInputPublisher(ctx, relay);
        idle = new GuardIdle(ctx);
        motion = new GuardMotion(ctx, combat, idle);
    }

    public boolean enabled() {
        return ctx.enabled();
    }

    public boolean sword() {
        return guardSword;
    }

    public long tick() {
        return controlTick;
    }

    public boolean protects(EntityPlayer player) {
        return ctx.protects(player);
    }

    public boolean permits(EntityPlayer player, long tick) {
        return combat.permits(player, tick);
    }

    public String status() {
        return "Guard: "
                + (enabled() ? ctx.settings().guardName : "off")
                + "; input: "
                + (paused ? "paused; last=" : "")
                + input.source()
                + "; Windows keys: "
                + ctx.settings().guardKeys
                + " ("
                + input.keyStatus()
                + "); navigation: "
                + (motion.routing() ? "route" : "close follow")
                + "; phase: "
                + (leader != null && combat.engaged(ctx.settings().hypixelPit, controlTick)
                        ? "fighting"
                        : "guarding")
                + "; direct movement ticks="
                + motion.directMoves()
                + "; "
                + timing.summary()
                + "; sharing: "
                + (publisher.sharing() ? "on" : "off");
    }

    public String summary() {
        return !enabled()
                ? "Guard off."
                : "Guard: "
                        + ctx.settings().guardName
                        + " | "
                        + (paused
                                ? "paused"
                                : leader != null
                                                && combat.engaged(
                                                        ctx.settings().hypixelPit, controlTick)
                                        ? "fighting"
                                        : "following");
    }

    public String keys(String value) {
        if (value.equals("bind")) {
            ClientSettings settings = ctx.settings();
            if (!enabled() || !ctx.guardingController())
                return "Select the configured controller with /o guard first.";
            if (settings.guardKeys.equals("off"))
                return "Use /o guard keys auto before binding a window.";
            return input.bindWindow();
        }
        if (!value.equals("auto") && !value.equals("on") && !value.equals("off"))
            return "Usage: /o guard keys auto|on|off|bind";
        ctx.settings().guardKeys = value;
        ctx.settings().save();
        input.resetPrediction();
        return "Guard keys: " + value + ".";
    }

    public String configure(String name) {
        if (name.equalsIgnoreCase("off")) {
            ctx.host.controlOptionsChanged();
            ctx.settings().guardName = ctx.settings().guardUuid = "";
            ctx.settings().save();
            return "Guard off.";
        }
        if (!name.matches("[A-Za-z0-9_]{3,16}")
                || !ServerSafety.isHypixel(ctx.host.connectedServer())
                || ctx.mc.getNetHandler() == null
                || ctx.mc.thePlayer == null) return "Join Hypixel with that player first.";
        NetworkPlayerInfo info = ctx.mc.getNetHandler().getPlayerInfo(name);
        if (info == null || info.getGameProfile().getId() == null)
            return "Player not in this lobby.";
        if (info.getGameProfile().getId().equals(ctx.mc.thePlayer.getUniqueID()))
            return "Can't guard this bot.";
        ctx.host.controlOptionsChanged();
        ctx.settings().guardName = info.getGameProfile().getName();
        ctx.settings().guardUuid = info.getGameProfile().getId().toString();
        ctx.settings().save();
        ctx.host.startGuardIfNeeded();
        return "Guarding " + ctx.settings().guardName + ".";
    }

    public String share(boolean enabled) {
        return publisher.share(enabled);
    }

    public void attack(AttackEntityEvent event) {
        publisher.attack(event);
    }

    public void publish() {
        publisher.publish();
    }

    public void reset() {
        idle.reset();
        motion.reset();
        motion.resetTelemetry();
        input.reset(true);
        input.close();
        publisher.reset();
        combat.clear(true);
        timing.reset();
        paused = true;
        leader = null;
    }

    public void suspend() {
        idle.reset();
        motion.reset();
        input.reset(false);
        combat.clear(false);
        paused = true;
        leader = null;
        ctx.host.guardHold();
    }

    public void step(PitRegion pit, long tick) {
        step(pit, tick, false);
    }

    public void step(PitRegion pit, long tick, boolean beforeMovement) {
        long started = System.nanoTime();
        ctx.navigator.beginGuardQueries();
        try {
            control(pit, tick, beforeMovement);
        } finally {
            ctx.navigator.endGuardQueries();
            timing.record(started, tick, input.source(), motion.routing());
        }
    }

    private EntityPlayer guarded(PitRegion pit) {
        if (ctx.mc.theWorld != null)
            for (EntityPlayer player : ctx.mc.theWorld.playerEntities)
                if (ctx.present(player, pit)
                        && ctx.settings().guardUuid.equals(player.getUniqueID().toString())
                        && ctx.settings().guardName.equalsIgnoreCase(player.getName()))
                    return player;
        return null;
    }

    private void control(PitRegion pit, long tick, boolean beforeMovement) {
        controlTick = tick;
        combat.tick(tick);
        if (!enabled() || !ctx.host.guardAllowed()) {
            suspend();
            return;
        }
        EntityPlayer player = guarded(pit);
        if (player == null) {
            if (!idle.waiting()) suspend();
            idle.waitForLeader(
                    pit, tick, candidate -> motion.safeInput(candidate, false, false, pit));
            return;
        }
        idle.returned();
        paused = false;
        if (player != leader) {
            boolean replacing = leader != null;
            motion.reset();
            idle.reset();
            input.reset(replacing);
            if (replacing) {
                input.close();
                motion.resetTelemetry();
                timing.reset();
            }
            leader = player;
            combat.leader(player);
            ctx.navigator.reset();
        }
        boolean damaged = combat.damaged();
        if (damaged) input.resetPrediction();
        boolean knocked = motion.knocked(player, tick, damaged);
        GuardObservation observation = input.sample(player, knocked, pit, tick);
        guardSword = observation.sword;
        if (ctx.host.guardSelectWeapon(guardSword)) ctx.mc.playerController.updateController();
        boolean equipmentReady = ctx.host.guardEquipment(guardSword, tick);
        combat.observeThreats(pit, tick);
        combat.repairSelection(pit, tick);
        motion.follow(observation, pit, tick, beforeMovement, equipmentReady);
    }
}