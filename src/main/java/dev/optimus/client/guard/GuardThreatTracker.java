package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.box;
import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayer;

/** Observed swings, damage and short motion histories; ambiguous attackers are never selected. */
final class GuardThreatTracker {
    private final GuardContext ctx;
    private final Map<EntityPlayer, ThreatSwing> threats = new IdentityHashMap<>();
    private final java.util.ArrayDeque<LeaderSample> leaderHistory = new java.util.ArrayDeque<>();
    private boolean damagePrimed;
    private int leaderHurt;
    private float leaderHealth;
    private long defendUntil = Long.MIN_VALUE;

    GuardThreatTracker(GuardContext ctx) {
        this.ctx = ctx;
    }

    private static final class LeaderSample {
        final long tick;
        final CollisionGeometry.Box body;

        LeaderSample(long tick, CollisionGeometry.Box body) {
            this.tick = tick;
            this.body = body;
        }
    }

    private static final class ThreatSwing {
        boolean swinging, used;
        int progress;
        long at = Long.MIN_VALUE, swungAt = Long.MIN_VALUE;
        int hurt;
        float health;
        long damagedAt = Long.MIN_VALUE, lookedAt = Long.MIN_VALUE;
        Vec3Point point, motion = new Vec3Point(0, 0, 0);
        long positionAt = Long.MIN_VALUE;
    }

    void observe(EntityPlayer leader, PitRegion pit, long tick) {
        // Remote hurt packets do not identify the attacker. Correlate actual
        // damage with one recent, visible, in-range swing aimed at the leader;
        // never infer an attacker merely because a bystander is nearby.
        boolean damaged =
                damagePrimed
                        && (leader.hurtTime > leaderHurt
                                || leader.getHealth() < leaderHealth - .01f);
        leaderHurt = leader.hurtTime;
        leaderHealth = leader.getHealth();
        damagePrimed = true;
        if (damaged) defendUntil = tick + 4;
        if (leaderHistory.isEmpty() || leaderHistory.peekLast().tick != tick)
            leaderHistory.addLast(new LeaderSample(tick, box(leader)));
        while (!leaderHistory.isEmpty() && tick - leaderHistory.peekFirst().tick > 6)
            leaderHistory.removeFirst();
        threats.keySet()
                .removeIf(candidate -> !ctx.present(candidate, pit) || ctx.protects(candidate));
        for (EntityPlayer candidate : ctx.mc.theWorld.playerEntities) {
            if (!ctx.present(candidate, pit) || ctx.protects(candidate)) continue;
            ThreatSwing swing = threats.get(candidate);
            if (swing == null) {
                swing = new ThreatSwing();
                swing.swinging = candidate.isSwingInProgress;
                swing.progress = candidate.swingProgressInt;
                swing.hurt = candidate.hurtTime;
                swing.health = candidate.getHealth();
                threats.put(candidate, swing);
            }
            if (swing.positionAt != tick) {
                Vec3Point now = position(candidate);
                if (swing.point != null) {
                    double elapsed = Math.max(1, tick - swing.positionAt);
                    swing.motion =
                            new Vec3Point(
                                    .65 * GuardFollow.clamp((now.x - swing.point.x) / elapsed, .36)
                                            + .35 * swing.motion.x,
                                    0,
                                    .65 * GuardFollow.clamp((now.z - swing.point.z) / elapsed, .36)
                                            + .35 * swing.motion.z);
                }
                swing.point = now;
                swing.positionAt = tick;
            }
            if (candidate.hurtTime > swing.hurt || candidate.getHealth() < swing.health - .01f)
                swing.damagedAt = tick;
            swing.hurt = candidate.hurtTime;
            swing.health = candidate.getHealth();
            Vec3Point ownerEye =
                    new Vec3Point(leader.posX, leader.posY + leader.getEyeHeight(), leader.posZ);
            Vec3Point aimed =
                    CombatAim.hit(
                            ownerEye,
                            box(candidate),
                            leader.rotationYawHead,
                            leader.rotationPitch,
                            CombatClicks.SWING_REACH);
            if (aimed != null && ctx.host.guardRay(ownerEye, aimed)) swing.lookedAt = tick;
            if (candidate.isSwingInProgress
                    && (!swing.swinging || candidate.swingProgressInt < swing.progress)) {
                swing.swungAt = tick;
                swing.at = Long.MIN_VALUE;
                swing.used = false;
            }
            if (!swing.used && swing.swungAt != Long.MIN_VALUE && tick - swing.swungAt <= 6) {
                Vec3Point eye =
                        new Vec3Point(
                                candidate.posX,
                                candidate.posY + candidate.getEyeHeight(),
                                candidate.posZ);
                for (LeaderSample sample : leaderHistory) {
                    Vec3Point hit =
                            CombatAim.hit(
                                    eye,
                                    sample.body,
                                    candidate.rotationYawHead,
                                    candidate.rotationPitch,
                                    3);
                    if (hit != null && ctx.host.guardRay(eye, hit)) {
                        swing.at = swing.swungAt;
                        break;
                    }
                }
            }
            swing.swinging = candidate.isSwingInProgress;
            swing.progress = candidate.swingProgressInt;
        }
    }

    CollisionGeometry.Box anticipatedBox(EntityPlayer target) {
        CollisionGeometry.Box actual = box(target);
        ThreatSwing sample = threats.get(target);
        if (sample == null) return actual;
        double ticks = Math.min(6, Math.max(0, ctx.host.guardPingMillis() / 50.0));
        double x = GuardFollow.clamp(sample.motion.x * ticks, .75),
                z = GuardFollow.clamp(sample.motion.z * ticks, .75);
        return new CollisionGeometry.Box(
                actual.minX + x,
                actual.minY,
                actual.minZ + z,
                actual.maxX + x,
                actual.maxY,
                actual.maxZ + z);
    }

    boolean damaged(EntityPlayer leader) {
        return damagePrimed
                && (leader.hurtTime > leaderHurt || leader.getHealth() < leaderHealth - .01f);
    }

    EntityPlayer damagedTarget(long tick) {
        EntityPlayer target = null;
        for (Map.Entry<EntityPlayer, ThreatSwing> entry : threats.entrySet()) {
            ThreatSwing evidence = entry.getValue();
            if (evidence.damagedAt == Long.MIN_VALUE
                    || evidence.lookedAt == Long.MIN_VALUE
                    || tick - evidence.damagedAt > 3
                    || tick - evidence.lookedAt > 6) continue;
            if (target != null) return null;
            target = entry.getKey();
        }
        return target;
    }

    EntityPlayer attacker(long tick) {
        if (defendUntil == Long.MIN_VALUE || tick > defendUntil) return null;
        EntityPlayer attacker = null;
        ThreatSwing evidence = null;
        for (Map.Entry<EntityPlayer, ThreatSwing> entry : threats.entrySet()) {
            ThreatSwing swing = entry.getValue();
            if (swing.used || swing.at == Long.MIN_VALUE || tick - swing.at > 6) continue;
            if (attacker != null) return null;
            attacker = entry.getKey();
            evidence = swing;
        }
        if (attacker != null) {
            evidence.used = true;
            defendUntil = Long.MIN_VALUE;
        }
        return attacker;
    }

    void reset() {
        damagePrimed = false;
        threats.clear();
        leaderHistory.clear();
        defendUntil = Long.MIN_VALUE;
    }
}