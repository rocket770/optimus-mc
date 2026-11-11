package dev.optimus.client;

import dev.optimus.client.guard.GuardNavigation;
import dev.optimus.core.geometry.*;
import dev.optimus.core.navigation.*;
import net.minecraft.client.Minecraft;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

/** Cached native geometry for guard following; route execution stays with NavigationController. */
final class GuardNavigationAdapter implements GuardNavigation {
    private final Minecraft mc;
    private final LobbyNavigator route;
    private CollisionGeometry queries;
    private int depth;

    GuardNavigationAdapter(Minecraft mc, LobbyNavigator route) {
        this.mc = mc;
        this.route = route;
    }

    private Vec3Point self() {
        return PlayerGeometry.position(mc.thePlayer);
    }

    private CollisionGeometry guardGeometry() {
        return queries == null ? route.collisionSnapshot() : queries;
    }

    public void beginGuardQueries() {
        if (depth++ == 0) queries = route.collisionSnapshot();
    }

    public void endGuardQueries() {
        if (--depth == 0) queries = null;
    }

    public void reset() {
        route.reset();
    }

    public boolean jumpInProgress() {
        return route.jumpInProgress();
    }

    public boolean preparingGap() {
        return route.preparingGap();
    }

    public boolean safeCombatInput(
            int forward, int side, boolean sprint, boolean sneak, PitRegion pit) {
        double speed =
                mc.thePlayer.getAIMoveSpeed()
                        / (mc.thePlayer.isSprinting() ? 1.3 : 1)
                        * (sprint ? 1.3 : 1);
        return GroundMotion.safe(
                guardGeometry(),
                pit,
                self(),
                mc.thePlayer.motionX,
                mc.thePlayer.motionZ,
                mc.thePlayer.rotationYaw,
                forward,
                side,
                speed,
                mc.thePlayer.isUsingItem() ? .2 : 1,
                sneak,
                p -> {
                    BlockPos below = new BlockPos(p.x, p.y - 1, p.z);
                    return mc.theWorld.isBlockLoaded(below)
                            ? mc.theWorld.getBlockState(below).getBlock().slipperiness * .91
                            : Double.NaN;
                });
    }
    /** Guard may walk down: check native coasting/falling to a real landing. */
    public boolean safeGuardInput(
            NearbySteering.Input input, boolean sprint, boolean sneak, PitRegion pit) {
        if (safeCombatInput(input.forward, input.side, sprint, sneak, pit)) return true;
        if (sneak) return false;
        CollisionGeometry live = guardGeometry();
        Vec3Point current = self();
        double friction =
                mc.theWorld
                                .getBlockState(new BlockPos(current.x, current.y - 1, current.z))
                                .getBlock()
                                .slipperiness
                        * .91;
        double speed =
                mc.thePlayer.getAIMoveSpeed()
                        / (mc.thePlayer.isSprinting() ? 1.3 : 1)
                        * (sprint ? 1.3 : 1);
        Vec3Point delta =
                GroundMotion.delta(
                        mc.thePlayer.motionX,
                        mc.thePlayer.motionZ,
                        mc.thePlayer.rotationYaw,
                        input.forward,
                        input.side,
                        speed * .16277136 / (friction * friction * friction),
                        mc.thePlayer.isUsingItem() ? .2 : 1);
        double x = delta.x, z = delta.z, y = 0;
        boolean falling = false;
        for (int tick = 0; tick < 64; tick++) {
            Vec3Point next =
                    falling
                            ? null
                            : live.walkEnd(
                                    current,
                                    new Vec3Point(current.x + x, current.y, current.z + z),
                                    pit);
            if (next == null) {
                falling = true;
                next = live.jumpMove(current, new Vec3Point(x, y, z), pit);
                if (next == null
                        || Math.abs(next.x - current.x - x) > 1e-6
                        || Math.abs(next.z - current.z - z) > 1e-6) return false;
                if (y < 0 && next.y > current.y + y + 1e-6) return live.standable(pit, next);
            }
            current = next;
            if (falling) {
                y = (y - .08) * .98;
                friction = .91;
            } else
                friction =
                        mc.theWorld
                                        .getBlockState(
                                                new BlockPos(current.x, current.y - 1, current.z))
                                        .getBlock()
                                        .slipperiness
                                * .91;
            x *= friction;
            z *= friction;
            if (Math.abs(x) < .005) x = 0;
            if (Math.abs(z) < .005) z = 0;
            if (!falling && x == 0 && z == 0) return true;
        }
        return false;
    }

    public Vec3Point guardDestination(
            Vec3Point rear, Vec3Point leader, boolean airborne, PitRegion pit) {
        CollisionGeometry live = guardGeometry();
        Vec3Point stand = live.surface(rear, pit);
        if (stand != null) return stand;
        if (airborne && live.free(CollisionGeometry.body(rear))) return rear;
        Vec3Point walked = live.walkEnd(self(), rear, pit);
        if (walked != null && Math.abs(walked.y - rear.y) <= .6) return walked;
        Vec3Point below = live.landingBelow(rear, pit);
        if (below != null && rear.y - below.y <= 3) return below;
        Vec3Point feet = live.surface(leader, pit);
        return feet == null ? leader : feet;
    }

    public Vec3Point guardPrediction(Vec3Point from, Vec3Point predicted, PitRegion pit) {
        Vec3Point walked = guardGeometry().walkEnd(from, predicted, pit);
        if (walked != null) return walked;
        return guardPredictionClear(from, predicted, pit) ? predicted : null;
    }

