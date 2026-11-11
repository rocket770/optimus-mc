package dev.optimus.client;

import dev.optimus.client.learning.CombatTelemetry;
import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatFocus;
import dev.optimus.core.combat.CombatHop;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatSpacing;
import dev.optimus.core.combat.CombatStrafe;
import dev.optimus.core.combat.CombatTap;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.combat.ComboEscape;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.ml.MovementAdvisor;
import dev.optimus.core.ml.MovementFeatures;
import dev.optimus.core.navigation.MotionProgress;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.safety.ControlPolicy;
import java.io.IOException;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/** Target selection, aim, combat movement and attacks; session safety remains with ClientEvents. */
final class CombatController {
    private final Minecraft mc;
    private final LobbyNavigator navigator;
    private final MovementInput movement;
    private final ClientEvents session;
    private final MovementAdvisor advisor;
    private final CombatTelemetry telemetry;
    private final Random random = new Random();
    private final CombatTurn combatTurn = new CombatTurn();
    private final CombatSpacing combatSpacing = new CombatSpacing();
    private final CombatStrafe combatStrafe = new CombatStrafe();
    private final CombatClicks combatClicks = new CombatClicks();
    private final CombatTap combatTap = new CombatTap();
    private final ComboEscape comboEscape = new ComboEscape();
    private final CombatHop combatHop = new CombatHop();
    private final CombatEquipment combatEquipment = new CombatEquipment();
    private final MotionProgress pursuitProgress = new MotionProgress();
    private final NearbySteering nearbySteering = new NearbySteering();
    private final CombatFocus<EntityPlayer> combatFocus = new CombatFocus<>();
    private EntityPlayer turningTarget;
    private long detourUntil;
    private CombatTap.Input lastTap = CombatTap.Input.NONE;
    private String combatDetail = "";
    private int strafeSign = 1;
    private long nextStrafeChange;

    CombatController(
            Minecraft mc,
            LobbyNavigator navigator,
            MovementInput input,
            ClientEvents session,
            MovementAdvisor advisor,
            CombatTelemetry telemetry) {
        this.mc = mc;
        this.navigator = navigator;
        this.movement = input;
        this.session = session;
        this.advisor = advisor;
        this.telemetry = telemetry;
    }

    void release() {
        combatTurn.reset();
        turningTarget = null;
        combatSpacing.reset();
        combatStrafe.reset();
        combatDetail = "";
        combatTap.reset();
        pursuitProgress.reset();
        detourUntil = 0;
        lastTap = CombatTap.Input.NONE;
        comboEscape.reset();
        combatHop.reset();
        combatEquipment.reset();
        nearbySteering.reset();
    }

    void targetsChanged() {
        combatFocus.targetsChanged();
        combatTurn.reset();
        turningTarget = null;
        combatSpacing.reset();
        combatDetail = "";
        comboEscape.reset();
        combatHop.reset();
    }

    CombatClicks clicks() {
        return combatClicks;
    }

    boolean equipmentReady(boolean sword, long tick) {
        return combatEquipment.ready(mc.thePlayer, tick, sword);
    }

    int attackDelay() {
        return OptimusMod.settings.combatProfile.attackDelay(random);
    }

    void begin(long seed, long tick) {
        reconfigure(seed, tick);
        combatClicks.defer(tick, 6);
    }

    void reconfigure(long seed, long tick) {
        release();
        random.setSeed(seed);
        combatTap.seed(seed ^ 0x57415441504cL);
        comboEscape.seed(seed ^ 0x434f4d424fL);
        combatHop.seed(seed ^ 0x484f50545259L);
        combatTurn.seed(seed ^ 0x5455524eL);
        nextStrafeChange = tick;
        strafeSign = 1;
    }

    void resetFocus() {
        combatFocus.reset();
    }

    void resetEquipment() {
        combatEquipment.reset();
    }

    void deferClicks(long tick) {
        combatClicks.defer(tick, 6);
    }

    boolean recentCombat(long tick) {
        return combatFocus.recentCombat(tick);
    }

    String detail() {
        return combatDetail;
    }

    void navigating() {
        combatSpacing.reset();
        combatTap.cancel();
        lastTap = CombatTap.Input.NONE;
    }

    void roaming() {
        combatTurn.reset();
        turningTarget = null;
        combatSpacing.reset();
        combatDetail = "";
        combatTap.reset();
        pursuitProgress.reset();
        lastTap = CombatTap.Input.NONE;
        comboEscape.reset();
        nearbySteering.reset();
    }

    void aimWhileWalking() {
        EntityPlayer target = turningTarget;
        PitRegion pit = OptimusMod.settings.hypixelPit;
        if (target == null || !session.controlAllowed() || !session.targetAllowed(pit, target))
            return;
        Vec3Point self = PlayerGeometry.position(mc.thePlayer),
                eye = new Vec3Point(self.x, self.y + mc.thePlayer.getEyeHeight(), self.z);
        CollisionGeometry.Box box = PlayerGeometry.hitbox(target);
        Vec3Point aim =
                CombatAim.clearPoint(
                        eye, box, CombatClicks.SWING_REACH, hit -> clearCombatRay(eye, hit));
        if (aim == null) aim = CombatAim.point(eye, box);
        float desired = (float) Math.toDegrees(Math.atan2(self.x - aim.x, aim.z - self.z));
        mc.thePlayer.rotationYaw +=
                combatTurn.yaw(
                        desired - mc.thePlayer.rotationYaw,
                        OptimusMod.settings.combatProfile,
                        combatFocus.reacting(session.tick()));
    }

