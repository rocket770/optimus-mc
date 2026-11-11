package dev.optimus.client;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.WalkSteering;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;

/** Executes checked navigation steps, retaining combat aim only when the route permits it. */
final class NavigationController {
    private final Minecraft mc;
    private final LobbyNavigator navigator;
    private final MovementInput movement;
    private final ClientEvents session;
    private final WalkSteering walkSteering = new WalkSteering();
    private String lastIssue = "";

    NavigationController(
            Minecraft mc, LobbyNavigator navigator, MovementInput input, ClientEvents session) {
        this.mc = mc;
        this.navigator = navigator;
        this.movement = input;
        this.session = session;
    }

    void resetSteering() {
        walkSteering.reset();
    }

    void clearIssue() {
        lastIssue = "";
    }

    LobbyNavigator.Status navigate(
            Vec3Point goal, PitRegion area, boolean target, CombatController combat) {
        if (goal == null || !session.navigationAllowed(area)) {
            session.stop(
                    goal == null
                            ? "Navigation has no destination"
                            : session.navigationFailure(area));
            return LobbyNavigator.Status.FAILED;
        }
        LobbyNavigator.Step step =
                combat.recentCombat(session.tick())
                        ? null
                        : navigator.sprintJump(goal, area, target);
        if (step == null) step = navigator.step(goal, area, target, session.tick());
        return navigateStep(goal, area, step, combat);
    }

