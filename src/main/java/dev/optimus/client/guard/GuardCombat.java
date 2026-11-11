package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.box;
import static dev.optimus.client.guard.GuardContext.distance;
import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardAssist;
import net.minecraft.entity.player.EntityPlayer;

/** Guard target acquisition and engagement lifecycle, separate from input and movement. */
final class GuardCombat {
    private final GuardContext ctx;
    private final GuardThreatTracker threats;
    private final GuardCombatPosition positioning;
    private final GuardAttack strikes;
    private final GuardAssist assist = new GuardAssist();
    private EntityPlayer leader, assistTarget, selectionCandidate;
    private long controlTick,
            engagementUntil = Long.MIN_VALUE,
            acquireUntil = Long.MIN_VALUE,
            selectionSince = Long.MIN_VALUE,
            defenseSerial;
    private boolean defending;

    GuardCombat(GuardContext ctx, CombatClicks clicks) {
        this.ctx = ctx;
        threats = new GuardThreatTracker(ctx);
        positioning = new GuardCombatPosition(ctx, threats);
        strikes = new GuardAttack(ctx, clicks);
    }

    EntityPlayer target() {
        return assistTarget;
    }

    int side() {
        return positioning.side();
    }

    boolean pending(long tick) {
        return assist.pending(tick);
    }

    boolean live(long tick) {
        return assist.live(tick);
    }

    boolean damaged() {
        return leader != null && threats.damaged(leader);
    }

    void tick(long tick) {
        controlTick = tick;
    }

    boolean permits(EntityPlayer player, long tick) {
        return assist.pending(tick) && player == assistTarget && !ctx.protects(player);
    }

    void leader(EntityPlayer player) {
        clear(false);
        leader = player;
    }

    void clear(boolean reset) {
        if (reset) assist.reset();
        else assist.cancel();
        strikes.reset();
        positioning.reset();
        threats.reset();
        defending = false;
        assistTarget = leader = selectionCandidate = null;
        engagementUntil = acquireUntil = selectionSince = Long.MIN_VALUE;
    }

    void observeThreats(PitRegion pit, long tick) {
        threats.observe(leader, pit, tick);
        if (assist.live(tick) && !defending && assistTarget == null) {
            EntityPlayer candidate = threats.damagedTarget(tick);
            if (candidate != null) bindVictim(candidate);
        }
        EntityPlayer attacker = threats.attacker(tick);
        if (attacker == null) return;
        if (assistTarget != attacker) {
            resetTarget();
        }
        if (assistTarget != attacker || tick > engagementUntil) positioning.rememberView(leader);
        assistTarget = attacker;
        assist.observe("defend:" + (++defenseSerial), attacker.getUniqueID().toString(), tick);
        defending = true;
        engagementUntil = tick + 20;
    }

    void observeSwing(String event, String victim, PitRegion pit, long tick) {
        observeSwing(event, victim, pit, tick, true);
    }

    void observeSwing(
            String event, String victim, PitRegion pit, long tick, boolean observedDirection) {
        EntityPlayer previous = assistTarget;
        EntityPlayer selected = findVictim(victim, pit);
        assistTarget =
                !observedDirection && victim.isEmpty() && engaged(pit, tick) ? previous : selected;
        if (assistTarget == null
                && victim.isEmpty()
                && assist.live(tick)
                && previous != null
                && ctx.present(previous, pit)
                && !ctx.protects(previous)) {
            Vec3Point eye =
                    new Vec3Point(leader.posX, leader.posY + leader.getEyeHeight(), leader.posZ);
            Vec3Point hit =
                    CombatAim.hit(
                            eye,
                            box(previous),
                            leader.rotationYawHead,
                            leader.rotationPitch,
                            CombatClicks.SWING_REACH);
            if (hit != null && ctx.host.guardRay(eye, hit)) assistTarget = previous;
        }
        if (assistTarget != previous) {
            resetTarget();
        }
        strikes.newSwing();
        defending = false;
        assist.observe(
                event, assistTarget == null ? "" : assistTarget.getUniqueID().toString(), tick);
        acquireUntil = tick + 6;
        selectionCandidate = null;
        selectionSince = Long.MIN_VALUE;
        if (assistTarget != null && selected != null) {
            if (assistTarget != previous || tick > engagementUntil)
                positioning.rememberView(leader);
            engagementUntil = tick + 20;
        }
    }

