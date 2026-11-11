package dev.optimus.client;

import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NavigationView;
import java.util.*;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

/** Local depth-tested navigation diagnostics with restored GL state. */
final class NavigationOverlay {
    private final Deque<Vec3Point> trail = new ArrayDeque<>();
    private Object world;
    private long lastTick = Long.MIN_VALUE;

    void clear() {
        trail.clear();
        world = null;
        lastTick = Long.MIN_VALUE;
    }

    void observe(Object currentWorld, Vec3Point point, long tick) {
        if (world != currentWorld || tick < lastTick) clear();
        world = currentWorld;
        if (tick == lastTick) return;
        lastTick = tick;
        Vec3Point last = trail.peekLast();
        if (last != null && last.horizontalDistanceSq(point) > 64 * 64) {
            trail.clear();
            last = null;
        }
        if (last != null
                && last.horizontalDistanceSq(point) < .0001
                && Math.abs(last.y - point.y) < .01) return;
        trail.addLast(point);
        while (trail.size() > 80) trail.removeFirst();
    }

    List<Vec3Point> trail() {
        return Collections.unmodifiableList(new ArrayList<>(trail));
    }

    void render(Minecraft mc, NavigationView view, float partialTicks) {
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT
                        | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_DEPTH_BUFFER_BIT
                        | GL11.GL_LINE_BIT
                        | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glLineWidth(2);
            GL11.glTranslated(-x, -y, -z);
            GL11.glColor4f(.65f, .7f, .75f, .65f);
            line(trail(), .035);
            GL11.glColor4f(.1f, .85f, 1, .9f);
            line(view.route, .055);
            for (Vec3Point p : view.route) block(mc, p, .1f, .75f, 1, .35f);
            GL11.glColor4f(1, .6f, .12f, .95f);
            line(view.trajectory, .025);
            for (int i = 0; i < view.trajectory.size(); i += 5) {
                Vec3Point p = view.trajectory.get(i);
                RenderGlobal.drawSelectionBoundingBox(
                        new AxisAlignedBB(
                                p.x - .3, p.y + .01, p.z - .3, p.x + .3, p.y + 1.8, p.z + .3));
            }
            if (!view.trajectory.isEmpty()) {
                block(mc, view.trajectory.get(0), 1, .6f, .12f, .65f);
                block(mc, view.trajectory.get(view.trajectory.size() - 1), .2f, 1, .35f, .9f);
            }
            if (view.current != null) {
                block(mc, view.current, .2f, 1, .35f, .95f);
                marker(view.current, .2f, 1, .35f);
            }
            if (view.goal != null) marker(view.goal, 1, .3f, .85f);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void line(List<Vec3Point> points, double lift) {
        if (points.size() < 2) return;
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (Vec3Point p : points) GL11.glVertex3d(p.x, p.y + lift, p.z);
        GL11.glEnd();
    }

    private static void marker(Vec3Point p, float r, float g, float b) {
        GL11.glColor4f(r, g, b, 1);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(p.x - .18, p.y + .08, p.z);
        GL11.glVertex3d(p.x + .18, p.y + .08, p.z);
        GL11.glVertex3d(p.x, p.y + .08, p.z - .18);
        GL11.glVertex3d(p.x, p.y + .08, p.z + .18);
        GL11.glVertex3d(p.x, p.y + .08, p.z);
        GL11.glVertex3d(p.x, p.y + .8, p.z);
        GL11.glEnd();
    }

    private static void block(
            Minecraft mc, Vec3Point point, float r, float g, float b, float alpha) {
        if (mc.thePlayer.getDistanceSq(point.x, point.y, point.z) > 48 * 48) return;
        AxisAlignedBB bounds = supportBounds(mc.theWorld, point);
        if (bounds == null) return;
        bounds = bounds.expand(.004, .004, .004);
        GL11.glColor4f(r, g, b, alpha);
        RenderGlobal.drawSelectionBoundingBox(bounds);
        GL11.glColor4f(r, g, b, alpha * .25f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(bounds.minX, bounds.maxY, bounds.minZ);
        GL11.glVertex3d(bounds.minX, bounds.maxY, bounds.maxZ);
        GL11.glVertex3d(bounds.maxX, bounds.maxY, bounds.maxZ);
        GL11.glVertex3d(bounds.maxX, bounds.maxY, bounds.minZ);
        GL11.glEnd();
    }

    static AxisAlignedBB supportBounds(net.minecraft.world.World world, Vec3Point point) {
        BlockPos pos = new BlockPos(point.x, point.y - .02, point.z);
        if (!world.isBlockLoaded(pos)) return null;
        net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
        if (state.getBlock().getMaterial() == Material.air) return null;
        state.getBlock().setBlockBoundsBasedOnState(world, pos);
        return state.getBlock().getSelectedBoundingBox(world, pos);
    }
}