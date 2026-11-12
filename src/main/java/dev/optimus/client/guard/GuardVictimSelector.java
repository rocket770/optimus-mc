package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.box;
import static dev.optimus.client.guard.GuardContext.distance;
import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import net.minecraft.entity.player.EntityPlayer;

/** Resolves explicit victims and visible swing directions without changing engagement state. */
final class GuardVictimSelector {
    private final GuardContext ctx;

    GuardVictimSelector(GuardContext ctx) {
        this.ctx = ctx;
    }

    EntityPlayer find(EntityPlayer leader, String victim, PitRegion pit) {
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
}