    public boolean guardDrop(Vec3Point from, Vec3Point to, PitRegion pit) {
        return guardGeometry().walkOffClear(from, to, pit);
    }

    public boolean safeGuardJump(NearbySteering.Input input, boolean sprint, PitRegion pit) {
        return guardJumpLanding(input, sprint, pit) != null;
    }

    public boolean travelJumpInProgress() {
        return route.travelJumpInProgress();
    }

    LobbyNavigator.Step sprintJump(Vec3Point goal, PitRegion pit) {
        Vec3Point from = self();
        if (!OptimusMod.settings.parkourEnabled
                || jumpInProgress()
                || preparingGap()
                || !mc.thePlayer.isSprinting()
                || mc.thePlayer.hurtTime > 0
                || mc.thePlayer.isCollidedHorizontally
                || !mc.thePlayer.onGround
                || mc.thePlayer.isPotionActive(Potion.jump)
                || mc.thePlayer.isPotionActive(Potion.blindness)
                || from.horizontalDistanceSq(goal) < 4.5 * 4.5
                || Math.abs(from.y - goal.y) > .1) return null;
        float desired = (float) Math.toDegrees(Math.atan2(from.x - goal.x, goal.z - from.z));
        if (Math.abs(MathHelper.wrapAngleTo180_float(desired - mc.thePlayer.rotationYaw)) > 5)
            return null;
        double distance = Math.sqrt(from.horizontalDistanceSq(goal)),
                dx = (goal.x - from.x) / distance,
                dz = (goal.z - from.z) / distance;
        double speed = mc.thePlayer.motionX * dx + mc.thePlayer.motionZ * dz;
        if (Math.abs(mc.thePlayer.motionX * dz - mc.thePlayer.motionZ * dx) > .025) return null;
        double friction =
                mc.theWorld
                                .getBlockState(new BlockPos(from.x, from.y - 1, from.z))
                                .getBlock()
                                .slipperiness
                        * .91;
        SprintJump.Plan plan =
                SprintJump.guardPlan(
                        guardGeometry(),
                        pit,
                        from,
                        goal,
                        speed,
                        mc.thePlayer.getAIMoveSpeed()
                                * .16277136
                                / (friction * friction * friction)
                                * .98,
                        mc.thePlayer.jumpMovementFactor * .98,
                        friction);
        return plan == null
                ? null
                : new LobbyNavigator.Step(
                        LobbyNavigator.Status.MOVING,
                        new Vec3Point(from.x + dx * 3, from.y, from.z + dz * 3),
                        true,
                        false,
                        true,
                        plan);
    }

    private Vec3Point guardJumpLanding(NearbySteering.Input input, boolean sprint, PitRegion pit) {
        if (!mc.thePlayer.onGround
                || mc.thePlayer.isInWater()
                || mc.thePlayer.isInLava()
                || mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.jump)) return null;
        CollisionGeometry live = guardGeometry();
        Vec3Point current = self();
        double angle = Math.toRadians(mc.thePlayer.rotationYaw);
        double friction =
                mc.theWorld
                                .getBlockState(new BlockPos(current.x, current.y - 1, current.z))
                                .getBlock()
                                .slipperiness
                        * .91;
        double speed =
                mc.thePlayer.getAIMoveSpeed()
                        / (mc.thePlayer.isSprinting() ? 1.3 : 1)
                        * (sprint ? 1.3 : 1);
        Vec3Point delta =
                GroundMotion.delta(
                        mc.thePlayer.motionX,
                        mc.thePlayer.motionZ,
                        mc.thePlayer.rotationYaw,
                        input.forward,
                        input.side,
                        speed * .16277136 / (friction * friction * friction),
                        1);
        double x = delta.x - (sprint ? Math.sin(angle) * .2 : 0),
                z = delta.z + (sprint ? Math.cos(angle) * .2 : 0),
                y = .42;
        for (int tick = 0; tick < 24; tick++) {
            Vec3Point next = live.jumpMove(current, new Vec3Point(x, y, z), pit);
            if (next == null
                    || Math.abs(next.x - current.x - x) > 1e-6
                    || Math.abs(next.z - current.z - z) > 1e-6) return null;
            if (y > 0 && next.y < current.y + y - 1e-6) return null;
            if (y < 0 && next.y > current.y + y + 1e-6)
                return live.standable(pit, next) ? next : null;
            current = next;
            y = (y - .08) * .98;
            Vec3Point air =
                    GroundMotion.delta(
                            x * .91,
                            z * .91,
                            mc.thePlayer.rotationYaw,
                            input.forward,
                            input.side,
                            sprint ? .026 : .02,
                            1);
            x = air.x;
            z = air.z;
        }
        return null;
    }

    public boolean guardPredictionClear(Vec3Point from, Vec3Point to, PitRegion area) {
        return area.containsBody(to, .3, 1.8)
                && guardGeometry()
                        .free(CollisionGeometry.body(from).union(CollisionGeometry.body(to)));
    }

    public boolean autoStep(Vec3Point from, Vec3Point to, PitRegion area) {
        return guardGeometry().autoStepClear(from, to, area);
    }

    public Vec3Point pursuitDestination(Vec3Point target, boolean airborne, PitRegion pit) {
        return guardGeometry().pursuitDestination(target, airborne, pit);
    }

    public boolean safeKnockbackStep(Vec3Point from, Vec3Point to, PitRegion pit) {
        CollisionGeometry live = guardGeometry();
        Vec3Point next =
                live.jumpMove(
                        from, new Vec3Point(to.x - from.x, to.y - from.y, to.z - from.z), pit);
        return next != null && live.knockbackControlClear(from, next, pit);
    }
}