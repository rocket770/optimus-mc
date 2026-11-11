package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.measuredPosition;
import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.navigation.GroundMotion;
import java.io.IOException;
import net.minecraft.entity.player.EntityPlayer;

/** Selects relay, bound desktop or observed inputs and predicts the guarded player's motion. */
final class GuardInputTracker {
    private final GuardContext ctx;
    private final GuardInputRelay relay;
    private final GuardCombat combat;
    private final GuardDesktopInput desktopInput;
    private boolean listening, observedSwing;
    private int observedProgress;
    private long remoteSwing, observedTick = Long.MIN_VALUE, lastRelayTick = Long.MIN_VALUE;
    private Vec3Point previousLeader, velocity = new Vec3Point(0, 0, 0);
    private String relayEvent = "", inputSource = "observed movement";

    GuardInputTracker(GuardContext ctx, GuardInputRelay relay, GuardCombat combat) {
        this.ctx = ctx;
        this.relay = relay;
        this.combat = combat;
        desktopInput = new GuardDesktopInput(ctx, combat);
    }

    String bindWindow() {
        return desktopInput.bindWindow();
    }

    String source() {
        return inputSource;
    }

    String keyStatus() {
        return desktopInput.status();
    }

    void resetPrediction() {
        desktopInput.resetPrediction();
    }

    void reset(boolean telemetry) {
        desktopInput.reset(telemetry);
        observedSwing = false;
        lastRelayTick = observedTick = Long.MIN_VALUE;
        previousLeader = null;
        velocity = new Vec3Point(0, 0, 0);
        relayEvent = "";
        observedProgress = 0;
        if (telemetry) {
            inputSource = "observed movement";
        }
    }

    void close() {
        relay.close();
        listening = false;
    }

    GuardObservation sample(EntityPlayer player, boolean knocked, PitRegion pit, long tick) {
        if (!listening) {
            try {
                relay.follow(ctx.settings().guardUuid);
                listening = true;
            } catch (IOException error) {
                ctx.host.guardActivity("Guard input feed unavailable; observing player");
            }
        }
        GuardInputRelay.Frame frame =
                relay.latest(
                        ctx.settings().guardUuid,
                        ctx.host.connectedServer(),
                        ctx.host.guardLobby(),
                        ctx.host.guardInstance());
        if (frame != null
                && (position(player).horizontalDistanceSq(new Vec3Point(frame.x, frame.y, frame.z))
                                > 9
                        || Math.abs(player.posY - frame.y) > 3)) frame = null;
        Vec3Point pose =
                frame == null ? measuredPosition(player) : new Vec3Point(frame.x, frame.y, frame.z);
        float yaw = frame == null ? player.rotationYawHead : frame.yaw,
                pitch = frame == null ? player.rotationPitch : frame.pitch;
        boolean sneak = frame == null ? player.isSneaking() : frame.sneak;
        boolean sprint = frame == null ? player.isSprinting() : frame.sprint;
        GuardDesktopInput.Sample keys =
                frame == null ? desktopInput.sample(player, pit, tick, knocked) : null;
        if (frame != null) desktopInput.release();
        GuardWindowsKeys.Input desktop = keys == null ? null : keys.keys;
        if (keys != null) {
            pose = keys.pose;
            sneak = keys.sneak;
            sprint = keys.sprint;
        }
        inputSource =
                frame != null
                        ? "local client keys"
                        : desktop != null ? "Windows keys" : "observed movement";
        boolean guardSword = frame == null ? ctx.host.guardSword(player) : frame.sword;
        if (tick != observedTick) {
            Vec3Point measuredVelocity =
                    previousLeader == null
                            ? new Vec3Point(0, 0, 0)
                            : new Vec3Point(
                                    GuardFollow.clamp(
                                            (pose.x - previousLeader.x)
                                                    / Math.max(1, tick - observedTick),
                                            .36),
                                    pose.y - previousLeader.y,
                                    GuardFollow.clamp(
                                            (pose.z - previousLeader.z)
                                                    / Math.max(1, tick - observedTick),
                                            .36));
            velocity =
                    desktop == null && frame == null
                            ? new Vec3Point(
                                    .6 * measuredVelocity.x + .4 * velocity.x,
                                    measuredVelocity.y,
                                    .6 * measuredVelocity.z + .4 * velocity.z)
                            : measuredVelocity;
            previousLeader = pose;
            observedTick = tick;
        }
        if (frame != null) {
            lastRelayTick = tick;
            Vec3Point delta =
                    GroundMotion.delta(
                            frame.mx,
                            frame.mz,
                            yaw,
                            frame.input.forward,
                            frame.input.side,
                            sprint ? .13 : .1,
                            sneak ? .3 : 1);
            velocity = new Vec3Point(delta.x, velocity.y, delta.z);
            String event = frame.session + ":" + frame.swing;
            if (!relayEvent.isEmpty() && !relayEvent.equals(event))
                combat.observeSwing(event, frame.victim, pit, tick);
            relayEvent = event;
        } else {
            boolean newSwing =
                    player.isSwingInProgress
                            && (!observedSwing || player.swingProgressInt < observedProgress);
            boolean desktopEcho = desktop != null && desktopInput.echo(tick);
            if (newSwing && (lastRelayTick == Long.MIN_VALUE || tick - lastRelayTick > 6)) {
                if (desktopEcho && combat.live(tick)) combat.reconcileSwing(pit);
                else combat.observeSwing("remote:" + (++remoteSwing), "", pit, tick);
            }
        }
        if (desktop != null && !knocked) {
            Vec3Point delta = desktopInput.nextMotion(desktop, yaw, sprint, sneak);
            velocity = new Vec3Point(delta.x, velocity.y, delta.z);
        }
        observedSwing = player.isSwingInProgress;
        observedProgress = player.swingProgressInt;
        if (desktop == null
                && frame == null
                && !knocked
                && player.onGround
                && velocity.x * velocity.x + velocity.z * velocity.z > .06 * .06) {
            Vec3Point ahead =
                    new Vec3Point(
                            pose.x + GuardFollow.clamp(velocity.x * 1.5, .3),
                            pose.y,
                            pose.z + GuardFollow.clamp(velocity.z * 1.5, .3));
            if (ctx.navigator.guardPredictionClear(pose, ahead, pit)
                    && ctx.navigator.autoStep(pose, ahead, pit)) pose = ahead;
        }

        return new GuardObservation(
                player,
                pose,
                velocity,
                yaw,
                pitch,
                sneak,
                sprint,
                guardSword,
                knocked,
                desktopInput.stalled(),
                frame,
                desktop);
    }
}