package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.box;
import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardAssist;
import dev.optimus.core.guard.GuardSwingSource;
import net.minecraft.entity.player.EntityPlayer;

/** Guard target acquisition and engagement lifecycle, separate from input and movement. */
final class GuardCombat {
    private final GuardContext ctx;
    private final GuardThreatTracker threats;
    private final GuardVictimSelector selector;
    private final GuardCombatPosition positioning;
    private final GuardAttack strikes;
    private final GuardAssist assist = new GuardAssist();
    private EntityPlayer leader, assistTarget, selectionCandidate;
    private long controlTick,
            engagementUntil = Long.MIN_VALUE,
            acquireUntil = Long.MIN_VALUE,
            selectionSince = Long.MIN_VALUE,
            defenseSerial;
    private boolean defending, targetConfirmed;

    GuardCombat(GuardContext ctx, CombatClicks clicks) {
        this.ctx = ctx;
        threats = new GuardThreatTracker(ctx);
        selector = new GuardVictimSelector(ctx);
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
        targetConfirmed = false;
        engagementUntil = acquireUntil = selectionSince = Long.MIN_VALUE;
    }

    void observeThreats(PitRegion pit, long tick) {
        threats.observe(leader, pit, tick);
        if (assist.live(tick) && !defending && !targetConfirmed) {
            EntityPlayer candidate = threats.damagedTarget(tick);
            if (candidate != null) {
                bindVictim(candidate);
                targetConfirmed = true;
            }
        }
        EntityPlayer attacker = threats.attacker(tick);
        if (attacker == null) return;
        if (assistTarget != attacker) {
            resetTarget();
        }
        if (assistTarget != attacker || tick > engagementUntil) positioning.rememberView(leader);
        assistTarget = attacker;
        assist.observe("defend:" + (++defenseSerial), attacker.getUniqueID().toString(), tick);
        defending = targetConfirmed = true;
        engagementUntil = tick + GuardAssist.ENGAGEMENT_TICKS;
    }

    void observeSwing(
            String event, String victim, PitRegion pit, long tick, GuardSwingSource source) {
        EntityPlayer previous = assistTarget;
        EntityPlayer selected = findVictim(victim, pit);
        boolean continuing = victim.isEmpty() && engaged(pit, tick);
        // Head and swing packets can arrive apart. An unresolved swing keeps
        // the active opponent; an identified new victim still replaces it.
        assistTarget =
                continuing && (source == GuardSwingSource.LOCAL_KEYS || selected == null)
                        ? previous
                        : selected;
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
        targetConfirmed = assistTarget != null && (!victim.isEmpty() || targetConfirmed);
        strikes.newSwing(leader, assistTarget, tick, source);
        defending = false;
        assist.observe(
                event, assistTarget == null ? "" : assistTarget.getUniqueID().toString(), tick);
        acquireUntil = tick + Math.min(12, Math.max(6, (ctx.host.guardPingMillis() + 49) / 50 + 2));
        selectionCandidate = null;
        selectionSince = Long.MIN_VALUE;
        if (assistTarget != null) {
            if (assistTarget != previous || tick > engagementUntil)
                positioning.rememberView(leader);
            engagementUntil = tick + GuardAssist.ENGAGEMENT_TICKS;
        }
    }

    EntityPlayer findVictim(String victim, PitRegion pit) {
        return selector.find(leader, victim, pit);
    }

    private void resetTarget() {
        targetConfirmed = false;
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
        engagementUntil = controlTick + GuardAssist.ENGAGEMENT_TICKS;
    }

    void reconcileSwing(PitRegion pit) {
        if (targetConfirmed) return;
        EntityPlayer actual = findVictim("", pit);
        if (actual != null) bindVictim(actual);
    }

    void repairSelection(PitRegion pit, long tick) {
        if (!assist.live(tick) || defending || targetConfirmed || tick > acquireUntil) return;
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
        return assist.live(tick)
                && tick <= engagementUntil
                && leader != null
                && ctx.present(assistTarget, pit)
                && !ctx.protects(assistTarget)
                && (position(leader).horizontalDistanceSq(position(assistTarget)) <= 4.5 * 4.5
                        || ctx.reachable(
                                position(ctx.mc.thePlayer),
                                box(assistTarget),
                                CombatClicks.SWING_REACH))
                && Math.abs(leader.posY - assistTarget.posY) <= 3;
    }

    Vec3Point goal(
            Vec3Point self,
            Vec3Point pose,
            Vec3Point ordinary,
            PitRegion pit,
            long tick,
            Vec3Point velocity) {
        return positioning.goal(
                leader, assistTarget, velocity, engaged(pit, tick), self, pose, ordinary, pit);
    }

    float heading(Vec3Point pose) {
        return positioning.heading(pose, assistTarget);
    }

    void turnToward(float yaw, float pitch, long tick) {
        strikes.turnToward(yaw, pitch, tick, assist.live(tick));
    }

    void assist(PitRegion pit, long tick, boolean mayTurn, boolean equipmentReady, boolean sword) {
        boolean fighting = engaged(pit, tick);
        if (assistTarget != null && !fighting) {
            assist.cancel();
            return;
        }
        // Give delayed victim/head packets time to identify a real target before mirroring air.
        if (assistTarget == null && tick <= acquireUntil) return;
        strikes.perform(
                assistTarget,
                assistTarget != null
                        && threats.retreating(assistTarget, position(ctx.mc.thePlayer)),
                assist,
                fighting,
                pit,
                tick,
                mayTurn,
                equipmentReady,
                sword);
    }
}