    private EntityPlayer focusedOpponent(PitRegion pit, Vec3Point self) {
        return combatFocus.select(
                mc.theWorld.playerEntities,
                other -> session.targetAllowed(pit, other),
                other -> self.horizontalDistanceSq(PlayerGeometry.position(other)),
                OptimusMod.settings.targetAll,
                session.tick());
    }

    void fight(PitRegion pit, Vec3Point self) {
        if (!session.controlAllowed()) {
            session.stop("Lobby/pit safety failed");
            return;
        }
        if (CombatEquipment.select(mc.thePlayer, OptimusMod.settings.swordEnabled))
            mc.playerController.updateController();
        CombatProfile profile = OptimusMod.settings.combatProfile;
        boolean justHit =
                combatFocus.observeDamage(
                        mc.thePlayer.getHealth(), mc.thePlayer.hurtTime, session.tick());
        if (justHit) combatHop.cancel();
        boolean reacting =
                combatFocus.reacting(session.tick())
                        || profile == CombatProfile.BALANCED
                                && combatFocus.airborneResponse(
                                        session.tick(), mc.thePlayer.onGround);
        if (justHit
                && profile == CombatProfile.BALANCED
                && (navigator.jumpInProgress() || navigator.preparingGap())) {
            // Knockback has invalidated the committed launch/arc. Release its inputs;
            // ordinary velocity stays untouched and checked defensive air control takes over.
            navigator.reset();
            movement.releaseMovement();
            combatTap.cancel();
            session.traceEvent("damage: interrupted jump; counterattack immediately");
        } else if (justHit && !navigator.jumpInProgress()) {
            if (profile != CombatProfile.BALANCED) navigator.reset();
            session.traceEvent("damage: immediate tracking response");
        }
        if (!session.running()) return;
        EntityPlayer target = focusedOpponent(pit, self);
        telemetry.begin(session.tick(), target);
        Vec3Point pursuitGoal =
                target == null
                        ? null
                        : navigator.pursuitDestination(
                                PlayerGeometry.position(target), !target.onGround, pit);
        boolean climbNeeded = pursuitGoal != null && pursuitGoal.y - self.y > .6;
        boolean edgeLaunch = navigator.edgeLaunchPending(session.tick());
        Vec3Point eye = new Vec3Point(self.x, self.y + mc.thePlayer.getEyeHeight(), self.z);
        CollisionGeometry.Box hitbox = null;
        Vec3Point exposedAim = null;
        boolean meleePriority = false;
        if (target != null) {
            hitbox = PlayerGeometry.hitbox(target);
            exposedAim =
                    CombatAim.clearPoint(
                            eye,
                            hitbox,
                            CombatClicks.ATTACK_REACH,
                            hit -> clearCombatRay(eye, hit));
            meleePriority = exposedAim != null;
        }
        // Reachable melee owns grounded aim and movement immediately, including
        // raised targets and a jump prepared but not yet airborne. A committed
        // flight keeps its checked landing inputs and still checks attacks below.
        if (meleePriority && mc.thePlayer.onGround) {
            if (!edgeLaunch) navigator.reset();
            detourUntil = 0;
            nearbySteering.reset();
        }
        if (navigator.jumpInProgress() && navigator.committedGoal() != null) {
            Vec3Point latest =
                    target == null
                            ? null
                            : navigator.pursuitDestination(
                                    PlayerGeometry.position(target), !target.onGround, pit);
            session.navigate(latest == null ? navigator.committedGoal() : latest, pit, true);
            if (session.running() && target != null)
                strikeWhileMoving(pit, target, profile, reacting);
            return;
        }
        if (target == null) {
            combatHop.cancel();
            session.roamPit(pit);
            return;
        }
        session.cancelRoaming();
        if (!session.targetAllowed(pit, target)) {
            session.releaseKeys();
            return;
        }
        if (target != turningTarget) {
            combatTurn.reset();
            combatSpacing.reset();
            combatStrafe.reset();
            combatTap.reset();
            pursuitProgress.reset();
            navigator.reset();
            comboEscape.reset();
            combatHop.cancel();
            nearbySteering.reset();
            detourUntil = 0;
            lastTap = CombatTap.Input.NONE;
            turningTarget = target;
        }
        if (hitbox == null) {
            hitbox = PlayerGeometry.hitbox(target);
        }
        boolean bodyInReach =
                CombatAim.distanceSquared(eye, hitbox)
                        <= CombatClicks.ATTACK_REACH * CombatClicks.ATTACK_REACH;
        Vec3Point visibleAim =
                exposedAim == null
                        ? CombatAim.clearPoint(
                                eye,
                                hitbox,
                                CombatClicks.SWING_REACH,
                                hit -> clearCombatRay(eye, hit))
                        : exposedAim;
        boolean visible = visibleAim != null || mc.thePlayer.canEntityBeSeen(target);
        if (tryCombatHop(pit, target, profile, hitbox, !meleePriority && !edgeLaunch, reacting))
            return;
        boolean direct =
                session.tick() >= detourUntil
                        && !nearbySteering.recovering(session.tick())
                        && pursuitGoal != null
                        && visible
                        && navigator.directPursuit(
                                self, pursuitGoal, pit, combatFocus.pursuitRange());
        boolean nearby =
                !direct
                        && profile == CombatProfile.BALANCED
                        && mc.thePlayer.onGround
                        && pursuitGoal != null
                        && self.horizontalDistanceSq(PlayerGeometry.position(target)) <= 36
                        && navigator.simplePursuitAllowed(pursuitGoal, pit);
        // Keep the chase band during the bot's own short knockback; geometry is still
        // rechecked on landing. Occlusion/blocked grounded corridors end direct chase immediately.
        if (mc.thePlayer.onGround || !visible) combatFocus.followingDirect(direct);
        boolean routeNeeded =
                edgeLaunch
                        || !meleePriority
                                && ((profile == CombatProfile.BALANCED
                                                ? (!bodyInReach
                                                        || exposedAim == null
                                                        || climbNeeded
                                                        || target == turningTarget
                                                                && navigator.chasingObstacleRoute())
                                                : mc.thePlayer.getDistanceToEntity(target) >= 2.7)
                                        || !visible
                                        || (session.tick() < detourUntil && !nearby));
        if (routeNeeded
                && !direct
                && !reacting
                && !comboEscape.responding(session.tick())
                && pursuitGoal != null) {
            LobbyNavigator.Step prepared =
                    navigator.continuePreparedGap(pursuitGoal, pit, session.tick());
            if (prepared != null) {
                session.navigateStep(pursuitGoal, pit, prepared);
                if (session.running()) strikeWhileMoving(pit, target, profile, reacting);
                return;
            }
        }
        // Ordinary air input during uncommitted knockback must not wait for A*.
        // The actual velocity, clipped next position and supported descent are checked below.
        boolean airReaction = profile == CombatProfile.BALANCED && !mc.thePlayer.onGround;
        boolean countering =
                profile == CombatProfile.BALANCED
                        && reacting
                        && visible
                        && (bodyInReach || direct || nearby);
        String targetLabel =
                "Target "
                        + target.getName()
                        + " | "
                        + (Math.round(mc.thePlayer.getDistanceToEntity(target) * 10) / 10.0)
                        + " blocks";
        combatDetail = targetLabel + " | routing";
        double dx = target.posX - self.x;
        double dz = target.posZ - self.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        comboEscape.observe(
                session.tick(),
                justHit,
                target.hurtTime,
                profile == CombatProfile.BALANCED && visible,
                horizontalDistance);
        boolean escaping = comboEscape.responding(session.tick());
        if (escaping) {
            combatTap.cancel();
            pursuitProgress.reset();
        }
        double separation =
                ((target.posX - target.prevPosX) - (mc.thePlayer.posX - mc.thePlayer.prevPosX)) * dx
                        + ((target.posZ - target.prevPosZ)
                                        - (mc.thePlayer.posZ - mc.thePlayer.prevPosZ))
                                * dz;
        double radialSpeed = separation / Math.max(.01, horizontalDistance);
        boolean pullingAway =
                profile == CombatProfile.BALANCED
                        ? combatSpacing.separating(radialSpeed)
                        : radialSpeed > .05;
        combatTap.observe(
                target.hurtTime,
                session.tick(),
                profile == CombatProfile.BALANCED && visible && mc.thePlayer.onGround,
                horizontalDistance,
                pullingAway,
                reacting);
        if (exposedAim == null) exposedAim = visibleAim;
        Vec3Point aimPoint = exposedAim == null ? CombatAim.point(eye, hitbox) : exposedAim;
        float targetYaw =
                (float) Math.toDegrees(Math.atan2(self.x - aimPoint.x, aimPoint.z - self.z));
        double vertical =
                (profile == CombatProfile.BALANCED
                                ? aimPoint.y
                                : target.posY + target.getEyeHeight())
                        - eye.y;
        float targetPitch =
                (float)
                        -Math.toDegrees(
                                Math.atan2(
                                        vertical,
                                        Math.max(
                                                0.01,
                                                Math.sqrt(self.horizontalDistanceSq(aimPoint)))));
        if (profile == CombatProfile.BALANCED
                && !reacting
                && !escaping
                && !direct
                && !navigator.preparingGap()
                && mc.thePlayer.onGround
                && pursuitGoal != null) {
            if (!reacting
                    && (edgeLaunch || !bodyInReach)
                    && horizontalDistance >= 2
                    && pursuitGoal.y >= self.y - .6) {
                LobbyNavigator.Step gap = navigator.nearbyGap(pursuitGoal, pit, session.tick());
                if (gap != null) {
                    session.navigateStep(pursuitGoal, pit, gap);
                    if (session.running()) strikeWhileMoving(pit, target, profile, reacting);
                    return;
                }
            }
            LobbyNavigator.Step rise =
                    routeNeeded ? navigator.nearbyRise(pursuitGoal, pit, session.tick()) : null;
            if (rise != null) {
                session.navigateStep(pursuitGoal, pit, rise);
                if (session.running()) strikeWhileMoving(pit, target, profile, reacting);
                return;
            }
        }
        if (routeNeeded && !direct && !airReaction && !countering && !nearby && !escaping) {
            LobbyNavigator.Status status = LobbyNavigator.Status.WAITING;
            if (pursuitGoal != null) status = session.navigate(pursuitGoal, pit, true);
            else {
                navigator.reset();
                movement.releaseMovement();
                navigator.activity = "Waiting for a supported pursuit destination";
            }
            if (!session.running()) return;
            if (status == LobbyNavigator.Status.MOVING) {
                // Navigation owns the heading on this tick. A valid current-view hit
                // is still considered; a missing route never authorizes unsafe movement.
                strikeWhileMoving(pit, target, profile, reacting);
                return;
            }
            // Search/landing waits retain aim and eligible melee independently of routing.
        } else {
            navigator.reset();
        }
        float yawStep = combatTurn.yaw(targetYaw - mc.thePlayer.rotationYaw, profile, reacting);
        if (!session.controlAllowed() || !session.targetAllowed(pit, target)) {
            session.stop("Safety changed before aiming");
            return;
        }
        mc.thePlayer.rotationYaw += yawStep;
        float yawGap = MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw);
        float pitchGap = MathHelper.wrapAngleTo180_float(targetPitch - mc.thePlayer.rotationPitch);
        float pitchStep = combatTurn.pitch(pitchGap, profile, reacting);
        mc.thePlayer.rotationPitch =
                MathHelper.clamp_float(mc.thePlayer.rotationPitch + pitchStep, -90, 90);