    LobbyNavigator.Status navigateStep(
            Vec3Point goal, PitRegion area, LobbyNavigator.Step step, CombatController combat) {
        combat.navigating();
        if (goal == null || !session.navigationAllowed(area)) {
            session.stop(
                    goal == null
                            ? "Navigation has no destination"
                            : session.navigationFailure(area));
            return LobbyNavigator.Status.FAILED;
        }
        if (step.status == LobbyNavigator.Status.FAILED) {
            movement.releaseMovement();
            if (!lastIssue.equals(navigator.failure)) {
                lastIssue = navigator.failure;
                session.traceEvent("navigation retry: " + navigator.failure);
            }
            return LobbyNavigator.Status.WAITING;
        }
        if (step.status != LobbyNavigator.Status.MOVING) {
            navigator.attemptedMovement(false, session.tick());
            movement.releaseMovement();
            return step.status;
        }
        if (!session.navigationAllowed(area)) {
            session.stop(session.navigationFailure(area));
            return LobbyNavigator.Status.FAILED;
        }
        if (step.hint != null) {
            HintJump.Control input = step.hint;
            float error = MathHelper.wrapAngleTo180_float(input.yaw - mc.thePlayer.rotationYaw);
            if (input.exactYaw) mc.thePlayer.rotationYaw = input.yaw;
            else
                mc.thePlayer.rotationYaw +=
                        Math.abs(error) < .8 ? error * .5f : walkSteering.turn(error);
            if (input.sprint
                    && (!movement.canSprint()
                            || Math.abs(
                                            MathHelper.wrapAngleTo180_float(
                                                    input.yaw - mc.thePlayer.rotationYaw))
                                    > .5)) {
                navigator.cancelHint("Advanced hint input changed");
                movement.releaseMovement();
                return LobbyNavigator.Status.WAITING;
            }
            movement.sprint(input.sprint);
            movement.move(
                    input.input.forward > 0,
                    input.input.forward < 0,
                    input.input.side > 0,
                    input.input.side < 0,
                    input.jump,
                    input.sneak);
            navigator.attemptedMovement(input.input != NearbySteering.Input.NONE, session.tick());
            return step.status;
        }
        if (step.input != null) {
            movement.sprint(false);
            movement.move(
                    step.input.forward > 0,
                    step.input.forward < 0,
                    step.input.side > 0,
                    step.input.side < 0,
                    false);
            navigator.attemptedMovement(step.input != NearbySteering.Input.NONE, session.tick());
            return step.status;
        }
        boolean precise = navigator.precise(step, area);
        boolean sprintHeading = navigator.sprintHeading(step);
        boolean normalClimb = navigator.normalClimb(step);
        double dx = step.waypoint.x - mc.thePlayer.posX, dz = step.waypoint.z - mc.thePlayer.posZ;
        boolean relative =
                step.alignOnly
                        || step.jump
                        || step.brake
                        || navigator.jumpInProgress()
                        || navigator.preparingGap()
                        || dx * dx + dz * dz <= 1.3 * 1.3 && (!step.sprint || precise);
        boolean correcting =
                relative
                        && !step.alignOnly
                        && !step.jump
                        && !step.brake
                        && !navigator.jumpInProgress()
                        && !navigator.preparingGap()
                        && mc.thePlayer.onGround
                        && Math.abs(step.waypoint.y - mc.thePlayer.posY) <= .6;
        if (relative && (!precise || normalClimb)) combat.aimWhileWalking();
        float desired = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (sprintHeading || !relative || precise && !normalClimb) {
            float correction =
                    sprintHeading || !relative
                            ? MathHelper.wrapAngleTo180_float(desired - mc.thePlayer.rotationYaw)
                            : WalkSteering.alignment(desired, mc.thePlayer.rotationYaw);
            // Finish a gap's sub-degree alignment before its run-up. Leaving
            // the ordinary turn deadzone here slowly drifts off narrow runways.
            mc.thePlayer.rotationYaw +=
                    step.alignOnly && sprintHeading && Math.abs(correction) < .8
                            ? correction
                            : walkSteering.turn(correction);
        }
        if (step.alignOnly) {
            // Release inherited sprint or make only the small input-angle correction.
            movement.sprint(false);
            movement.releaseMovement();
            navigator.attemptedMovement(false, session.tick());
            return LobbyNavigator.Status.MOVING;
        }
        boolean aligned =
                sprintHeading
                        ? Math.abs(
                                        MathHelper.wrapAngleTo180_float(
                                                desired - mc.thePlayer.rotationYaw))
                                < (precise ? 10 : 25)
                        : normalClimb
                                || !step.jump && !navigator.jumpInProgress()
                                || Math.abs(
                                                WalkSteering.alignment(
                                                        desired, mc.thePlayer.rotationYaw))
                                        < 2.5;
        NearbySteering.Input input =
                aligned
                        ? WalkSteering.input(
                                step.brake ? desired + 180 : desired, mc.thePlayer.rotationYaw)
                        : NearbySteering.Input.NONE;
        if (sprintHeading)
            input =
                    aligned
                            ? (step.brake
                                    ? NearbySteering.Input.BACK
                                    : NearbySteering.Input.FORWARD)
                            : NearbySteering.Input.NONE;
        if (correcting) input = navigator.correctionInput(step.waypoint, area);
        boolean trying =
                !step.brake
                        && (input != NearbySteering.Input.NONE
                                || correcting && dx * dx + dz * dz > .09);
        if (input != NearbySteering.Input.NONE
                && !precise
                && !correcting
                && mc.thePlayer.onGround) {
            double distance = step.sprint && input.forward > 0 ? .4 : .28;
            if (!navigator.safeWalkingInput(
                    input.point(
                            PlayerGeometry.position(mc.thePlayer),
                            mc.thePlayer.rotationYaw,
                            distance),
                    area)) {
                // A narrow corner may need a small correction to the nearest WASD
                // axis, but ordinary walking never turns to face the whole route.
                mc.thePlayer.rotationYaw +=
                        walkSteering.turn(
                                WalkSteering.alignment(desired, mc.thePlayer.rotationYaw));
                input = WalkSteering.input(desired, mc.thePlayer.rotationYaw);
                if (!navigator.safeWalkingInput(
                        input.point(
                                PlayerGeometry.position(mc.thePlayer),
                                mc.thePlayer.rotationYaw,
                                distance),
                        area)) input = NearbySteering.Input.NONE;
            }
        }
        if (!session.navigationAllowed(area)) {
            session.stop(session.navigationFailure(area));
            return LobbyNavigator.Status.FAILED;
        }
        boolean sprint =
                input.forward > 0
                        && step.sprint
                        && !correcting
                        && !step.brake
                        && movement.canSprint();
        movement.sprint(sprint);
        boolean jump =
                input != NearbySteering.Input.NONE
                        && step.jump
                        && (mc.thePlayer.onGround || navigator.holdGapJump())
                        && !step.brake;
        if (step.leap != null && !sprint) jump = false;
        if (step.gapLeap != null && !sprint) {
            movement.releaseMovement();
            return LobbyNavigator.Status.WAITING;
        }
        movement.move(input.forward > 0, input.forward < 0, input.side > 0, input.side < 0, jump);
        navigator.commit(step, jump, session.tick());
        navigator.attemptedMovement(trying, session.tick());
        return step.status;
    }
}