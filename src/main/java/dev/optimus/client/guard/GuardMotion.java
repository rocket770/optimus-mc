package dev.optimus.client.guard;

import static dev.optimus.client.guard.GuardContext.box;
import static dev.optimus.client.guard.GuardContext.position;

import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.navigation.NearbySteering;
import net.minecraft.entity.player.EntityPlayer;

/** Coordinates route following and close steering while preserving committed jump ownership. */
final class GuardMotion {
    private final GuardContext ctx;
    private final GuardCombat combat;
    private final GuardIdle idlePolicy;
    private final GuardSteering steering;
    private boolean routing;
    private long knockbackUntil = Long.MIN_VALUE, recoverUntil = Long.MIN_VALUE;

    GuardMotion(GuardContext ctx, GuardCombat combat, GuardIdle idle) {
        this.ctx = ctx;
        this.combat = combat;
        this.idlePolicy = idle;
        steering = new GuardSteering(ctx, combat);
    }

    boolean routing() {
        return routing;
    }

    long directMoves() {
        return steering.directMoves();
    }

    void reset() {
        routing = false;
        knockbackUntil = recoverUntil = Long.MIN_VALUE;
        steering.reset();
    }

    void resetTelemetry() {
        steering.resetTelemetry();
    }

    boolean knocked(EntityPlayer leader, long tick, boolean damaged) {
        if (damaged) {
            knockbackUntil = tick + 12;
            steering.unsettle();
        }
        if (knockbackUntil == Long.MIN_VALUE) return false;
        if (tick > knockbackUntil && leader.onGround) {
            knockbackUntil = Long.MIN_VALUE;
            return false;
        }
        return true;
    }

