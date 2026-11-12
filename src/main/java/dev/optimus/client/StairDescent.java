package dev.optimus.client;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import net.minecraft.client.Minecraft;

/** Keeps momentum down a checked staircase instead of stopping at each lower grid cell. */
final class StairDescent {
    private final Minecraft mc;
    private Vec3Point destination, origin;

    StairDescent(Minecraft mc) {
        this.mc = mc;
    }

    void reset() {
        destination = origin = null;
    }

    boolean active() {
        return destination != null;
    }

    LobbyNavigator.Step step(
            CollisionGeometry geometry, PitRegion area, Vec3Point goal, boolean chasing) {
        Vec3Point current = PlayerGeometry.position(mc.thePlayer);
        if (destination != null
                && (destination.horizontalDistanceSq(goal) > .36
                        || Math.abs(destination.y - goal.y) > .6)) reset();
        if (origin == null && mc.thePlayer.onGround) origin = current;
        boolean corridor =
                origin != null
                        && goal.y < current.y - .26
                        && geometry.free(CollisionGeometry.body(current))
                        && geometry.autoStepClear(origin, goal, area);
        if (!corridor) {
            reset();
            return null;
        }
        double length = Math.sqrt(origin.horizontalDistanceSq(goal));
        double dx = (goal.x - origin.x) / length, dz = (goal.z - origin.z) / length;
        double along = (current.x - origin.x) * dx + (current.z - origin.z) * dz;
        double across = Math.abs((current.x - origin.x) * dz - (current.z - origin.z) * dx);
        if (across > .6 || along < -.3 || along > length + .6) {
            reset();
            return null;
        }
        destination = goal;
        double remaining = current.horizontalDistanceSq(goal);
        boolean sprint = remaining > (chasing ? 2.1 * 2.1 : 1.2 * 1.2);
        return new LobbyNavigator.Step(
                LobbyNavigator.Status.MOVING, goal, false, false, sprint, null);
    }
}