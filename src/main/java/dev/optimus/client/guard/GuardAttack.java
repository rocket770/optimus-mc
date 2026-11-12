package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.box;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardAssist;
import dev.optimus.core.guard.GuardCombatWindow;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.guard.GuardSwingSource;
import net.minecraft.entity.player.EntityPlayer;

/** Owns guard aim and assist pacing; native attacks still use the shared combat adapter. */
final class GuardAttack {
    private final GuardContext ctx;
    private final CombatClicks clicks;
    private final GuardCombatWindow combatWindow = new GuardCombatWindow();
    private long aimedAt = Long.MIN_VALUE;
    private boolean airMirrored, combatAimed;
    private float aimBaseYaw, aimBasePitch, combatYaw, combatPitch;

    GuardAttack(GuardContext ctx, CombatClicks clicks) {
        this.ctx = ctx;
        this.clicks = clicks;
    }

    void resetAim() {
        aimedAt = Long.MIN_VALUE;
        combatAimed = false;
    }

    void resetWindow() {
        combatWindow.reset();
    }

    void newSwing(EntityPlayer leader, EntityPlayer target, long tick, GuardSwingSource source) {
        airMirrored = false;
        // Give the guarded player's hit time to arrive instead of requesting
        // an identical first hit. Repeated swings cannot extend this wait.
        if (target != null) {
            Vec3Point eye =
                    new Vec3Point(leader.posX, leader.posY + leader.getEyeHeight(), leader.posZ);
            if (CombatAim.clearPoint(
                            eye,
                            box(target),
                            CombatClicks.ATTACK_REACH,
                            hit -> ctx.host.guardRay(eye, hit))
                    != null)
                combatWindow.awaitDamage(
                        target.getUniqueID().toString(), tick, ctx.host.guardPingMillis(), source);
        }
    }

    void reset() {
        resetAim();
        resetWindow();
        airMirrored = false;
    }

    void turnToward(float yaw, float pitch, long tick, boolean combat) {
        if (aimedAt != tick) {
            aimedAt = tick;
            aimBaseYaw = ctx.mc.thePlayer.rotationYaw;
            aimBasePitch = ctx.mc.thePlayer.rotationPitch;
            combatAimed = false;
        }
        if (combatAimed && !combat) {
            ctx.mc.thePlayer.rotationYaw = combatYaw;
            ctx.mc.thePlayer.rotationPitch = combatPitch;
            return;
        }
        CombatProfile profile = ctx.settings().combatProfile;
        // Reassert against navigation within a tick without accumulating extra turn speed.
        ctx.mc.thePlayer.rotationYaw =
                aimBaseYaw
                        + (float)
                                GuardFollow.clamp(
                                        CombatTurn.wrap(yaw - aimBaseYaw),
                                        combat ? Math.max(90, profile.yawLimit) : profile.yawLimit);
        ctx.mc.thePlayer.rotationPitch =
                aimBasePitch
                        + (float)
                                GuardFollow.clamp(
                                        pitch - aimBasePitch,
                                        combat
                                                ? Math.max(45, profile.pitchLimit)
                                                : profile.pitchLimit);
        if (combat) {
            combatAimed = true;
            combatYaw = ctx.mc.thePlayer.rotationYaw;
            combatPitch = ctx.mc.thePlayer.rotationPitch;
        }
    }

    void perform(
            EntityPlayer target,
            boolean retreating,
            GuardAssist assist,
            boolean engaged,
            PitRegion pit,
            long tick,
            boolean mayTurn,
            boolean equipmentReady,
            boolean sword) {
        if (!assist.live(tick) && !engaged) return;
        if (target != null && (!ctx.present(target, pit) || ctx.protects(target))) {
            assist.cancel();
            combatWindow.reset();
            return;
        }
        if (target != null
                && combatWindow.observe(
                        target.getUniqueID().toString(), target.hurtTime, target.getHealth(), tick))
            assist.damageObserved(tick);
        float beforeYaw = ctx.mc.thePlayer.rotationYaw,
                beforePitch = ctx.mc.thePlayer.rotationPitch;
        float desired = beforeYaw, pitch = beforePitch;
        boolean rayReady = true, windowReady = true;
        if (target != null) {
            double reach = CombatClicks.ATTACK_REACH - (retreating ? .12 : 0);
            Vec3Point eye =
                    new Vec3Point(
                            ctx.mc.thePlayer.posX,
                            ctx.mc.thePlayer.posY + ctx.mc.thePlayer.getEyeHeight(),
                            ctx.mc.thePlayer.posZ);
            Vec3Point aim =
                    CombatAim.clearPoint(
                            eye, box(target), reach, hit -> ctx.host.guardRay(eye, hit));
            if (aim == null)
                aim =
                        CombatAim.clearPoint(
                                eye, box(target), 6.5, hit -> ctx.host.guardRay(eye, hit));
            if (aim == null) aim = CombatAim.point(eye, box(target));
            desired = (float) Math.toDegrees(Math.atan2(eye.x - aim.x, aim.z - eye.z));
            pitch =
                    (float)
                            -Math.toDegrees(
                                    Math.atan2(
                                            aim.y - eye.y,
                                            Math.sqrt(eye.horizontalDistanceSq(aim))));
            if (mayTurn && !ctx.navigator.jumpInProgress()) turnToward(desired, pitch, tick, true);
            Vec3Point hit =
                    CombatAim.hit(
                            eye,
                            box(target),
                            ctx.mc.thePlayer.rotationYaw,
                            ctx.mc.thePlayer.rotationPitch,
                            reach);
            rayReady = hit != null && ctx.host.guardRay(eye, hit);
            windowReady = combatWindow.ready(tick, target.hurtTime, ctx.host.guardPingMillis());
        }
        boolean pending = assist.pending(tick);
        long cooldown = clicks.remaining(tick);
        CombatClicks.Action action =
                equipmentReady
                                && pending
                                && rayReady
                                && windowReady
                                && !(target == null && airMirrored)
                        ? clicks.choose(
                                tick, ctx.host.guardAllowed(), target != null, target == null)
                        : CombatClicks.Action.NONE;
        boolean performed =
                action != CombatClicks.Action.NONE && ctx.host.guardStrike(target, sword);
        if (performed) {
            clicks.performed(action, tick, ctx.host.guardAttackDelay());
            if (action == CombatClicks.Action.ATTACK) {
                combatWindow.attackSent(tick, ctx.host.guardPingMillis());
                assist.attackSent(tick, ctx.host.guardPingMillis());
            } else airMirrored = true;
        }
        String gate;
        if (performed) gate = "none";
        else if (!pending) gate = "no_pending_swing";
        else if (!equipmentReady) gate = "equipment";
        else if (!rayReady) gate = "ray";
        else if (!windowReady) gate = "hit_window";
        else if (cooldown > 0) gate = "pacing";
        else gate = "duplicate_tick";
        ctx.host.recordCombatDecision(
                target,
                "guard",
                tick,
                beforeYaw,
                beforePitch,
                desired,
                pitch,
                equipmentReady,
                windowReady,
                pending,
                cooldown,
                action == CombatClicks.Action.AIR_SWING && !performed
                        ? CombatClicks.Action.NONE
                        : action,
                performed && action == CombatClicks.Action.ATTACK,
                gate);
    }
}