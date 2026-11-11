package dev.optimus.client.guard;

import dev.optimus.core.geometry.Vec3Point;
import net.minecraft.entity.player.EntityPlayer;

/** One immutable input observation shared by follow and combat decisions. */
final class GuardObservation {
    final EntityPlayer player;
    final Vec3Point pose, velocity;
    final float yaw, pitch;
    final boolean sneak, sprint, sword, knocked, stalled;
    final GuardInputRelay.Frame frame;
    final GuardWindowsKeys.Input desktop;

    GuardObservation(
            EntityPlayer player,
            Vec3Point pose,
            Vec3Point velocity,
            float yaw,
            float pitch,
            boolean sneak,
            boolean sprint,
            boolean sword,
            boolean knocked,
            boolean stalled,
            GuardInputRelay.Frame frame,
            GuardWindowsKeys.Input desktop) {
        this.player = player;
        this.pose = pose;
        this.velocity = velocity;
        this.yaw = yaw;
        this.pitch = pitch;
        this.sneak = sneak;
        this.sprint = sprint;
        this.sword = sword;
        this.knocked = knocked;
        this.stalled = stalled;
        this.frame = frame;
        this.desktop = desktop;
    }
}