    EntityPlayer findVictim(String victim, PitRegion pit) {
        EntityPlayer chosen = null;
        Vec3Point eye =
                new Vec3Point(leader.posX, leader.posY + leader.getEyeHeight(), leader.posZ);
        double closest = Double.POSITIVE_INFINITY;
        for (int pass = 0; pass < 2 && chosen == null; pass++)
            for (EntityPlayer candidate : ctx.mc.theWorld.playerEntities) {
                if (!ctx.present(candidate, pit) || ctx.protects(candidate)) continue;
                if (!victim.isEmpty() && !candidate.getUniqueID().toString().equals(victim))
                    continue;
                double selectionReach = CombatClicks.SWING_REACH;
                if (ctx.reachable(position(ctx.mc.thePlayer), candidate)) {
                    Vec3Point center =
                            new Vec3Point(
                                    candidate.posX,
                                    candidate.posY + candidate.getEyeHeight(),
                                    candidate.posZ);
                    selectionReach =
                            Math.max(selectionReach, Math.sqrt(distance(eye, center)) + .6);
                }
                CollisionGeometry.Box body = box(candidate);
                // Remote head/position packets are quantized and need not arrive
                // together. Tolerance nominates intent only; attacks still require
                // the real unexpanded body, reach and block ray below.
                if (pass == 1)
                    body =
                            new CollisionGeometry.Box(
                                    body.minX - .12,
                                    body.minY,
                                    body.minZ - .12,
                                    body.maxX + .12,
                                    body.maxY,
                                    body.maxZ + .12);
                Vec3Point hit =
                        CombatAim.hit(
                                eye,
                                body,
                                leader.rotationYawHead,
                                leader.rotationPitch,
                                selectionReach);
                if (victim.isEmpty() && (hit == null || !ctx.host.guardRay(eye, hit))) continue;
                double distance =
                        hit == null
                                ? eye.horizontalDistanceSq(position(candidate))
                                : distance(eye, hit);
                if (distance < closest) {
                    closest = distance;
                    chosen = candidate;
                }
            }
        return chosen;
    }

    private void resetTarget() {
        strikes.resetWindow();
        positioning.retarget();
        strikes.resetAim();
    }

    void bindVictim(EntityPlayer target) {
        if (target != assistTarget || controlTick > engagementUntil)
            positioning.rememberView(leader);
        if (target != assistTarget) {
            resetTarget();
        }
        assistTarget = target;
        assist.retarget(target.getUniqueID().toString());
        engagementUntil = controlTick + 20;
    }

    void reconcileSwing(PitRegion pit) {
        EntityPlayer actual = findVictim("", pit);
        if (actual != null) bindVictim(actual);
    }

    void repairSelection(PitRegion pit, long tick) {
        if (!assist.live(tick) || defending || tick > acquireUntil) return;
        EntityPlayer actual = findVictim("", pit);
        if (actual == null || actual == assistTarget) {
            selectionCandidate = null;
            return;
        }
        if (actual != selectionCandidate) {
            selectionCandidate = actual;
            selectionSince = tick;
        }
        // A delayed head update may follow the swing. Unknown victims resolve
        // immediately; replacing a lock needs two consistent observations.
        if (assistTarget == null || tick > selectionSince) bindVictim(actual);
    }

    boolean engaged(PitRegion pit, long tick) {
        return tick <= engagementUntil
                && leader != null
                && ctx.present(assistTarget, pit)
                && !ctx.protects(assistTarget)
                && position(leader).horizontalDistanceSq(position(assistTarget)) <= 4.5 * 4.5
                && Math.abs(leader.posY - assistTarget.posY) <= 3;
    }

    Vec3Point goal(
            Vec3Point self,
            Vec3Point pose,
            Vec3Point ordinary,
            float yaw,
            PitRegion pit,
            long tick,
            Vec3Point velocity) {
        return positioning.goal(
                leader,
                assistTarget,
                velocity,
                engaged(pit, tick),
                self,
                pose,
                ordinary,
                yaw,
                pit,
                tick);
    }

    float heading(Vec3Point pose) {
        return positioning.heading(pose, assistTarget);
    }

    void turnToward(float yaw, float pitch, long tick) {
        strikes.turnToward(yaw, pitch, tick);
    }

    void assist(PitRegion pit, long tick, boolean mayTurn, boolean equipmentReady, boolean sword) {
        strikes.perform(
                assistTarget,
                assist,
                engaged(pit, tick),
                pit,
                tick,
                mayTurn,
                equipmentReady,
                sword);
    }
}