package dev.optimus.client;

import dev.optimus.client.guard.GuardHost;
import dev.optimus.client.learning.CombatTelemetry;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.MovementState;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.WalkSteering;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/** Applies guard requests through the same session, movement and attack owners as normal combat. */
final class GuardBridge implements GuardHost {
    private final Minecraft mc;
    private final ClientEvents session;
    private final MovementInput movement;
    private final CombatController combat;
    private final LobbyNavigator navigator;
    private final GuardNavigationAdapter queries;
    private final CombatTelemetry telemetry;

    GuardBridge(
            Minecraft mc,
            ClientEvents session,
            MovementInput movement,
            CombatController combat,
            LobbyNavigator navigator,
            GuardNavigationAdapter queries,
            CombatTelemetry telemetry) {
        this.mc = mc;
        this.session = session;
        this.movement = movement;
        this.combat = combat;
        this.navigator = navigator;
        this.queries = queries;
        this.telemetry = telemetry;
    }

    public boolean guardAllowed() {
        return session.controlAllowed();
    }

    public boolean guardArmed() {
        return session.running();
    }

    public boolean isVerifiedLobby() {
        return session.isVerifiedLobby();
    }

    public String connectedServer() {
        return session.connectedServer();
    }

    public String guardLobby() {
        return session.guardLobby();
    }

    public String guardInstance() {
        return session.guardInstance();
    }

    public int guardPingMillis() {
        return session.guardPingMillis();
    }

    public void controlOptionsChanged() {
        session.controlOptionsChanged();
    }

    public void startGuardIfNeeded() {
        session.startGuardIfNeeded();
    }

    public void notice(String text) {
        OptimusMod.notice(text);
    }

    public void guardActivity(String text) {
        navigator.activity = text;
    }

    public void guardHold() {
        movement.releaseMovement();
    }

    public void guardRoam(PitRegion pit) {
        session.roamPit(pit);
    }

    public void guardCancelRoam() {
        session.cancelRoaming();
        navigator.reset();
        movement.releaseMovement();
    }

    public NearbySteering.Input guardMovement() {
        return movement.snapshot().direction;
    }

    public void guardNavigate(Vec3Point goal, PitRegion pit, boolean precise, boolean travelJump) {
        if (!guardAllowed()) {
            guardHold();
            return;
        }
        LobbyNavigator.Step jump = !precise && travelJump ? queries.sprintJump(goal, pit) : null;
        if (jump != null) session.navigateStep(goal, pit, jump);
        else if (!precise
                && travelJump
                && !navigator.jumpInProgress()
                && !navigator.preparingGap()
                && queries.autoStep(PlayerGeometry.position(mc.thePlayer), goal, pit)) {
            // Clear ground is already checked; do not wait for A* to rediscover it.
            navigator.reset();
            session.navigateStep(
                    goal,
                    pit,
                    new LobbyNavigator.Step(
                            LobbyNavigator.Status.MOVING, goal, false, false, true, null));
        } else if (precise || !travelJump)
            session.navigateStep(goal, pit, navigator.step(goal, pit, !precise, session.tick()));
        else session.navigate(goal, pit, true);
    }

    public boolean guardTravelJump(Vec3Point goal, PitRegion pit) {
        if (goal == null || !movement.canSprint() || !guardAllowed()) return false;
        LobbyNavigator.Step jump = queries.sprintJump(goal, pit);
        if (jump == null) return false;
        navigator.reset();
        session.navigateStep(goal, pit, jump);
        return navigator.travelJumpInProgress();
    }

    public void guardReorient(NearbySteering.Input input, float previousYaw, PitRegion pit) {
        if (input == NearbySteering.Input.NONE
                || Math.abs(MathHelper.wrapAngleTo180_float(previousYaw - mc.thePlayer.rotationYaw))
                        < .01) return;
        Vec3Point self = PlayerGeometry.position(mc.thePlayer),
                direction = input.point(self, previousYaw, 1);
        float desired =
                (float) Math.toDegrees(Math.atan2(self.x - direction.x, direction.z - self.z));
        NearbySteering.Input relative = WalkSteering.input(desired, mc.thePlayer.rotationYaw);
        MovementState held = movement.snapshot();
        boolean running = held.sprint && relative.forward > 0;
        Vec3Point air =
                GroundMotion.delta(
                        mc.thePlayer.motionX,
                        mc.thePlayer.motionZ,
                        mc.thePlayer.rotationYaw,
                        relative.forward,
                        relative.side,
                        mc.thePlayer.jumpMovementFactor,
                        1);
        boolean safe =
                mc.thePlayer.onGround
                        ? queries.safeGuardInput(relative, running, held.sneak, pit)
                        : queries.safeKnockbackStep(
                                self,
                                new Vec3Point(
                                        self.x + air.x,
                                        self.y + mc.thePlayer.motionY,
                                        self.z + air.z),
                                pit);
        guardMove(safe ? relative : NearbySteering.Input.NONE, running, false, held.sneak);
    }

    public boolean guardRay(Vec3Point eye, Vec3Point hit) {
        return hit != null
                && mc.theWorld.rayTraceBlocks(
                                new Vec3(eye.x, eye.y, eye.z), new Vec3(hit.x, hit.y, hit.z))
                        == null;
    }

    public boolean guardSword(EntityPlayer player) {
        return CombatEquipment.sword(player.getHeldItem());
    }

    public boolean guardSelectWeapon(boolean sword) {
        return guardAllowed() && CombatEquipment.select(mc.thePlayer, sword);
    }

    public boolean guardEquipment(boolean sword, long tick) {
        return combat.equipmentReady(sword, tick);
    }

    public int guardAttackDelay() {
        return combat.attackDelay();
    }

    public void guardMove(
            NearbySteering.Input input, boolean running, boolean jump, boolean sneak) {
        if (!guardAllowed()) {
            guardHold();
            return;
        }
        movement.sprint(running && movement.canSprint());
        movement.move(
                input.forward > 0, input.forward < 0, input.side > 0, input.side < 0, jump, sneak);
    }

    public boolean guardStrike(EntityPlayer target, boolean sword) {
        if (!guardAllowed() || !guardEquipment(sword, session.tick())) return false;
        if (target == null) {
            mc.thePlayer.swingItem();
            return true;
        }
        return session.targetAllowed(OptimusMod.settings.hypixelPit, target)
                && combat.assistAttack(target, sword, session.tick());
    }

    public void recordCombatDecision(
            EntityPlayer target,
            String mode,
            long controlTick,
            float beforeYaw,
            float beforePitch,
            float desiredYaw,
            float desiredPitch,
            boolean equipment,
            boolean hitWindow,
            boolean pending,
            long cooldown,
            CombatClicks.Action action,
            boolean requested,
            String gate) {
        if (!guardAllowed()
                || target != null
                        && (target == mc.thePlayer
                                || !target.isEntityAlive()
                                || target.worldObj != mc.theWorld
                                || session.protectsGuardedPlayer(target)
                                || !OptimusMod.settings.hypixelPit.containsBody(
                                        PlayerGeometry.position(target), .3, 1.8))) return;
        telemetry.guardDecision(
                session.tick(),
                target,
                controlTick,
                beforeYaw,
                beforePitch,
                desiredYaw,
                desiredPitch,
                equipment,
                hitWindow,
                pending,
                cooldown,
                action,
                requested,
                gate);
    }
}