        if (profile != CombatProfile.BALANCED
                && profile.strafe
                && session.tick() >= nextStrafeChange) {
            strafeSign = random.nextBoolean() ? 1 : -1;
            nextStrafeChange = session.tick() + 14 + random.nextInt(17);
        }
        double towardSpeed =
                (mc.thePlayer.motionX * dx + mc.thePlayer.motionZ * dz)
                        / Math.max(.01, horizontalDistance);
        CombatSpacing.Direction direction =
                profile == CombatProfile.BALANCED
                        ? combatSpacing.direction(
                                horizontalDistance,
                                radialSpeed,
                                towardSpeed,
                                Math.sqrt(CombatAim.distanceSquared(eye, hitbox)),
                                meleePriority,
                                combatFocus.recentCombat(session.tick()))
                        : combatSpacing.direction(horizontalDistance, reacting);
        boolean advance =
                (profile == CombatProfile.BALANCED
                                ? direction == CombatSpacing.Direction.FORWARD
                                : horizontalDistance > (reacting ? 1.7 : 2.0))
                        && Math.abs(yawGap) < 35;
        boolean retreat =
                profile == CombatProfile.BALANCED
                        ? direction == CombatSpacing.Direction.BACK
                        : horizontalDistance < 1.15 && !reacting;
        boolean strafe =
                profile.strafe
                        && !reacting
                        && !(profile == CombatProfile.BALANCED && (pullingAway || advance))
                        && horizontalDistance < 2.5
                        && !retreat
                        && Math.abs(yawGap) < 25;
        if (routeNeeded && !direct && !airReaction && !countering && !nearby && !escaping)
            advance = retreat = strafe = false;
        boolean tryingPursuit = advance;
        NearbySteering.Input nearbyInput = NearbySteering.Input.NONE;
        if (!escaping && nearby && (advance || nearbySteering.recovering(session.tick()))) {
            nearbyInput = navigator.simplePursuit(nearbySteering, pursuitGoal, pit, session.tick());
            advance = nearbyInput.forward > 0;
            retreat = nearbyInput.forward < 0;
            strafe = nearbyInput.side != 0;
            if (strafe) strafeSign = -nearbyInput.side;
        }
        if (profile == CombatProfile.BALANCED
                && visible
                && !escaping
                && (!nearby
                        || !nearbySteering.recovering(session.tick())
                                && nearbyInput.side == 0
                                && nearbyInput.forward >= 0)
                && (!routeNeeded || direct || nearby || countering || airReaction)) {
            NearbySteering.Input choice =
                    combatStrafe.choose(
                            session.tick(),
                            self,
                            PlayerGeometry.position(target),
                            new Vec3Point(
                                    target.posX - target.prevPosX,
                                    0,
                                    target.posZ - target.prevPosZ),
                            mc.thePlayer.rotationYaw,
                            mc.thePlayer.motionX,
                            mc.thePlayer.motionZ,
                            advance ? 1 : retreat ? -1 : 0,
                            horizontalDistance <= 3.2 && !pullingAway && Math.abs(yawGap) < 25,
                            input -> safeResponseInput(self, input, pit));
            advance = choice.forward > 0;
            retreat = choice.forward < 0;
            strafe = choice.side != 0;
            if (strafe) strafeSign = -choice.side;
        }
        CombatTap.Input tap =
                combatTap.input(
                        session.tick(),
                        profile == CombatProfile.BALANCED
                                && visible
                                && mc.thePlayer.onGround
                                && !escaping
                                && (!routeNeeded || direct || nearby)
                                && !nearbySteering.recovering(session.tick())
                                && !navigator.jumpInProgress(),
                        horizontalDistance,
                        pullingAway,
                        reacting);
        if (tap == CombatTap.Input.W_RELEASE) advance = retreat = false;
        if (tap == CombatTap.Input.S_BACK) {
            advance = false;
            retreat = true;
        }
        if (escaping) {
            NearbySteering.Input escape =
                    comboEscape.input(
                            session.tick(),
                            horizontalDistance,
                            input -> safeResponseInput(self, input, pit));
            advance = false;
            retreat = escape.forward < 0;
            strafe = escape.side != 0;
            if (strafe) strafeSign = -escape.side;
            tryingPursuit = false;
        }

