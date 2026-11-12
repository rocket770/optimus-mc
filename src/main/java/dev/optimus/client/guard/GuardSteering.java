package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.MotionProgress;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.WalkSteering;
import net.minecraft.util.BlockPos;

/** Chooses checked movement keys and braking for a resolved close-follow plan. */
final class GuardSteering {
    private static final double SETTLE_EXIT_DISTANCE_SQ = .16 * .16;
    private final GuardContext ctx;
    private final GuardCombat combat;
    private final MotionProgress followProgress = new MotionProgress();
    private boolean settled, desktopMoving, coasting;
    private long directMoves, lastDirectTick = Long.MIN_VALUE;

    GuardSteering(GuardContext ctx, GuardCombat combat) {
        this.ctx = ctx;
        this.combat = combat;
    }

    static final class Result {
        final NearbySteering.Input input;
        final boolean running, jump, recover;

        Result(NearbySteering.Input input, boolean running, boolean jump, boolean recover) {
            this.input = input;
            this.running = running;
            this.jump = jump;
            this.recover = recover;
        }
    }

    void reset() {
        settled = desktopMoving = coasting = false;
        followProgress.reset();
        lastDirectTick = Long.MIN_VALUE;
    }

    void resetTelemetry() {
        directMoves = 0;
    }

    void unsettle() {
        settled = false;
    }

    long directMoves() {
        return directMoves;
    }