    void follow(
            GuardObservation observation,
            PitRegion pit,
            long tick,
            boolean beforeMovement,
            boolean equipmentReady) {
        EntityPlayer player = observation.player;
        Vec3Point pose = observation.pose, velocity = observation.velocity;
        float yaw = observation.yaw, pitch = observation.pitch;
        boolean sneak = observation.sneak, knocked = observation.knocked;
        GuardInputRelay.Frame frame = observation.frame;
        GuardWindowsKeys.Input desktop = observation.desktop;
        boolean fighting = combat.engaged(pit, tick);
        float steeringYaw = fighting ? combat.heading(pose) : yaw;
        GuardIdle.Offset offset =
                idlePolicy.following(pose, yaw, velocity, combat.pending(tick), tick);
        long idle = offset.phase;
        Vec3Point followPose = offset.pose;
        Vec3Point self = position(ctx.mc.thePlayer), goal = GuardFollow.anchor(followPose, yaw);
        goal = combat.goal(self, pose, goal, pit, tick, velocity);
        if (!pit.containsBody(goal, .3, 1.8)) goal = pose;
        // A rear offset can be inside the step the leader just left. Route to
        // real feet instead of searching for an impossible point in that block.
        goal = ctx.navigator.guardDestination(goal, pose, !player.onGround, pit);
        // Normal target routes arrive within two blocks. Take over before that
        // tolerance can strand a guard outside its much closer following point.
        boolean copyingJump =
                !knocked
                        && !fighting
                        && ((frame != null && frame.jump)
                                || desktop != null && desktop.jump
                                || !player.onGround && velocity.y > .15 && pose.y - self.y > .15);
        boolean ordinaryJumpRise = copyingJump && pose.y >= self.y && pose.y - self.y <= 1.3;
        Vec3Point landing = ctx.navigator.pursuitDestination(goal, !player.onGround, pit);
        Vec3Point connection = landing == null ? goal : landing;
        Vec3Point support =
                ctx.mc.thePlayer.onGround
                        ? self
                        : ctx.navigator.pursuitDestination(self, true, pit);
        boolean descending = support != null && ctx.navigator.guardDrop(support, connection, pit);
        boolean connected =
                support != null && (ctx.navigator.autoStep(support, connection, pit) || descending);
        boolean directCombat =
                (fighting || combat.pending(tick))
                        && ctx.present(combat.target(), pit)
                        && !ctx.protects(combat.target())
                        && ctx.reachable(self, box(combat.target()), CombatClicks.SWING_REACH);
        if (directCombat && ctx.navigator.travelJumpInProgress()) ctx.navigator.reset();
        boolean travelJump = !knocked && !sneak && !directCombat;
        if (!beforeMovement
                && travelJump
                && idle < 0
                && connected
                && !descending
                && tick >= recoverUntil
                && ctx.host.guardTravelJump(
                        GuardFollow.travelGoal(self, connection, velocity), pit)) {
            routing = true;
            combat.assist(pit, tick, false, equipmentReady, observation.sword);
            return;
        }
        boolean closeFight =
                fighting
                        && ctx.mc.thePlayer.onGround
                        && !ctx.navigator.jumpInProgress()
                        && self.horizontalDistanceSq(pose) <= 4.5 * 4.5
                        && ctx.reachable(self, combat.target());
        boolean holdFight = closeFight && self.horizontalDistanceSq(pose) > 1.8 * 1.8;
        if (holdFight) {
            goal = self;
            connection = self;
            connected = true;
        }
        boolean terrainJump = false;
        if (copyingJump && ctx.mc.thePlayer.onGround) {
            NearbySteering.Input movement =
                    desktop != null ? desktop.movement : frame != null ? frame.input : null;
            Vec3Point ahead =
                    movement != null && movement != NearbySteering.Input.NONE
                            ? movement.point(self, yaw, 3)
                            : null;
            double speedSq = velocity.x * velocity.x + velocity.z * velocity.z;
            if (ahead == null && speedSq > .01) {
                double scale = 3 / Math.sqrt(speedSq);
                ahead =
                        new Vec3Point(
                                self.x + velocity.x * scale, self.y, self.z + velocity.z * scale);
            }
            // Mirroring a jump from a different takeoff point is not a plan.
            // Gaps/climbs belong to navigation, including runway preparation.
            terrainJump = ahead != null && !ctx.navigator.autoStep(self, ahead, pit);
        }
        boolean needsRoute =
                tick < recoverUntil
                        || ctx.navigator.jumpInProgress()
                        || ctx.navigator.preparingGap()
                        || self.horizontalDistanceSq(pose) > 6.25
                        || terrainJump
                        || !connected
                        || ctx.mc.thePlayer.onGround
                                && Math.abs(self.y - connection.y) > .6
                                && !ordinaryJumpRise
                                && !descending;
        if (needsRoute) routing = true;
        if (routing && !needsRoute && !beforeMovement) routing = false;
        if (routing
                && closeFight
                && equipmentReady
                && ctx.navigator.safeCombatInput(0, 0, false, false, pit)) {
            ctx.navigator.reset();
            routing = false;
            recoverUntil = Long.MIN_VALUE;
        }
        if (routing) {
            if (!beforeMovement) {
                ctx.host.guardNavigate(connection, pit, tick < recoverUntil, travelJump);
            }
            // Distant assists retain their target, but navigation owns the camera
            // until close following or actual combat reach can take over.
            boolean aim =
                    (directCombat || self.horizontalDistanceSq(pose) <= 6.25)
                            && !ctx.navigator.jumpInProgress()
                            && !ctx.navigator.preparingGap();
            float travelYaw = ctx.mc.thePlayer.rotationYaw;
            NearbySteering.Input travel = ctx.host.guardMovement();
            combat.assist(pit, tick, aim, equipmentReady, observation.sword);
            if (aim) ctx.host.guardReorient(travel, travelYaw, pit);
            return;
        }
        // Only a connected close follow may replace navigation's camera/runway.
        if (!beforeMovement) ctx.navigator.reset();
        if (!fighting && (!combat.live(tick) || combat.target() == null))
            combat.turnToward(yaw, pitch, tick);
        combat.assist(pit, tick, true, equipmentReady, observation.sword);

        GuardFollowPlan plan =
                new GuardFollowPlan(
                        goal, steeringYaw, idle, fighting, holdFight, copyingJump, descending);
        GuardSteering.Result result = steering.choose(plan, observation, pit, tick, beforeMovement);
        if (result.recover) {
            routing = true;
            recoverUntil = tick + 10;
            ctx.navigator.reset();
            if (!beforeMovement) ctx.host.guardNavigate(goal, pit, true, travelJump);
            return;
        }
        ctx.host.guardMove(result.input, result.running, result.jump, sneak);
        ctx.host.guardActivity(
                "Guarding "
                        + player.getName()
                        + " | "
                        + (frame != null
                                ? "local client keys"
                                : desktop != null ? "Windows keys" : "observed movement")
                        + (combat.pending(tick) ? " | waiting to assist" : ""));
    }
}