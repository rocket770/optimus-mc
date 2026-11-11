package dev.optimus.client.guard;

import com.google.gson.JsonObject;
import dev.optimus.core.navigation.NearbySteering;
import java.io.IOException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.player.AttackEntityEvent;

/** Publishes opt-in local controls without owning guard following or target selection. */
final class GuardInputPublisher {
    private final GuardContext ctx;
    private final GuardInputRelay relay;
    private boolean sharing, localSwing;
    private int localProgress;
    private long localSwingId, localVictimAt;
    private String localVictim = "";

    GuardInputPublisher(GuardContext ctx, GuardInputRelay relay) {
        this.ctx = ctx;
        this.relay = relay;
    }

    boolean sharing() {
        return sharing;
    }

    void reset() {
        sharing = localSwing = false;
        localSwingId = 0;
        localVictim = "";
        localVictimAt = 0;
        relay.stopPublishing();
    }

    public String share(boolean enabled) {
        if (enabled && (!ctx.host.isVerifiedLobby() || ctx.host.guardArmed()))
            return "Check the lobby with /o locraw first. Input sharing runs on the main, stopped client.";
        sharing = enabled;
        if (!enabled) relay.stopPublishing();
        return "Keyboard sharing " + (enabled ? "on" : "off") + ".";
    }

    public void attack(AttackEntityEvent event) {
        if (!sharing || event.entityPlayer != ctx.mc.thePlayer) return;
        localSwingId++;
        localSwing = ctx.mc.thePlayer.isSwingInProgress;
        localProgress = ctx.mc.thePlayer.swingProgressInt;
        localVictim =
                event.target instanceof EntityPlayer ? event.target.getUniqueID().toString() : "";
        localVictimAt = System.currentTimeMillis();
        publish();
    }

    public void publish() {
        if (!sharing || ctx.mc.thePlayer == null || ctx.mc.theWorld == null) return;
        boolean active =
                !ctx.host.guardArmed()
                        && ctx.host.isVerifiedLobby()
                        && ctx.mc.currentScreen == null
                        && ctx.mc.inGameHasFocus
                        && ctx.mc.thePlayer.isEntityAlive();
        if (ctx.mc.thePlayer.isSwingInProgress
                && (!localSwing || ctx.mc.thePlayer.swingProgressInt < localProgress)) {
            localSwingId++;
            localVictim = "";
        }
        localSwing = ctx.mc.thePlayer.isSwingInProgress;
        localProgress = ctx.mc.thePlayer.swingProgressInt;
        JsonObject data = new JsonObject();
        data.addProperty("server", ctx.host.connectedServer());
        data.addProperty("lobby", ctx.host.guardLobby());
        data.addProperty("instance", ctx.host.guardInstance());
        data.addProperty("active", active);
        data.addProperty("x", ctx.mc.thePlayer.posX);
        data.addProperty("y", ctx.mc.thePlayer.posY);
        data.addProperty("z", ctx.mc.thePlayer.posZ);
        data.addProperty("mx", ctx.mc.thePlayer.motionX);
        data.addProperty("mz", ctx.mc.thePlayer.motionZ);
        data.addProperty("yaw", ctx.mc.thePlayer.rotationYaw);
        data.addProperty("pitch", ctx.mc.thePlayer.rotationPitch);
        int forward =
                (active && ctx.mc.gameSettings.keyBindForward.isKeyDown() ? 1 : 0)
                        - (active && ctx.mc.gameSettings.keyBindBack.isKeyDown() ? 1 : 0);
        int side =
                (active && ctx.mc.gameSettings.keyBindLeft.isKeyDown() ? 1 : 0)
                        - (active && ctx.mc.gameSettings.keyBindRight.isKeyDown() ? 1 : 0);
        NearbySteering.Input input = NearbySteering.Input.NONE;
        for (NearbySteering.Input candidate : NearbySteering.Input.values())
            if (candidate.forward == forward && candidate.side == side) input = candidate;
        data.addProperty("input", input.name());
        data.addProperty("jump", active && ctx.mc.gameSettings.keyBindJump.isKeyDown());
        data.addProperty("sneak", active && ctx.mc.gameSettings.keyBindSneak.isKeyDown());
        data.addProperty(
                "sprint",
                active
                        && (ctx.mc.thePlayer.isSprinting()
                                || ctx.mc.gameSettings.keyBindSprint.isKeyDown()));
        data.addProperty("sword", ctx.host.guardSword(ctx.mc.thePlayer));
        data.addProperty("swing", localSwingId);
        data.addProperty(
                "victim", System.currentTimeMillis() - localVictimAt <= 150 ? localVictim : "");
        try {
            relay.publish(data, ctx.mc.thePlayer.getUniqueID().toString());
        } catch (IOException error) {
            sharing = false;
            relay.close();
            ctx.host.notice("Keyboard sharing stopped: " + error.getMessage());
        }
    }
}