    Result choose(
            GuardFollowPlan plan,
            GuardObservation observation,
            PitRegion pit,
            long tick,
            boolean beforeMovement) {
        Vec3Point self = position(ctx.mc.thePlayer),
                pose = observation.pose,
                velocity = observation.velocity,
                goal = plan.goal;
        float yaw = observation.yaw, steeringYaw = plan.steeringYaw;
        boolean sneak = observation.sneak,
                sprint = observation.sprint,
                knocked = observation.knocked,
                fighting = plan.fighting,
                holdFight = plan.holdFight,
                copyingJump = plan.copyingJump,
                descending = plan.descending;
        long idle = plan.idle;
        GuardInputRelay.Frame frame = observation.frame;
        GuardWindowsKeys.Input desktop = observation.desktop;
        final boolean
                running =
                        !sneak
                                && (sprint
                                        || ctx.mc.thePlayer.onGround
                                                && self.horizontalDistanceSq(goal) > .75 * .75),
                ducking = sneak;
        double friction =
                ctx.mc
                                .theWorld
                                .getBlockState(new BlockPos(self.x, self.y - 1, self.z))
                                .getBlock()
                                .slipperiness
                        * .91;
        double speed =
                ctx.mc.thePlayer.getAIMoveSpeed()
                        / (ctx.mc.thePlayer.isSprinting() ? 1.3 : 1)
                        * (running ? 1.3 : 1);
        double acceleration =
                ctx.mc.thePlayer.onGround
                        ? speed * .16277136 / (friction * friction * friction)
                        : ctx.mc.thePlayer.jumpMovementFactor;
        if (sneak) acceleration *= .3;
        NearbySteering.Input mirrored = frame == null || fighting ? null : frame.input;
        Vec3Point steeringVelocity = holdFight ? new Vec3Point(0, 0, 0) : velocity;
        final byte[] inputChecks = new byte[NearbySteering.Input.values().length];
        java.util.function.Predicate<NearbySteering.Input> safe =
                candidate -> {
                    int key = candidate.ordinal();
                    if (inputChecks[key] == 0)
                        inputChecks[key] =
                                (byte) (safeInput(candidate, running, ducking, pit) ? 1 : 2);
                    return inputChecks[key] == 1;
                };
        NearbySteering.Input input =
                GuardFollow.choose(
                        self,
                        new Vec3Point(ctx.mc.thePlayer.motionX, 0, ctx.mc.thePlayer.motionZ),
                        pose,
                        goal,
                        steeringVelocity,
                        steeringYaw,
                        ctx.mc.thePlayer.rotationYaw,
                        acceleration,
                        idle >= 0 ? 0 : ctx.mc.thePlayer.onGround ? friction : .91,
                        mirrored,
                        safe);
        double heading = Math.toRadians(yaw),
                behind =
                        (self.x - pose.x) * -Math.sin(heading)
                                + (self.z - pose.z) * Math.cos(heading);
        // A delayed position estimate must not reapply movement after a local key release.
        // Coast while native momentum decays, then resume ordinary position correction.
        boolean moving = desktop != null && desktop.movement != NearbySteering.Input.NONE;
        if (desktop != null && desktopMoving && !moving) coasting = true;
        if (desktop == null
                || moving
                || knocked
                || fighting
                || !ctx.mc.thePlayer.onGround
                || Math.max(Math.abs(ctx.mc.thePlayer.motionX), Math.abs(ctx.mc.thePlayer.motionZ))
                        < .005) coasting = false;
        desktopMoving = moving;
        boolean matchingInput =
                desktop != null
                        && (desktop.movement != NearbySteering.Input.NONE
                                || coasting
                                || self.horizontalDistanceSq(goal) <= .1 * .1);
        if (matchingInput
                && !fighting
                && !knocked
                && combat.side() == 0
                && !observation.stalled
                && idle < 0
                && behind <= -.30
                && self.horizontalDistanceSq(goal) <= .4 * .4) {
            NearbySteering.Input direct = desktop.movement;
            if (direct != NearbySteering.Input.NONE) {
                Vec3Point direction = direct.point(self, yaw, 1);
                direct =
                        WalkSteering.input(
                                (float)
                                        Math.toDegrees(
                                                Math.atan2(
                                                        self.x - direction.x,
                                                        direction.z - self.z)),
                                ctx.mc.thePlayer.rotationYaw);
            }
            if (safe.test(direct)) {
                input = direct;
                if (direct != NearbySteering.Input.NONE && tick != lastDirectTick) {
                    directMoves++;
                    lastDirectTick = tick;
                }
            }
        }
        if (combat.side() != 0
                && ctx.mc.thePlayer.onGround
                && !ctx.reachable(self, combat.target())
                && self.horizontalDistanceSq(pose) < .8 * .8
                && self.horizontalDistanceSq(goal) < .2 * .2) {
            Vec3Point towardPoint =
                    self.horizontalDistanceSq(goal) > .0001 ? goal : position(combat.target());
            float toward =
                    (float)
                            Math.toDegrees(
                                    Math.atan2(self.x - towardPoint.x, towardPoint.z - self.z));
            NearbySteering.Input close = WalkSteering.input(toward, ctx.mc.thePlayer.rotationYaw);
            if (safe.test(close)) input = close;
        }
        // Hysteresis absorbs packet quantization and lets native friction stop
        // the bot instead of repeatedly tapping opposing keys around one point.
        boolean quiet =
                fighting
                        ? steeringVelocity.x * steeringVelocity.x
                                        + steeringVelocity.z * steeringVelocity.z
                                < .02 * .02
                        : (desktop != null
                                ? desktop.movement == NearbySteering.Input.NONE
                                : velocity.x * velocity.x + velocity.z * velocity.z < .08 * .08);
        double error = self.horizontalDistanceSq(goal);
        Vec3Point stop =
                new Vec3Point(
                        self.x + ctx.mc.thePlayer.motionX / (1 - friction),
                        self.y,
                        self.z + ctx.mc.thePlayer.motionZ / (1 - friction));
        boolean combatReady = combat.side() == 0 || ctx.reachable(self, combat.target());
        if (!combatReady
                || idle >= 0
                || !quiet
                || !ctx.mc.thePlayer.onGround
                || copyingJump
                || error > SETTLE_EXIT_DISTANCE_SQ) settled = false;
        else if (error < .12 * .12 && stop.horizontalDistanceSq(goal) < .08 * .08) settled = true;
        if (settled && safe.test(NearbySteering.Input.NONE)) input = NearbySteering.Input.NONE;
        boolean stuck =
                !beforeMovement
                        && followProgress.stuck(
                                self,
                                tick,
                                error > SETTLE_EXIT_DISTANCE_SQ,
                                ctx.mc.thePlayer.onGround);
        if ((stuck || input == NearbySteering.Input.NONE && error > .64)
                && ctx.mc.thePlayer.onGround) return new Result(input, false, false, true);
        boolean jump =
                !descending
                        && ctx.mc.thePlayer.onGround
                        && (copyingJump || idle == 0)
                        && ctx.navigator.safeGuardJump(input, running && input.forward > 0, pit);
        return new Result(input, running && input.forward > 0, jump, false);
    }

    private boolean safeInput(
            NearbySteering.Input input, boolean sprint, boolean sneak, PitRegion pit) {
        if (ctx.mc.thePlayer.onGround)
            return ctx.navigator.safeGuardInput(input, sprint, sneak, pit);
        Vec3Point from = position(ctx.mc.thePlayer);
        Vec3Point delta =
                GroundMotion.delta(
                        ctx.mc.thePlayer.motionX,
                        ctx.mc.thePlayer.motionZ,
                        ctx.mc.thePlayer.rotationYaw,
                        input.forward,
                        input.side,
                        ctx.mc.thePlayer.jumpMovementFactor,
                        1);
        return ctx.navigator.safeKnockbackStep(
                from,
                new Vec3Point(
                        from.x + delta.x, from.y + ctx.mc.thePlayer.motionY, from.z + delta.z),
                pit);
    }
}