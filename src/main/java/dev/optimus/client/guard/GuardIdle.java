package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.navigation.NearbySteering;
import net.minecraft.util.BlockPos;

/** Idle activity at a safe post or close-follow offset, with no target or input ownership. */
final class GuardIdle {
    private final GuardContext ctx;
    private Vec3Point stillPoint, waitingPost;
    private float stillYaw, waitingYaw;
    private long lastActiveTick, idleAt = Long.MIN_VALUE, waitingSince = Long.MIN_VALUE;

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
        stillPoint = waitingPost = null;
        idleAt = waitingSince = Long.MIN_VALUE;
    }

    boolean waiting() {
        return waitingPost != null;
    }

    void returned() {
        waitingPost = null;
        waitingSince = Long.MIN_VALUE;
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

    void waitForLeader(
            PitRegion pit, long tick, java.util.function.Predicate<NearbySteering.Input> safe) {
        if (waitingPost == null) {
            waitingPost = position(ctx.mc.thePlayer);
            waitingYaw = ctx.mc.thePlayer.rotationYaw;
            waitingSince = tick;
        }
        ctx.host.guardHold();
        Vec3Point self = position(ctx.mc.thePlayer);
        // Knockback establishes a new safe post; do not route back across terrain.
        if (self.horizontalDistanceSq(waitingPost) > 1 || Math.abs(self.y - waitingPost.y) > 1.5) {
            waitingPost = self;
            waitingSince = tick;
        }
        long elapsed = tick - waitingSince;
        if (elapsed >= 600) {
            long phase = (elapsed - 600) % 600;
            Vec3Point goal =
                    phase < 3
                            ? NearbySteering.Input.FORWARD.point(waitingPost, waitingYaw, .12)
                            : waitingPost;
            double friction =
                    ctx.mc
                                    .theWorld
                                    .getBlockState(new BlockPos(self.x, self.y - 1, self.z))
                                    .getBlock()
                                    .slipperiness
                            * .91;
            double acceleration =
                    ctx.mc.thePlayer.getAIMoveSpeed()
                            * .16277136
                            / (friction * friction * friction);
            NearbySteering.Input input =
                    GuardFollow.choose(
                            self,
                            new Vec3Point(ctx.mc.thePlayer.motionX, 0, ctx.mc.thePlayer.motionZ),
                            waitingPost,
                            goal,
                            new Vec3Point(0, 0, 0),
                            waitingYaw,
                            ctx.mc.thePlayer.rotationYaw,
                            acceleration,
                            phase < 6 ? 0 : ctx.mc.thePlayer.onGround ? friction : .91,
                            null,
                            candidate -> safe.test(candidate));
            if (phase >= 3 && self.horizontalDistanceSq(waitingPost) < .04 * .04)
                input = NearbySteering.Input.NONE;
            boolean jump =
                    phase == 0
                            && ctx.mc.thePlayer.onGround
                            && ctx.navigator.safeGuardJump(input, false, pit);
            ctx.host.guardMove(input, false, jump, false);
        }
        ctx.host.guardActivity("Waiting for " + ctx.settings().guardName + " in the pit");
    }
}