        // Learned steering is confined to ordinary, visible, grounded engagement.
        // Deliberate taps, escape, terrain navigation and attack gates retain ownership.
        if (profile == CombatProfile.BALANCED
                && visible
                && mc.thePlayer.onGround
                && !reacting
                && !escaping
                && tap == CombatTap.Input.NONE
                && !edgeLaunch
                && !navigator.jumpInProgress()
                && !navigator.preparingGap()
                && !nearbySteering.recovering(session.tick())
                && (!routeNeeded || direct || nearby || countering)) {
            NearbySteering.Input baseline =
                    MovementFeatures.action(
                            advance, retreat, strafe && strafeSign < 0, strafe && strafeSign > 0);
            double[] features =
                    MovementFeatures.features(
                            horizontalDistance,
                            yawGap,
                            radialSpeed,
                            towardSpeed,
                            target.posX - target.prevPosX,
                            target.posZ - target.prevPosZ,
                            mc.thePlayer.motionX,
                            mc.thePlayer.motionZ,
                            mc.thePlayer.rotationYaw,
                            mc.thePlayer.getHealth(),
                            target.getHealth(),
                            mc.thePlayer.hurtTime,
                            target.hurtTime,
                            meleePriority,
                            reacting,
                            direct,
                            nearby);
            telemetry.features(features);
            NearbySteering.Input chosen =
                    advisor.choose(
                            session.tick(),
                            features,
                            baseline,
                            candidate -> safeResponseInput(self, candidate, pit));
            advance = chosen.forward > 0;
            retreat = chosen.forward < 0;
            strafe = chosen.side != 0;
            if (strafe) strafeSign = -chosen.side;
            tryingPursuit = advance;
        }

