package dev.optimus.client;

import dev.optimus.core.geometry.Vec3Point;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.world.EnumDifficulty;

/**
 * Closed-loop replay: real navigator -> real key handler -> native movement/collisions. No window,
 * sockets, live client, or teleporting along the planner's predicted arc.
 */
final class NativeGuardFixture {
    static final class Player extends EntityPlayerSP {
        String label = "OfflineBot";
        boolean sprint;
        int jumpCooldown, jumps;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public boolean isEntityAlive() {
            return true;
        }

        @Override
        public String getName() {
            return label;
        }

        @Override
        public void swingItem() {}

        @Override
        public void addChatMessage(IChatComponent message) {}

        @Override
        public boolean isPotionActive(Potion potion) {
            return false;
        }

        @Override
        public boolean isSprinting() {
            return sprint;
        }

        @Override
        public void setSprinting(boolean value) {
            sprint = value;
        }

        @Override
        public float getAIMoveSpeed() {
            return sprint ? .13f : .1f;
        }

        @Override
        public boolean isServerWorld() {
            return true;
        }

        @Override
        public boolean isInWater() {
            return false;
        }

        @Override
        public boolean isInLava() {
            return false;
        }

        @Override
        public boolean isOnLadder() {
            return false;
        }

        @Override
        public boolean isSneaking() {
            return Minecraft.getMinecraft().gameSettings.keyBindSneak.isKeyDown();
        }

        @Override
        public boolean isWet() {
            return false;
        }

        @Override
        public void addMovementStat(double x, double y, double z) {}

        @Override
        protected boolean canTriggerWalking() {
            return false;
        }

        @Override
        protected void doBlockCollisions() {}

        @Override
        protected void updateFallState(double y, boolean ground, Block b, BlockPos p) {}

        void physics(GameSettings keys) {
            // EntityLivingBase.onLivingUpdate input damping, deadzone and jump cooldown.
            if (jumpCooldown > 0) jumpCooldown--;
            if (Math.abs(motionX) < .005) motionX = 0;
            if (Math.abs(motionY) < .005) motionY = 0;
            if (Math.abs(motionZ) < .005) motionZ = 0;
            float forward =
                    (keys.keyBindForward.isKeyDown() ? 1 : 0)
                            - (keys.keyBindBack.isKeyDown() ? 1 : 0);
            float side =
                    (keys.keyBindLeft.isKeyDown() ? 1 : 0)
                            - (keys.keyBindRight.isKeyDown() ? 1 : 0);
            if (keys.keyBindSneak.isKeyDown()) {
                forward *= .3f;
                side *= .3f;
            }
            if (forward >= .8f
                    && keys.keyBindSprint.isKeyDown()
                    && getFoodStats().getFoodLevel() > 6) sprint = true;
            if (forward < .8f || isCollidedHorizontally) sprint = false;
            if (!keys.keyBindJump.isKeyDown()) jumpCooldown = 0;
            else if (onGround && jumpCooldown == 0) {
                // Vanilla jump impulse, omitting Forge events/statistics that need a booted game.
                motionY = (double) .42f;
                if (sprint) {
                    float yaw = rotationYaw * .017453292f;
                    motionX -= MathHelper.sin(yaw) * .2f;
                    motionZ += MathHelper.cos(yaw) * .2f;
                }
                jumpCooldown = 10;
                jumps++;
            }
            prevPosX = posX;
            prevPosY = posY;
            prevPosZ = posZ;
            // Calls vanilla moveFlying AND moveEntity, including its two step-up attempts.
            moveEntityWithHeading(side * .98f, forward * .98f);
            // EntityPlayer refreshes this after movement; do not update it before takeoff.
            jumpMovementFactor = sprint ? .026f : .02f;
        }
    }

    static final class Controller extends PlayerControllerMP {
        int attacks;
        java.util.function.BiConsumer<EntityPlayer, Entity> attackObserver;

        private Controller() {
            super(null, null);
        }

        @Override
        public void updateController() {}

        @Override
        public void attackEntity(EntityPlayer player, Entity target) {
            attacks++;
            player.setSprinting(false);
            if (attackObserver != null) attackObserver.accept(player, target);
        }
    }

    private static final class FloorBlock extends Block {
        private FloorBlock() {
            super(Material.rock);
        }
    }

    static final class World extends WorldClient {
        private static final IBlockState FLOOR = new FloorBlock().getDefaultState();
        Map<String, List<AxisAlignedBB>> cells;
        boolean combatRays;

        private World() {
            super(null, null, 0, EnumDifficulty.NORMAL, null);
        }

        @Override
        public boolean isBlockLoaded(BlockPos p) {
            return true;
        }

        @Override
        public IBlockState getBlockState(BlockPos p) {
            return FLOOR;
        }

        @Override
        public boolean isFlammableWithin(AxisAlignedBB b) {
            return false;
        }

        @Override
        public MovingObjectPosition rayTraceBlocks(Vec3 a, Vec3 b) {
            if (!combatRays) return null;
            MovingObjectPosition nearest = null;
            double best = Double.POSITIVE_INFINITY;
            AxisAlignedBB query =
                    new AxisAlignedBB(
                            Math.min(a.xCoord, b.xCoord),
                            Math.min(a.yCoord, b.yCoord),
                            Math.min(a.zCoord, b.zCoord),
                            Math.max(a.xCoord, b.xCoord),
                            Math.max(a.yCoord, b.yCoord),
                            Math.max(a.zCoord, b.zCoord));
            for (AxisAlignedBB box : getCollidingBoundingBoxes(null, query)) {
                MovingObjectPosition hit = box.calculateIntercept(a, b);
                if (hit == null) continue;
                double x = hit.hitVec.xCoord - a.xCoord,
                        y = hit.hitVec.yCoord - a.yCoord,
                        z = hit.hitVec.zCoord - a.zCoord,
                        d = x * x + y * y + z * z;
                if (d < best) {
                    best = d;
                    nearest = hit;
                }
            }
            return nearest;
        }

        @Override
        public List<AxisAlignedBB> getCollidingBoundingBoxes(Entity e, AxisAlignedBB b) {
            List<AxisAlignedBB> result = new ArrayList<>();
            for (int x = MathHelper.floor_double(b.minX); x <= MathHelper.floor_double(b.maxX); x++)
                for (int y = MathHelper.floor_double(b.minY) - 1;
                        y <= MathHelper.floor_double(b.maxY);
                        y++)
                    for (int z = MathHelper.floor_double(b.minZ);
                            z <= MathHelper.floor_double(b.maxZ);
                            z++) {
                        List<AxisAlignedBB> boxes = cells.get(x + ":" + y + ":" + z);
                        if (boxes != null)
                            for (AxisAlignedBB box : boxes)
                                if (box.intersectsWith(b)) result.add(box);
                    }
            return result;
        }

        void add(int x, int y, int z, double a, double b, double c, double d, double e, double f) {
            cells.computeIfAbsent(x + ":" + y + ":" + z, k -> new ArrayList<>())
                    .add(new AxisAlignedBB(a, b, c, d, e, f));
        }
    }

    static void position(Player p, Vec3Point at, float yaw) {
        p.width = .6f;
        p.height = 1.8f;
        p.stepHeight = .6f;
        p.setPosition(at.x, at.y, at.z);
        p.onGround = true;
        p.motionX = p.motionZ = 0;
        p.motionY = -.0784;
        p.rotationYaw = yaw;
        p.sprint = false;
        p.jumpMovementFactor = .02f;
        p.jumpCooldown = p.jumps = 0;
        p.isCollidedHorizontally = p.isCollidedVertically = p.isCollided = false;
    }
}