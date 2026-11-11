package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.measuredPosition;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardInputPrediction;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;

/** Bound desktop input, double-tap sprint and delayed player prediction. */
final class GuardDesktopInput {
    private final GuardContext ctx;
    private final GuardCombat combat;
    private GuardWindowsKeys desktopKeys = new GuardWindowsKeys();
    private final GuardInputPrediction desktopPrediction = new GuardInputPrediction();
    private boolean desktopAttack, desktopForward, desktopSprint;
    private long desktopSwing, lastForwardPress = Long.MIN_VALUE, lastDesktopSwing = Long.MIN_VALUE;
    private int inputDelay;

    GuardDesktopInput(GuardContext ctx, GuardCombat combat) {
        this.ctx = ctx;
        this.combat = combat;
    }

    static final class Sample {
        final GuardWindowsKeys.Input keys;
        final Vec3Point pose;
        final boolean sprint, sneak;

        Sample(GuardWindowsKeys.Input keys, Vec3Point pose, boolean sprint, boolean sneak) {
            this.keys = keys;
            this.pose = pose;
            this.sprint = sprint;
            this.sneak = sneak;
        }
    }

    String bindWindow() {
        return desktopKeys.requestBinding(ctx.settings().guardUuid);
    }

    String status() {
        return desktopKeys.status();
    }

    boolean stalled() {
        return desktopPrediction.stalled();
    }

    void resetPrediction() {
        desktopPrediction.reset();
    }

    void configureKeys(String mode) {
        if (mode.equals("auto")) desktopKeys.automatic();
        resetPrediction();
    }

    void release() {
        desktopPrediction.reset();
        desktopAttack = desktopForward = desktopSprint = false;
        lastForwardPress = Long.MIN_VALUE;
    }

    void reset(boolean telemetry) {
        release();
        lastDesktopSwing = Long.MIN_VALUE;
        if (telemetry) desktopKeys.resetTelemetry();
    }

    boolean echo(long tick) {
        return desktopAttack
                || lastDesktopSwing != Long.MIN_VALUE && tick - lastDesktopSwing <= inputDelay + 3;
    }

    Vec3Point nextMotion(GuardWindowsKeys.Input keys, float yaw, boolean sprint, boolean sneak) {
        return desktopPrediction.nextMotion(keys.movement, yaw, sprint, sneak);
    }

    Sample sample(EntityPlayer player, PitRegion pit, long tick, boolean knocked) {
        Vec3Point pose = measuredPosition(player);
        float yaw = player.rotationYawHead;
        boolean sprint = player.isSprinting(), sneak = player.isSneaking();
        GuardWindowsKeys.Input desktop = null;
        inputDelay = 0;
        String keys = ctx.settings().guardKeys;
        boolean localPlayer = ctx.guardingController();
        if (localPlayer && !keys.equals("off"))
            desktop = desktopKeys.read(ctx.settings().guardUuid);
        if (desktop != null) {
            sneak = desktop.sneak;
            boolean forward = desktop.movement.forward > 0;
            if (forward && !desktopForward) {
                if (lastForwardPress != Long.MIN_VALUE && tick - lastForwardPress <= 7)
                    desktopSprint = true;
                lastForwardPress = tick;
            }
            if (!forward || sneak) desktopSprint = false;
            desktopForward = forward;
            sprint = desktop.sprint || desktopSprint || forward && !sneak && player.isSprinting();
            Vec3Point measured = measuredPosition(player);
            NetworkPlayerInfo other =
                    ctx.mc.getNetHandler() == null
                            ? null
                            : ctx.mc.getNetHandler().getPlayerInfo(player.getUniqueID());
            int ownPing = ctx.host.guardPingMillis(),
                    otherPing =
                            other == null || other.getResponseTime() <= 0
                                    ? ownPing
                                    : other.getResponseTime();
            inputDelay = Math.min(12, Math.max(0, (ownPing + otherPing + 50) / 100));
            pose =
                    knocked
                            ? measured
                            : desktopPrediction.update(
                                    measured,
                                    desktop.movement,
                                    yaw,
                                    sprint,
                                    sneak,
                                    tick,
                                    inputDelay);
            Vec3Point checked =
                    !knocked && player.onGround
                            ? ctx.navigator.guardPrediction(measured, pose, pit)
                            : ctx.navigator.guardPredictionClear(measured, pose, pit) ? pose : null;
            if (checked == null) {
                desktopPrediction.reset();
                pose = measured;
            } else pose = checked;
            if (desktop.attack && (!desktopAttack || tick - lastDesktopSwing >= 4)) {
                combat.observeSwing("windows:" + (++desktopSwing), "", pit, tick, false);
                lastDesktopSwing = tick;
            }
            desktopAttack = desktop.attack;
        } else {
            desktopPrediction.reset();
            desktopAttack = desktopForward = desktopSprint = false;
            lastForwardPress = Long.MIN_VALUE;
        }

        return desktop == null ? null : new Sample(desktop, pose, sprint, sneak);
    }
}