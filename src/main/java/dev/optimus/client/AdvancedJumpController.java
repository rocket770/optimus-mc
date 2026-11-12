package dev.optimus.client;

import dev.optimus.client.LobbyNavigator.Status;
import dev.optimus.client.LobbyNavigator.Step;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.NearbySteering;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

/** Setup, live trajectory validation and execution for advanced saved jump connections. */
final class AdvancedJumpController {
    private final Minecraft mc;
    private String failure = "";
    private String activity = "";
    private RouteHints.Jump hintJump;
    private List<HintJump.Frame> hintFrames;
    private int hintStage;
    private float hintYaw;
    private long hintSetupAt, hintFlightAt;

    AdvancedJumpController(Minecraft mc) {
        this.mc = mc;
    }

    void begin(RouteHints.Jump jump, long tick) {
        hintJump = jump;
        hintSetupAt = tick;
        hintStage = 0;
        hintFrames = null;
    }

    void reset() {
        hintJump = null;
        hintFrames = null;
        hintStage = 0;
    }

    boolean active() {
        return hintJump != null;
    }

    boolean inFlight() {
        return hintFrames != null;
    }

    boolean preparing() {
        return hintJump != null && hintFrames == null;
    }

    boolean groundedSetup(long tick) {
        return hintFrames == null
                || mc.thePlayer.onGround && hintJump.kind == 5 && tick - hintFlightAt < 7;
    }

    String failure() {
        return failure;
    }

    String activity() {
        return activity;
    }

    void appendArc(List<Vec3Point> arc) {
        if (hintJump != null) arc.add(hintJump.start);
        for (HintJump.Frame frame : hintFrames) arc.add(frame.point);
    }

    private Vec3Point self() {
        return PlayerGeometry.position(mc.thePlayer);
    }

    private Step failed(String reason) {
        failure = reason;
        return new Step(Status.FAILED, null, false);
    }

