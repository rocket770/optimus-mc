package dev.optimus.client.guard;

import dev.optimus.core.geometry.Vec3Point;

/** Checked close-follow intent after terrain and combat positioning are resolved. */
final class GuardFollowPlan {
    final Vec3Point goal;
    final float steeringYaw;
    final long idle;
    final boolean fighting, holdFight, copyingJump, descending;

    GuardFollowPlan(
            Vec3Point goal,
            float steeringYaw,
            long idle,
            boolean fighting,
            boolean holdFight,
            boolean copyingJump,
            boolean descending) {
        this.goal = goal;
        this.steeringYaw = steeringYaw;
        this.idle = idle;
        this.fighting = fighting;
        this.holdFight = holdFight;
        this.copyingJump = copyingJump;
        this.descending = descending;
    }
}