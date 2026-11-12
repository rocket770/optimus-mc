package dev.optimus.client.guard;

import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;

/** Close-follow idle activity and waiting patrol lifecycle; movement uses the shared navigator. */
final class GuardIdle {
    private final GuardContext ctx;
    private Vec3Point stillPoint;
    private boolean waiting;
    private float stillYaw;
    private long lastActiveTick, idleAt = Long.MIN_VALUE;

    GuardIdle(GuardContext ctx) {
        this.ctx = ctx;
    }

    static final class Offset {
        final Vec3Point pose;
        final long phase;

        Offset(Vec3Point pose, long phase) {
            this.pose = pose;
            this.phase = phase;
        }
    }

    void reset() {
        returned();
        stillPoint = null;
        idleAt = Long.MIN_VALUE;
    }

    boolean waiting() {
        return waiting;
    }

    void returned() {
        if (waiting) ctx.host.guardCancelRoam();
        waiting = false;
    }

    Offset following(Vec3Point pose, float yaw, Vec3Point velocity, boolean pending, long tick) {
        if (stillPoint == null
                || stillPoint.horizontalDistanceSq(pose) > .0025
                || Math.abs(stillPoint.y - pose.y) > .05
                || Math.abs(CombatTurn.wrap(yaw - stillYaw)) > 3
                || velocity.horizontalDistanceSq(new Vec3Point(0, velocity.y, 0)) > .0004
                || pending) {
            stillPoint = pose;
            stillYaw = yaw;
            lastActiveTick = tick;
            idleAt = Long.MIN_VALUE;
        }
        if (tick - lastActiveTick >= 600 && idleAt == Long.MIN_VALUE) {
            idleAt = tick;
            lastActiveTick = tick;
        }
        long idle = idleAt == Long.MIN_VALUE ? -1 : tick - idleAt;
        if (idle > 8) idleAt = Long.MIN_VALUE;
        Vec3Point followPose = pose;
        if (idle >= 0 && idle < 6) {
            double offset = idle < 3 ? .10 : 0, angle = Math.toRadians(yaw);
            followPose =
                    new Vec3Point(
                            pose.x + Math.sin(angle) * offset,
                            pose.y,
                            pose.z - Math.cos(angle) * offset);
        }

        return new Offset(followPose, idle);
    }

    void waitForLeader(PitRegion pit, boolean beforeMovement) {
        waiting = true;
        if (!beforeMovement) ctx.host.guardRoam(pit);
        ctx.host.guardActivity("Waiting for " + ctx.settings().guardName + " | patrolling pit");
    }
}