    private boolean normalHintPhysics() {
        BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1, mc.thePlayer.posZ);
        double speed = mc.thePlayer.getAIMoveSpeed() / (mc.thePlayer.isSprinting() ? 1.3 : 1);
        return mc.theWorld.isBlockLoaded(below)
                && Math.abs(mc.theWorld.getBlockState(below).getBlock().slipperiness - .6) < .0001
                && Math.abs(speed - .1) < .0001
                && !mc.thePlayer.isUsingItem()
                && !mc.thePlayer.isPotionActive(Potion.jump)
                && !mc.thePlayer.isPotionActive(Potion.blindness)
                && mc.thePlayer.hurtTime == 0;
    }

    Step step(CollisionGeometry live, PitRegion area, long tick) {
        activity = null;
        Vec3Point current = self();
        if (!normalHintPhysics()) return failed("Advanced hint physics changed");
        if (hintJump.kind == SpecificPitJump.KIND) return specificPitJumpStep(live, area, tick);
        if (hintFrames != null) {
            int elapsed = (int) (tick - hintFlightAt);
            if (elapsed < 0
                    || elapsed > hintFrames.size()
                    || elapsed > 0
                            && (current.horizontalDistanceSq(hintFrames.get(elapsed - 1).point)
                                            > .04 * .04
                                    || Math.abs(current.y - hintFrames.get(elapsed - 1).point.y)
                                            > .03
                                    || mc.thePlayer.onGround
                                            != hintFrames.get(elapsed - 1).grounded)
                    || Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - hintYaw))
                            > .6)
                return failed("Advanced hint was interrupted; choosing another route");
            for (int i = elapsed; i < hintFrames.size(); i++) {
                HintJump.Frame frame = hintFrames.get(i);
                Vec3Point before = i == elapsed ? current : hintFrames.get(i - 1).point;
                Vec3Point projected = live.jumpMove(before, frame.delta, area);
                if (projected == null
                        || projected.horizontalDistanceSq(frame.point) > .04 * .04
                        || Math.abs(projected.y - frame.point.y) > .03)
                    return failed("Advanced hint landing or corridor changed");
            }
            if (elapsed == hintFrames.size()) {
                if (!mc.thePlayer.onGround || !live.autoStepClear(current, hintJump.to, area))
                    return failed("Advanced hint missed landing");
                hintJump = null;
                hintFrames = null;
                hintStage = 0;
                return null;
            }
            activity =
                    "Executing checked "
                            + (hintJump.kind == 4 ? "momentum chain" : "precision jump");
            return new Step(new HintJump.Control(hintYaw, true, hintFrames.get(elapsed).jump));
        }
        if (!mc.thePlayer.onGround || tick - hintSetupAt > 70)
            return failed("Advanced hint setup unavailable");
        Vec3Point setup = HintJump.restSetup(hintJump);
        if (current.horizontalDistanceSq(setup) < .07 * .07
                && Math.abs(
                                MathHelper.wrapAngleTo180_float(
                                        hintJump.yaw - mc.thePlayer.rotationYaw))
                        < .4) {
            List<HintJump.Frame> early =
                    HintJump.project(
                            live,
                            area,
                            hintJump,
                            current,
                            mc.thePlayer.rotationYaw,
                            mc.thePlayer.motionX,
                            mc.thePlayer.motionY,
                            mc.thePlayer.motionZ);
            if (!early.isEmpty()) return launchHint(early, tick);
        }
        if (hintStage == 0) {
            double radius = .025, maximumSpeed = .005;
            if (current.horizontalDistanceSq(setup) > radius * radius
                    || Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ) > maximumSpeed) {
                if (!live.autoStepClear(current, setup, area))
                    return failed("Advanced hint setup is blocked");
                activity =
                        "Positioning for "
                                + (hintJump.kind == 4 ? "momentum chain" : "precision jump");
                if (current.horizontalDistanceSq(setup) < .4 * .4) {
                    if (Math.abs(
                                    MathHelper.wrapAngleTo180_float(
                                            hintJump.yaw - mc.thePlayer.rotationYaw))
                            > .4) return new Step(new HintJump.Control(hintJump.yaw, false, false));
                    return new Step(
                            new HintJump.Control(
                                    mc.thePlayer.rotationYaw,
                                    fineHintInput(live, setup, area),
                                    false,
                                    true,
                                    false));
                }
                return new Step(Status.MOVING, setup, false);
            }
            hintStage = 1;
        }
        if (Math.abs(MathHelper.wrapAngleTo180_float(hintJump.yaw - mc.thePlayer.rotationYaw))
                > .4) {
            activity = "Aligning advanced hint";
            return new Step(new HintJump.Control(hintJump.yaw, false, false));
        }
        List<HintJump.Frame> frames =
                HintJump.project(
                        live,
                        area,
                        hintJump,
                        current,
                        mc.thePlayer.rotationYaw,
                        mc.thePlayer.motionX,
                        mc.thePlayer.motionY,
                        mc.thePlayer.motionZ);
        if (!frames.isEmpty()) {
            return launchHint(frames, tick);
        }
        return failed("Advanced hint no longer has a checked launch");
    }

    private Step specificPitJumpStep(CollisionGeometry live, PitRegion area, long tick) {
        Vec3Point current = self();
        if (hintFrames != null) {
            int elapsed = (int) (tick - hintFlightAt);
            if (elapsed == SpecificPitJump.LENGTH) {
                if (!mc.thePlayer.onGround
                        || !live.autoStepClear(current, SpecificPitJump.TO, area))
                    return failed("Corner momentum landing changed");
                hintJump = null;
                hintFrames = null;
                hintStage = 0;
                return null;
            }
            List<HintJump.Frame> remaining =
                    SpecificPitJump.project(
                            live,
                            area,
                            current,
                            mc.thePlayer.motionX,
                            mc.thePlayer.motionY,
                            mc.thePlayer.motionZ,
                            mc.thePlayer.onGround,
                            elapsed);
            if (remaining.isEmpty())
                return failed("Corner momentum state changed; choosing another route");
            activity = "Executing checked corner momentum jump";
            return new Step(remaining.get(0).control);
        }
        if (!mc.thePlayer.onGround || Math.abs(current.y - 68) > .01 || tick - hintSetupAt > 140)
            return failed("Corner momentum lineup unavailable");
        Vec3Point setup = SpecificPitJump.SETUP;
        if (current.horizontalDistanceSq(setup) > .35 * .35) {
            Vec3Point approach = new Vec3Point(setup.x, setup.y, -118.6);
            if (!live.autoStepClear(current, approach, area))
                return failed("Corner momentum approach changed");
            activity = "Approaching corner momentum lineup";
            return new Step(Status.MOVING, approach, false);
        }
        if (!live.autoStepClear(current, setup, area))
            return failed("Corner momentum wall lineup blocked");
        if (Math.abs(current.x - setup.x) < .0001
                && Math.abs(current.z - setup.z) < .00002
                && Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ) < .005) {
            if (!mc.thePlayer.isCollidedHorizontally) {
                List<HintJump.Frame> checked =
                        SpecificPitJump.project(
                                live,
                                area,
                                current,
                                mc.thePlayer.motionX,
                                mc.thePlayer.motionY,
                                mc.thePlayer.motionZ,
                                true,
                                0);
                if (!checked.isEmpty()) {
                    hintFrames = checked;
                    hintFlightAt = tick;
                    activity = "Launching checked corner momentum jump";
                    return new Step(checked.get(0).control);
                }
            }
            activity = "Settling corner momentum lineup";
            return new Step(
                    new HintJump.Control(90, NearbySteering.Input.NONE, false, false, false, true));
        }
        activity = "Lining up corner momentum jump against wall";
        return new Step(SpecificPitJump.align(current, mc.thePlayer.motionX));
    }

    private Step launchHint(List<HintJump.Frame> frames, long tick) {
        hintFrames = frames;
        hintFlightAt = tick;
        hintYaw = mc.thePlayer.rotationYaw;
        activity =
                "Launching checked " + (hintJump.kind == 4 ? "momentum chain" : "precision jump");
        return new Step(new HintJump.Control(hintYaw, true, frames.get(0).jump));
    }

    private NearbySteering.Input fineHintInput(
            CollisionGeometry live, Vec3Point goal, PitRegion area) {
        Vec3Point current = self();
        float friction = .6f * .91f;
        double acceleration = .1f * (.16277136f / (friction * friction * friction));
        NearbySteering.Input best = NearbySteering.Input.NONE;
        double score = Double.POSITIVE_INFINITY;
        for (NearbySteering.Input input : NearbySteering.Input.values()) {
            Vec3Point delta =
                    GroundMotion.delta(
                            mc.thePlayer.motionX,
                            mc.thePlayer.motionZ,
                            mc.thePlayer.rotationYaw,
                            input.forward,
                            input.side,
                            acceleration,
                            .3);
            Vec3Point next =
                    live.walkEnd(
                            current,
                            new Vec3Point(current.x + delta.x, current.y, current.z + delta.z),
                            area);
            if (next == null) continue;
            Vec3Point coast =
                    live.walkEnd(
                            next,
                            new Vec3Point(
                                    next.x + delta.x * friction / (1 - friction),
                                    next.y,
                                    next.z + delta.z * friction / (1 - friction)),
                            area);
            if (coast == null) continue;
            double value = next.horizontalDistanceSq(goal) + coast.horizontalDistanceSq(goal) * .08;
            if (value < score) {
                score = value;
                best = input;
            }
        }
        return best;
    }
}