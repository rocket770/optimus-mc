package dev.optimus.client.guard;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import net.minecraft.entity.player.EntityPlayer;

/** Chooses checked guard combat positions without crossing the guarded player's view. */
final class GuardCombatPosition {
    // Keep the approach inside the existing 1.8-block leader tether.
    private static final double[] APPROACH_OFFSETS = {
        -.08, .16, .38, .60, .82, 1.04, 1.26, 1.48, 1.70
    };
    private final GuardContext ctx;
    private final GuardThreatTracker threats;
    private int combatSide;
    private Vec3Point fightingPost, fightingOwner;
    private float fightViewYaw, fightViewPitch;

    GuardCombatPosition(GuardContext ctx, GuardThreatTracker threats) {
        this.ctx = ctx;
        this.threats = threats;
    }

    int side() {
        return combatSide;
    }

    void retarget() {
        combatSide = 0;
        fightingPost = null;
    }

    void rememberView(EntityPlayer leader) {
        fightViewYaw = leader.rotationYawHead;
        fightViewPitch = leader.rotationPitch;
    }

    void reset() {
        retarget();
        fightingOwner = null;
        fightViewYaw = fightViewPitch = 0;
    }

    Vec3Point goal(
            EntityPlayer leader,
            EntityPlayer assistTarget,
            Vec3Point velocity,
            boolean engaged,
            Vec3Point self,
            Vec3Point pose,
            Vec3Point ordinary,
            PitRegion pit) {
        if (!engaged
                || !ctx.mc.thePlayer.onGround
                || !leader.onGround
                || ctx.navigator.jumpInProgress()
                || self.horizontalDistanceSq(pose) > 1.8 * 1.8
                || Math.abs(self.y - pose.y) > .6) {
            combatSide = 0;
            fightingPost = fightingOwner = null;
            return ordinary;
        }
        if (fightingPost != null && fightingOwner != null) {
            Vec3Point shifted =
                    new Vec3Point(
                            fightingPost.x + pose.x - fightingOwner.x,
                            pose.y,
                            fightingPost.z + pose.z - fightingOwner.z);
            if (pose.horizontalDistanceSq(fightingOwner) <= .6 * .6
                    && ctx.navigator.autoStep(self, shifted, pit)) fightingPost = shifted;
            else fightingPost = null;
        }
        fightingOwner = pose;
        float yaw = heading(pose, assistTarget);
        ordinary = GuardFollow.anchor(pose, yaw);
        Vec3Point ownerEye =
                new Vec3Point(
                        pose.x + velocity.x, pose.y + leader.getEyeHeight(), pose.z + velocity.z);
        CollisionGeometry.Box body = CollisionGeometry.body(self);
        CollisionGeometry.Box viewMargin =
                new CollisionGeometry.Box(
                        body.minX - .06,
                        body.minY,
                        body.minZ - .06,
                        body.maxX + .06,
                        body.maxY,
                        body.maxZ + .06);
        boolean inView =
                CombatAim.hit(ownerEye, viewMargin, fightViewYaw, fightViewPitch, 3) != null;
        CollisionGeometry.Box forecast = threats.anticipatedBox(assistTarget);
        if (!inView
                && ctx.reachable(self, assistTarget)
                && ctx.reachable(self, forecast, CombatClicks.ATTACK_REACH - .12)
                && self.horizontalDistanceSq(pose) <= 1.25 * 1.25) {
            if (fightingPost == null
                    || !ctx.reachable(fightingPost, assistTarget)
                    || fightingPost.horizontalDistanceSq(pose) > 1.5 * 1.5
                    || !ctx.navigator.autoStep(self, fightingPost, pit)) fightingPost = self;
            combatSide = 1;
            return fightingPost;
        }
        fightingPost = null;
        Vec3Point eye =
                new Vec3Point(ordinary.x, ordinary.y + ctx.mc.thePlayer.getEyeHeight(), ordinary.z);
        if (!inView
                && combatSide == 0
                && ctx.reachable(ordinary, forecast, CombatClicks.ATTACK_REACH - .12))
            return ordinary;
        double angle = Math.toRadians(yaw), fx = -Math.sin(angle), fz = Math.cos(angle);
        Vec3Point chosen = null;
        double best = Double.POSITIVE_INFINITY;
        int side = 0;
        for (int pass = 0; pass < 2 && chosen == null; pass++) {
            for (int sign :
                    new int[] {
                        combatSide == 0 ? 1 : combatSide, combatSide == 0 ? -1 : -combatSide
                    })
                for (double advance : APPROACH_OFFSETS) {
                    Vec3Point candidate =
                            new Vec3Point(
                                    pose.x + fx * advance + fz * .42 * sign,
                                    pose.y,
                                    pose.z + fz * advance - fx * .42 * sign);
                    if (!pit.containsBody(candidate, .3, 1.8)
                            || !ctx.navigator.autoStep(self, candidate, pit)) continue;
                    Vec3Point from =
                            new Vec3Point(
                                    candidate.x,
                                    candidate.y + ctx.mc.thePlayer.getEyeHeight(),
                                    candidate.z);
                    double separation = CombatAim.distanceSquared(from, forecast);
                    if (pass == 0
                            && !ctx.reachable(candidate, forecast, CombatClicks.ATTACK_REACH - .12))
                        continue;
                    if (pass == 1 && separation >= CombatAim.distanceSquared(eye, forecast) - .01)
                        continue;
                    double score =
                            pass == 0
                                    ? self.horizontalDistanceSq(candidate)
                                            + Math.max(0, advance + .08) * .3
                                            - (sign == combatSide ? .10 : 0)
                                    : separation
                                            + self.horizontalDistanceSq(candidate) * .1
                                            - (sign == combatSide ? .05 : 0);
                    if (score < best) {
                        chosen = candidate;
                        best = score;
                        side = sign;
                    }
                }
        }
        combatSide = side;
        if (chosen == null) return ordinary;
        // Use the checked direct shortcut; brief view crossing is preferable
        // to a slower staged sideways/forward movement and its corner jitter.
        fightingPost = chosen;
        return chosen;
    }

    float heading(Vec3Point pose, EntityPlayer assistTarget) {
        CollisionGeometry.Box target = threats.anticipatedBox(assistTarget);
        return (float)
                Math.toDegrees(
                        Math.atan2(
                                pose.x - (target.minX + target.maxX) / 2,
                                (target.minZ + target.maxZ) / 2 - pose.z));
    }
}