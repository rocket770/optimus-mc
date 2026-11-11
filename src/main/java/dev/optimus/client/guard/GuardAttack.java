package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.box;
import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardAssist;
import dev.optimus.core.guard.GuardCombatWindow;
import dev.optimus.core.guard.GuardFollow;
import net.minecraft.entity.player.EntityPlayer;

/** Owns guard aim and assist pacing; native attacks still use the shared combat adapter. */
final class GuardAttack {
    private final GuardContext ctx;
    private final CombatClicks clicks;
    private final GuardCombatWindow combatWindow = new GuardCombatWindow();
    private long aimedAt = Long.MIN_VALUE;
    private boolean airMirrored;

    GuardAttack(GuardContext ctx, CombatClicks clicks) {
        this.ctx = ctx;
        this.clicks = clicks;
    }

    void resetAim() {
        aimedAt = Long.MIN_VALUE;
    }

    void resetWindow() {
        combatWindow.reset();
    }

    void newSwing() {
        airMirrored = false;
    }

    void reset() {
        resetAim();
        resetWindow();
        newSwing();
    }

    void turnToward(float yaw, float pitch, long tick) {
        turnToward(yaw, pitch, tick, false);
    }

    void turnToward(float yaw, float pitch, long tick, boolean melee) {
        if (aimedAt == tick) return;
        aimedAt = tick;
        CombatProfile profile = ctx.settings().combatProfile;
        ctx.mc.thePlayer.rotationYaw +=
                GuardFollow.clamp(
                        CombatTurn.wrap(yaw - ctx.mc.thePlayer.rotationYaw),
                        melee ? Math.max(60, profile.yawLimit) : profile.yawLimit);
        ctx.mc.thePlayer.rotationPitch +=
                GuardFollow.clamp(
                        pitch - ctx.mc.thePlayer.rotationPitch,
                        melee ? Math.max(30, profile.pitchLimit) : profile.pitchLimit);
    }

    void perform(
            EntityPlayer target,
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
            Vec3Point eye =
                    new Vec3Point(
                            ctx.mc.thePlayer.posX,
                            ctx.mc.thePlayer.posY + ctx.mc.thePlayer.getEyeHeight(),
                            ctx.mc.thePlayer.posZ);
            Vec3Point aim =
                    CombatAim.clearPoint(
                            eye,
                            box(target),
                            CombatClicks.ATTACK_REACH,
                            hit -> ctx.host.guardRay(eye, hit));
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
            if (mayTurn && !ctx.navigator.jumpInProgress() && aimedAt != tick)
                turnToward(
                        desired,
                        pitch,
                        tick,
                        assist.pending(tick) && ctx.reachable(position(ctx.mc.thePlayer), target));
            Vec3Point hit =
                    CombatAim.hit(
                            eye,
                            box(target),
                            ctx.mc.thePlayer.rotationYaw,
                            ctx.mc.thePlayer.rotationPitch,
                            CombatClicks.ATTACK_REACH);
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
                performed
                        ? "none"
                        : !pending
                                ? "no_pending_swing"
                                : !equipmentReady
                                        ? "equipment"
                                        : !rayReady
                                                ? "ray"
                                                : !windowReady
                                                        ? "hit_window"
                                                        : cooldown > 0
                                                                ? "pacing"
                                                                : "duplicate_tick");
    }
}