        boolean sprint =
                combatSpacing.sprint(
                        horizontalDistance,
                        profile == CombatProfile.BALANCED
                                && !escaping
                                && (direct || nearby)
                                && advance
                                && Math.abs(yawGap) < 20
                                && movement.canSprint(),
                        pullingAway,
                        combatFocus.recentCombat(session.tick()));
        boolean sneak = false;
        double yawRadians = Math.toRadians(mc.thePlayer.rotationYaw);
        double fb = advance ? 1.0 : (retreat ? -1.0 : 0.0);
        double sidestep = strafe ? strafeSign : 0.0;
        if (airReaction) {
            double inputLength = Math.max(1, Math.hypot(fb * .98, sidestep * .98));
            double motionX =
                    mc.thePlayer.motionX
                            + (-Math.sin(yawRadians) * fb - Math.cos(yawRadians) * sidestep)
                                    * .98
                                    / inputLength
                                    * mc.thePlayer.jumpMovementFactor;
            double motionZ =
                    mc.thePlayer.motionZ
                            + (Math.cos(yawRadians) * fb - Math.sin(yawRadians) * sidestep)
                                    * .98
                                    / inputLength
                                    * mc.thePlayer.jumpMovementFactor;
            double predictedY = self.y + mc.thePlayer.motionY;
            Vec3Point predicted = new Vec3Point(self.x + motionX, predictedY, self.z + motionZ);
            if (!safeFightMovement(self, predicted, pit, true)) {
                strafe = false;
                predicted =
                        new Vec3Point(
                                self.x
                                        + mc.thePlayer.motionX
                                        - Math.sin(yawRadians)
                                                * fb
                                                * .98
                                                * mc.thePlayer.jumpMovementFactor,
                                predictedY,
                                self.z
                                        + mc.thePlayer.motionZ
                                        + Math.cos(yawRadians)
                                                * fb
                                                * .98
                                                * mc.thePlayer.jumpMovementFactor);
                if (!safeFightMovement(self, predicted, pit, true)) advance = retreat = false;
            }
        } else {
            int forward = advance ? 1 : retreat ? -1 : 0, side = strafe ? -strafeSign : 0;
            boolean safeInput = navigator.safeCombatInput(forward, side, sprint, false, pit);
            if (!safeInput) {
                // Preserve a clear forward approach when only its sidestep is blocked.
                strafe = false;
                safeInput = navigator.safeCombatInput(forward, 0, sprint, false, pit);
                if (!safeInput) {
                    sprint = false;
                    safeInput = navigator.safeCombatInput(forward, 0, false, false, pit);
                    if (!safeInput) {
                        advance = retreat = false;
                        safeInput = navigator.safeCombatInput(0, 0, false, false, pit);
                    }
                }
            }
            if (!safeInput) {
                // Releasing W alone does not stop momentum. Native sneak protects the
                // lip while an ordinary opposite input brakes; velocity stays native.
                NearbySteering.Input brake = navigator.combatBrake(pit);
                advance = brake.forward > 0;
                retreat = brake.forward < 0;
                strafe = brake.side != 0;
                if (strafe) strafeSign = -brake.side;
                sneak = true;
                sprint = false;
                combatTap.cancel();
                tap = CombatTap.Input.NONE;
            }
        }
        if (!advance || sneak) sprint = false;
        if (!sprint) combatSpacing.sprint(horizontalDistance, false, pullingAway);
        if (tap != lastTap) {
            lastTap = tap;
            if (tap != CombatTap.Input.NONE) {
                session.traceEvent("combat tap: " + tap);
                if (!session.running()) return;
            }
        }
        if (pursuitProgress.stuck(
                self,
                session.tick(),
                tryingPursuit && tap == CombatTap.Input.NONE,
                mc.thePlayer.onGround)) {
            if (nearby) nearbySteering.recover(session.tick());
            else {
                navigator.reset();
                detourUntil = session.tick() + 20;
            }
            combatTap.cancel();
            session.traceEvent("pursuit: recovering blocked corner");
            if (!session.running()) return;
        }
        if (!session.controlAllowed() || !session.targetAllowed(pit, target)) {
            session.stop("Safety changed before movement");
            return;
        }
        movement.move(
                advance, retreat, strafe && strafeSign < 0, strafe && strafeSign > 0, false, sneak);
        boolean alreadySprinting = mc.thePlayer.isSprinting();
        movement.sprint(sprint);
        if (sprint
                && alreadySprinting
                && pursuitGoal != null
                && !combatFocus.recentCombat(session.tick())
                && session.controlAllowed()
                && session.targetAllowed(pit, target)) {
            LobbyNavigator.Step leap = navigator.sprintJump(pursuitGoal, pit, true);
            if (leap != null && session.controlAllowed() && session.targetAllowed(pit, target)) {
                movement.move(true, false, false, false, true);
                navigator.commit(leap, true, session.tick());
            }
        }
        if (escaping) navigator.activity = "Escaping combo; aim/attacks active";
        else if (airReaction) navigator.activity = "Airborne tracking; aim/attacks active";
        else if (countering) navigator.activity = "Countering hit; aim/attacks active";
        else if (nearby && !navigator.jumpInProgress())
            navigator.activity = "Simple close pursuit: " + nearbyInput;
        else if (direct && !navigator.jumpInProgress())
            navigator.activity = "Following " + target.getName();
        else if (!routeNeeded) navigator.activity = "Tracking " + target.getName();

