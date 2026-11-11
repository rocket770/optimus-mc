package dev.optimus.client;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.Vec3Point;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

/** Coordinate conversion shared by the client controllers. */
final class PlayerGeometry {
    private PlayerGeometry() {}

    static Vec3Point position(EntityPlayer player) {
        return new Vec3Point(player.posX, player.posY, player.posZ);
    }

    static CollisionGeometry.Box hitbox(EntityPlayer player) {
        AxisAlignedBB box = player.getEntityBoundingBox();
        return new CollisionGeometry.Box(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}