        if (session.tick() % 20 == 0) {
            session.traceEvent(
                    "pursuit state: position="
                            + self.serialize()
                            + " ground="
                            + mc.thePlayer.onGround
                            + " direct="
                            + direct
                            + " route="
                            + routeNeeded
                            + " nearby="
                            + nearby
                            + " forward="
                            + advance
                            + " sprint="
                            + sprint
                            + " bodyReach="
                            + bodyInReach
                            + " activity="
                            + navigator.activity);
            if (!session.running()) return;
        }
        strike(pit, target, profile, eye, hitbox, targetYaw, targetPitch, yawStep, pitchStep);
    }

    private boolean tryCombatHop(
            PitRegion pit,
            EntityPlayer target,
            CombatProfile profile,
            CollisionGeometry.Box hitbox,
            boolean unreachable,
            boolean reacting) {
        Vec3Point self = PlayerGeometry.position(mc.thePlayer),
                goal = PlayerGeometry.position(target);
        if (profile != CombatProfile.BALANCED
                || reacting
                || comboEscape.responding(session.tick())
                || !CombatEquipment.usable(mc.thePlayer, OptimusMod.settings.swordEnabled)
                || mc.thePlayer.onGround && !unreachable) {
            combatHop.cancel();
            return false;
        }
        if (!combatHop.active()
                && (!unreachable
                        || !mc.thePlayer.onGround
                        || !target.onGround
                        || !combatHop.mayTry(session.tick(), target, self, goal))) return false;
        if (combatHop.launched()
                && !mc.thePlayer.onGround
                && !navigator.combatHopSafe(combatHop.origin(), pit)) {
            combatHop.cancel();
            return false; // Ordinary checked knockback/air control takes over.
        }
        boolean weaponReady =
                combatEquipment.ready(
                        mc.thePlayer, session.tick(), OptimusMod.settings.swordEnabled);
        boolean possible =
                !combatHop.launched()
                        && mc.thePlayer.onGround
                        && target.onGround
                        && unreachable
                        && navigator.combatHopPossible(hitbox, pit, this::clearCombatRay);
        Vec3Point eye = new Vec3Point(self.x, self.y + mc.thePlayer.getEyeHeight(), self.z);
        Vec3Point aim =
                CombatAim.clearPoint(
                        eye, hitbox, CombatClicks.ATTACK_REACH, hit -> clearCombatRay(eye, hit));
        if (aim == null)
            aim =
                    CombatAim.clearPoint(
                            eye, hitbox, CombatClicks.SWING_REACH, hit -> clearCombatRay(eye, hit));
        if (aim == null) aim = CombatAim.point(eye, hitbox);
        float yaw = (float) Math.toDegrees(Math.atan2(self.x - aim.x, aim.z - self.z));
        float pitch =
                (float)
                        -Math.toDegrees(
                                Math.atan2(
                                        aim.y - eye.y,
                                        Math.max(.01, Math.sqrt(self.horizontalDistanceSq(aim)))));
        boolean aligned =
                weaponReady
                        && Math.abs(MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw))
                                <= 15
                        && Math.abs(pitch - mc.thePlayer.rotationPitch) <= 15;
        boolean wasLaunched = combatHop.launched();
        CombatHop.Action action =
                combatHop.update(
                        session.tick(),
                        target,
                        self,
                        goal,
                        mc.thePlayer.onGround,
                        possible,
                        aligned);
        if (action == CombatHop.Action.NONE) {
            if (wasLaunched && mc.thePlayer.onGround) navigator.allowLongerSearch();
            return false;
        }
        if (!session.controlAllowed() || !session.targetAllowed(pit, target)) {
            combatHop.cancel();
            session.stop("Safety changed before jump attack");
            return true;
        }
        navigator.reset();
        combatTap.cancel();
        pursuitProgress.reset();
        float yawStep = combatTurn.yaw(yaw - mc.thePlayer.rotationYaw, profile);
        float pitchStep = combatTurn.pitch(pitch - mc.thePlayer.rotationPitch, profile);
        mc.thePlayer.rotationYaw += yawStep;
        mc.thePlayer.rotationPitch =
                MathHelper.clamp_float(mc.thePlayer.rotationPitch + pitchStep, -90, 90);
        // No sprint impulse or horizontal input: the proof models one ordinary
        // jump from this supported position, followed by native coasting.
        movement.sprint(false);
        movement.move(false, false, false, false, action == CombatHop.Action.JUMP);
        navigator.activity =
                action == CombatHop.Action.PREPARING
                        ? "Preparing elevated jump attack"
                        : "Jumping for elevated attack";
        strike(pit, target, profile, eye, hitbox, yaw, pitch, yawStep, pitchStep);
        return true;
    }

    private boolean safeResponseInput(Vec3Point self, NearbySteering.Input input, PitRegion pit) {
        if (mc.thePlayer.onGround)
            return navigator.safeCombatInput(input.forward, input.side, false, false, pit);
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        double scale =
                .98
                        * mc.thePlayer.jumpMovementFactor
                        / Math.max(1, Math.hypot(input.forward * .98, input.side * .98));
        Vec3Point predicted =
                new Vec3Point(
                        self.x
                                + mc.thePlayer.motionX
                                + (-Math.sin(yaw) * input.forward + Math.cos(yaw) * input.side)
                                        * scale,
                        self.y + mc.thePlayer.motionY,
                        self.z
                                + mc.thePlayer.motionZ
                                + (Math.cos(yaw) * input.forward + Math.sin(yaw) * input.side)
                                        * scale);
        return safeFightMovement(self, predicted, pit, true);
    }

    private void strike(
            PitRegion pit,
            EntityPlayer target,
            CombatProfile profile,
            Vec3Point eye,
            CollisionGeometry.Box hitbox,
            float targetYaw,
            float targetPitch,
            float yawStep,
            float pitchStep) {
        if (!session.controlAllowed() || !session.targetAllowed(pit, target)) return;
        if (!combatEquipment.ready(
                mc.thePlayer, session.tick(), OptimusMod.settings.swordEnabled)) {
            combatDetail =
                    OptimusMod.settings.swordEnabled
                            ? "Waiting for combat sword"
                            : "Waiting for empty slot 2";
            telemetry.decision(
                    session.tick(),
                    target,
                    targetYaw,
                    targetPitch,
                    yawStep,
                    pitchStep,
                    false,
                    combatClicks.ready(session.tick()),
                    false,
                    false,
                    false,
                    false,
                    CombatClicks.Action.NONE);
            return;
        }
        double distance = mc.thePlayer.getDistanceToEntity(target);
        boolean bodyInReach =
                CombatAim.distanceSquared(eye, hitbox)
                        <= CombatClicks.ATTACK_REACH * CombatClicks.ATTACK_REACH;
        float yawGap = MathHelper.wrapAngleTo180_float(targetYaw - mc.thePlayer.rotationYaw);
        float pitchError =
                MathHelper.wrapAngleTo180_float(targetPitch - mc.thePlayer.rotationPitch);
        // LivingBase.getLookVec uses rotationYawHead, which can still contain
        // the previous update's yaw after this END-tick combat turn.
        boolean clearHit =
                clearCombatRay(
                        eye,
                        CombatAim.hit(
                                eye,
                                hitbox,
                                mc.thePlayer.rotationYaw,
                                mc.thePlayer.rotationPitch,
                                CombatClicks.ATTACK_REACH));
        boolean clearSwing =
                clearCombatRay(
                        eye,
                        CombatAim.hit(
                                eye,
                                hitbox,
                                mc.thePlayer.rotationYaw,
                                mc.thePlayer.rotationPitch,
                                CombatClicks.SWING_REACH));
        boolean visible = clearHit || clearSwing || mc.thePlayer.canEntityBeSeen(target);
        boolean aiming =
                profile == CombatProfile.BALANCED
                        ? clearHit
                        : Math.abs(yawGap) <= profile.yawTolerance
                                && Math.abs(pitchError) <= profile.pitchTolerance;
        boolean canAttack =
                session.controlAllowed()
                        && ControlPolicy.allowAttack(
                                session.running(),
                                session.isVerifiedLobby(),
                                pit.contains(PlayerGeometry.position(mc.thePlayer)),
                                pit.contains(PlayerGeometry.position(target)),
                                OptimusMod.settings.targets(target.getName()),
                                profile == CombatProfile.BALANCED
                                        ? clearHit
                                        : distance < CombatClicks.ATTACK_REACH,
                                visible,
                                mc.currentScreen != null);
        boolean nearMiss =
                profile == CombatProfile.BALANCED && !bodyInReach && visible && clearSwing;
        boolean ready = combatClicks.ready(session.tick());
        CombatClicks.Action action =
                combatClicks.choose(
                        session.tick(),
                        session.controlAllowed() && session.targetAllowed(pit, target),
                        canAttack && aiming,
                        nearMiss);
        if (!session.controlAllowed() || !session.targetAllowed(pit, target)) return;
        boolean attacked = action == CombatClicks.Action.ATTACK;
        if (attacked) {
            requestAttack(target, profile);
            // Explicit comparison pacing; never catch up with a burst after a pause.
            combatClicks.performed(action, session.tick(), profile.attackDelay(random));
        } else if (action == CombatClicks.Action.AIR_SWING) {
            mc.thePlayer.swingItem(); // Animation only: never send attackEntity beyond reach.
            combatClicks.performed(action, session.tick(), 0);
        }
        telemetry.decision(
                session.tick(),
                target,
                targetYaw,
                targetPitch,
                yawStep,
                pitchStep,
                true,
                ready,
                bodyInReach,
                visible,
                aiming,
                canAttack,
                action);
        String targetLabel =
                "Target "
                        + target.getName()
                        + " | "
                        + (Math.round(distance * 10) / 10.0)
                        + " blocks";
        combatDetail =
                targetLabel
                        + " | "
                        + (!visible
                                ? "target obscured"
                                : !bodyInReach
                                        ? "closing distance"
                                        : !aiming
                                                ? "aligning aim"
                                                : !canAttack
                                                        ? "attack blocked"
                                                        : !ready
                                                                ? "attack cooldown"
                                                                : attacked
                                                                        ? "attacking"
                                                                        : "tracking");
        try {
            session.trace()
                    .row(
                            session.tick(),
                            action == CombatClicks.Action.AIR_SWING ? "air_swing" : "combat",
                            session.connectedServer(),
                            profile.id(),
                            OptimusMod.settings.combatSeed,
                            target.getName(),
                            distance,
                            yawStep,
                            pitchStep,
                            yawGap,
                            pitchError,
                            canAttack,
                            aiming,
                            ready,
                            attacked);
        } catch (IOException error) {
            session.stop("Audit trace failed: " + error.getMessage());
        }
    }

    private void requestAttack(EntityPlayer target, CombatProfile profile) {
        sendAttack(target, profile, OptimusMod.settings.swordEnabled, session.tick());
    }

    boolean assistAttack(EntityPlayer target, boolean sword, long tick) {
        return sendAttack(target, OptimusMod.settings.combatProfile, sword, tick);
    }

    private boolean sendAttack(
            EntityPlayer target, CombatProfile profile, boolean sword, long tick) {
        if (!session.controlAllowed()
                || !session.targetAllowed(OptimusMod.settings.hypixelPit, target)
                || !combatEquipment.ready(mc.thePlayer, tick, sword)) return false;
        // Vanilla can clear sprint inside attackEntity. Record the state that
        // actually requested the hit, before that normal client-side reset.
        boolean sprintAtRequest = mc.thePlayer.isSprinting();
        mc.thePlayer.swingItem();
        mc.playerController.attackEntity(mc.thePlayer, target);
        telemetry.attackRequested(session.tick());
        combatFocus.attacked(session.tick());
        comboEscape.attacked(session.tick());
        if (profile == CombatProfile.BALANCED) combatTap.attacked(session.tick(), sprintAtRequest);
        return true;
    }

    private void strikeWhileMoving(
            PitRegion pit, EntityPlayer target, CombatProfile profile, boolean reacting) {
        if (!session.controlAllowed() || !session.targetAllowed(pit, target)) return;
        Vec3Point self = PlayerGeometry.position(mc.thePlayer),
                eye = new Vec3Point(self.x, self.y + mc.thePlayer.getEyeHeight(), self.z);
        CollisionGeometry.Box hitbox = PlayerGeometry.hitbox(target);
        Vec3Point aim =
                CombatAim.clearPoint(
                        eye, hitbox, CombatClicks.SWING_REACH, hit -> clearCombatRay(eye, hit));
        if (aim == null) aim = CombatAim.point(eye, hitbox);
        float yaw = (float) Math.toDegrees(Math.atan2(self.x - aim.x, aim.z - self.z));
        float pitch =
                (float)
                        -Math.toDegrees(
                                Math.atan2(
                                        (profile == CombatProfile.BALANCED
                                                        ? aim.y
                                                        : target.posY + target.getEyeHeight())
                                                - eye.y,
                                        Math.max(.01, Math.sqrt(self.horizontalDistanceSq(aim)))));
        // Pitch does not redirect the horizontal movement/jump. A committed arc
        // keeps its own yaw until damage cancels it or its safe landing completes.
        float pitchStep = combatTurn.pitch(pitch - mc.thePlayer.rotationPitch, profile, reacting);
        mc.thePlayer.rotationPitch =
                MathHelper.clamp_float(mc.thePlayer.rotationPitch + pitchStep, -90, 90);
        strike(pit, target, profile, eye, hitbox, yaw, pitch, 0, pitchStep);
    }

    private boolean clearCombatRay(Vec3Point eye, Vec3Point hit) {
        return hit != null
                && mc.theWorld.rayTraceBlocks(
                                new net.minecraft.util.Vec3(eye.x, eye.y, eye.z),
                                new net.minecraft.util.Vec3(hit.x, hit.y, hit.z))
                        == null;
    }

    private boolean safeFightMovement(
            Vec3Point from, Vec3Point predicted, PitRegion pit, boolean airReaction) {
        return airReaction
                ? navigator.safeKnockbackStep(from, predicted, pit)
                : navigator.safeCombatStep(from, predicted, pit);
    }
}