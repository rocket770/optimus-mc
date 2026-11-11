package dev.optimus.client;

import dev.optimus.core.combat.CombatHop;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.DropGate;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GridPathfinder.Node;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.JumpArc;
import dev.optimus.core.navigation.JumpPreparation;
import dev.optimus.core.navigation.LobbyTravel;
import dev.optimus.core.navigation.LocalApproach;
import dev.optimus.core.navigation.MotionProgress;
import dev.optimus.core.navigation.NavigationView;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.PitPatrol;
import dev.optimus.core.navigation.PlatformJump;
import dev.optimus.core.navigation.PursuitGoal;
import dev.optimus.core.navigation.SprintGap;
import dev.optimus.core.navigation.SprintJump;
import dev.optimus.core.navigation.TravelSprint;
import dev.optimus.core.navigation.WalkSteering;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/** Loaded geometry, bounded parkour and a separately authorized deep pit entry. */
final class LobbyNavigator {
    enum Status {
        MOVING,
        WAITING,
        ARRIVED,
        FAILED
    }

    static final class Step {
        final Status status;
        final Vec3Point waypoint;
        final boolean jump;
        final boolean brake;
        final boolean sprint;
        final SprintJump.Plan leap;
        final NearbySteering.Input input;
        final SprintGap.Plan gapLeap;
        final PlatformJump.Plan platformLeap;
        final boolean alignOnly;
        final boolean normalClimb;
        HintJump.Control hint;

        Step(HintJump.Control hint) {
            this(Status.MOVING, null, false);
            this.hint = hint;
        }

        Step(Status status, Vec3Point waypoint, boolean jump) {
            this(status, waypoint, jump, false);
        }

        Step(Status status, Vec3Point waypoint, boolean jump, boolean brake) {
            this(status, waypoint, jump, brake, false, null);
        }

        Step(
                Status status,
                Vec3Point waypoint,
                boolean jump,
                boolean brake,
                boolean sprint,
                SprintJump.Plan leap) {
            this(status, waypoint, jump, brake, sprint, leap, false);
        }

        Step(
                Status status,
                Vec3Point waypoint,
                boolean jump,
                boolean brake,
                boolean sprint,
                SprintJump.Plan leap,
                boolean normalClimb) {
            this.status = status;
            this.waypoint = waypoint;
            this.jump = jump;
            this.brake = brake;
            this.sprint = sprint;
            this.leap = leap;
            this.input = null;
            this.gapLeap = null;
            this.platformLeap = null;
            this.alignOnly = false;
            this.normalClimb = normalClimb;
        }

        Step(NearbySteering.Input input, Vec3Point waypoint) {
            this.status = Status.MOVING;
            this.waypoint = waypoint;
            this.jump = this.brake = this.sprint = false;
            this.leap = null;
            this.input = input;
            this.gapLeap = null;
            this.platformLeap = null;
            this.alignOnly = false;
            this.normalClimb = false;
        }

        Step(
                Vec3Point waypoint,
                boolean jump,
                boolean brake,
                boolean sprint,
                SprintGap.Plan gapLeap) {
            this.status = Status.MOVING;
            this.waypoint = waypoint;
            this.jump = jump;
            this.brake = brake;
            this.sprint = sprint;
            this.leap = null;
            this.input = null;
            this.gapLeap = gapLeap;
            this.platformLeap = null;
            this.alignOnly = false;
            this.normalClimb = false;
        }

        Step(Vec3Point heading, PlatformJump.Plan plan) {
            this.status = Status.MOVING;
            this.waypoint = heading;
            this.jump = plan != null;
            this.brake = this.sprint = false;
            this.leap = null;
            this.input = null;
            this.gapLeap = null;
            this.platformLeap = plan;
            this.alignOnly = plan == null;
            this.normalClimb = false;
        }
    }

    private final Minecraft mc;
    private final AdvancedJumpController advanced;
    private List<Node> path = Collections.emptyList();
    private List<Vec3Point> debugArc = Collections.emptyList();
    private Vec3Point debugWaypoint;
    private GridPathfinder.Search search;
    private GroundRoute plannedTerrain;
    private GroundRoute searchTerrain;
    private final MotionProgress movementProgress = new MotionProgress();
    private final NearbySteering escapeSteering = new NearbySteering();
    private final JumpPreparation preparation = new JumpPreparation();
    private long decisionTick;
    private boolean recoverRoute;
    private long localRetryAt;
    private long retryAt;
    private boolean extendedSearch;
    private boolean hintedPath;
    private RouteHints pathHints;
    private RouteHints searchHints;
    private long hintRetryAt;
    private final Map<String, Long> rejectedHints = new HashMap<>();
    String activity = "Waiting";
    private final CollisionGeometry geometry;

    private CollisionGeometry createGeometry() {
        return new CollisionGeometry(
                (x, y, z) -> {
                    if (mc.theWorld == null || y < 0 || y > 255)
                        return CollisionGeometry.Cell.UNLOADED;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!mc.theWorld.isBlockLoaded(pos)) return CollisionGeometry.Cell.UNLOADED;
                    IBlockState state = mc.theWorld.getBlockState(pos);
                    List<AxisAlignedBB> nativeBoxes = new ArrayList<>();
                    state.getBlock()
                            .addCollisionBoxesToList(
                                    mc.theWorld,
                                    pos,
                                    state,
                                    new AxisAlignedBB(x, y, z, x + 1, y + 2, z + 1),
                                    nativeBoxes,
                                    mc.thePlayer);
                    List<CollisionGeometry.Box> boxes = new ArrayList<>();
                    for (AxisAlignedBB b : nativeBoxes)
                        boxes.add(
                                new CollisionGeometry.Box(
                                        b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
                    return new CollisionGeometry.Cell(true, hazardous(state.getBlock()), boxes);
                });
    }

    private GroundRoute terrain(CollisionGeometry g, PitRegion area) {
        ClientSettings s = OptimusMod.settings;
        return new GroundRoute(
                g,
                area,
                s.hypixelPit,
                s.dropRim,
                s.dropFloor,
                s.parkourEnabled,
                !mc.thePlayer.isPotionActive(Potion.jump),
                (from, to) ->
                        preparation.allowed(from, to, decisionTick)
                                || g.autoStepClear(from, to, area)
                                || g.walkOffClear(from, to, area));
    }

    private Vec3Point pathPoint(int i) {
        return plannedTerrain.point(path.get(i));
    }

    Vec3Point flightLanding(PitRegion area) {
        return geometry.landingBelow(self(), area);
    }

    private int index;
    private Vec3Point plannedGoal, progress;
    private long progressAt, airborneAt;
    private long planUpdatedAt;
    private boolean targetMode;
    private boolean gapCommitted, wasAirborne;
    private SprintJump.Plan sprintLeap;
    private boolean leapAirborne;
    private long leapStarted;
    private long gapStarted;
    private Vec3Point blockLanding;
    private Vec3Point blockStart;
    private boolean blockAirborne;
    private boolean blockSimple;
    private long blockStarted;
    private SprintGap.Candidate gapPreparing;
    private boolean gapFromRoute;
    private Vec3Point gapPrepareGoal;
    private boolean gapRunReady;
    private Vec3Point gapEdgeFrom, gapEdgeDelta;
    private long gapEdgeAt;
    private SprintGap.Plan gapLeap;
    private boolean gapAirborne;
    private long sprintGapAt;
    private PlatformJump.Plan platformLeap;
    private boolean platformAirborne;
    private long platformStarted;
    String failure = "";

    LobbyNavigator(Minecraft mc) {
        this.mc = mc;
        this.geometry = createGeometry();
        this.advanced = new AdvancedJumpController(mc);
    }

    void clearDebug() {
        debugArc = Collections.emptyList();
        debugWaypoint = null;
    }

    NavigationView debugView() {
        List<Vec3Point> route = new ArrayList<>(), arc = new ArrayList<>();
        if (plannedTerrain != null)
            for (int i = Math.max(0, index - 1); i < path.size() && route.size() < 64; i++)
                route.add(pathPoint(i));
        if (advanced.inFlight()) {
            advanced.appendArc(arc);
        } else if (jumpInProgress()) arc.addAll(debugArc);
        Vec3Point active =
                !arc.isEmpty()
                        ? arc.get(arc.size() - 1)
                        : plannedTerrain != null && index < path.size()
                                ? pathPoint(index)
                                : debugWaypoint;
        return new NavigationView(route, arc, active, plannedGoal);
    }

    private void captureDebugArc(Step step) {
        debugArc = Collections.emptyList();
        if (OptimusMod.settings == null
                || !OptimusMod.settings.debugVisuals
                || mc.thePlayer == null
                || mc.theWorld == null) return;
        try {
            Vec3Point current = self();
            BlockPos below = new BlockPos(current.x, current.y - 1, current.z);
            if (!mc.theWorld.isBlockLoaded(below)) return;
            double friction = mc.theWorld.getBlockState(below).getBlock().slipperiness * .91;
            double ground =
                    mc.thePlayer.getAIMoveSpeed()
                            * .16277136
                            / (friction * friction * friction)
                            * .98;
            double air = mc.thePlayer.jumpMovementFactor * .98;
            PitRegion area =
                    OptimusMod.settings.hypixelPit.containsBody(current, .3, 1.8)
                            ? OptimusMod.settings.hypixelPit
                            : LobbyTravel.area();
            if (step.platformLeap != null)
                debugArc =
                        PlatformJump.samples(
                                geometry.cached(),
                                area,
                                step.platformLeap,
                                mc.thePlayer.motionX,
                                mc.thePlayer.motionZ,
                                ground,
                                air,
                                friction,
                                0);
            else if (step.gapLeap != null) {
                SprintGap.Candidate c = step.gapLeap.candidate;
                debugArc =
                        SprintGap.samples(
                                step.gapLeap.start,
                                c.landing.y,
                                c.dx,
                                c.dz,
                                mc.thePlayer.motionX * c.dx + mc.thePlayer.motionZ * c.dz,
                                ground,
                                air,
                                friction);
            } else if (step.leap != null)
                debugArc =
                        SprintJump.samples(
                                step.leap.start,
                                step.leap.dx,
                                step.leap.dz,
                                mc.thePlayer.motionX * step.leap.dx
                                        + mc.thePlayer.motionZ * step.leap.dz,
                                ground,
                                air,
                                friction);
            else if (step.jump && step.waypoint != null)
                debugArc = JumpArc.samples(current, step.waypoint);
        } catch (RuntimeException ignored) {
            debugArc = Collections.emptyList();
        } // Display data cannot interrupt controls.
    }

    void reset() {
        clearDebug();
        preparation.reset();
        resetRoute();
    }

    private void resetRoute() {
        clearDebug();
        hintedPath = false;
        pathHints = null;
        searchHints = null;
        advanced.reset();
        path = Collections.emptyList();
        index = 0;
        plannedGoal = progress = null;
        extendedSearch = false;
        progressAt = airborneAt = retryAt = 0;
        failure = "";
        search = null;
        plannedTerrain = null;
        activity = "Waiting";
        gapCommitted = wasAirborne = false;
        sprintLeap = null;
        leapAirborne = false;
        blockLanding = null;
        blockAirborne = false;
        blockSimple = false;
        gapPreparing = null;
        gapFromRoute = false;
        gapLeap = null;
        gapAirborne = gapRunReady = false;
        gapEdgeFrom = gapEdgeDelta = null;
        platformLeap = null;
        platformAirborne = false;
        searchTerrain = null;
        movementProgress.reset();
        recoverRoute = false;
        localRetryAt = 0;
        escapeSteering.reset();
    }

    private Step failed(String reason) {
        if (hintedPath) {
            if (index > 0 && index < path.size())
                rejectedHints.put(
                        RouteHints.key(path.get(index - 1), path.get(index)), decisionTick + 200);
            hintRetryAt = decisionTick + 40;
        }
        hintedPath = false;
        pathHints = null;
        searchHints = null;
        advanced.reset();
        failure = reason;
        activity = reason + "; retrying";
        path = Collections.emptyList();
        search = null;
        gapCommitted = wasAirborne = false;
        progress = null;
        sprintLeap = null;
        leapAirborne = false;
        blockLanding = null;
        blockAirborne = false;
        blockSimple = false;
        gapPreparing = null;
        gapLeap = null;
        gapAirborne = gapRunReady = false;
        gapEdgeFrom = gapEdgeDelta = null;
        platformLeap = null;
        platformAirborne = false;
        return new Step(Status.FAILED, null, false);
    }

    private Vec3Point self() {
        return new Vec3Point(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
    }

    private AxisAlignedBB body(Vec3Point point) {
        return new AxisAlignedBB(
                point.x - 0.30,
                point.y + 0.01,
                point.z - 0.30,
                point.x + 0.30,
                point.y + 1.80,
                point.z + 0.30);
    }

    private boolean hazardous(Block block) {
        return block.getMaterial().isLiquid()
                || block == Blocks.fire
                || block == Blocks.cactus
                || block == Blocks.web
                || block == Blocks.portal
                || block == Blocks.end_portal
                || block == Blocks.ladder
                || block == Blocks.vine;
    }

    private CollisionGeometry.Box box(AxisAlignedBB b) {
        return new CollisionGeometry.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    private boolean free(AxisAlignedBB b) {
        return geometry.free(box(b));
    }

    private boolean supported(Vec3Point point) {
        return geometry.supported(point, .15);
    }

    private boolean within(PitRegion area, Vec3Point point) {
        return area.containsBody(point, 0.30, 1.80);
    }

    private boolean standable(PitRegion area, Vec3Point point) {
        return within(area, point) && free(body(point)) && supported(point);
    }

    private boolean edgeClear(Vec3Point from, Vec3Point to, PitRegion area) {
        return terrain(geometry, area).edgeClear(from, to);
    }

    Vec3Point resolveEntry(ClientSettings settings, PitRegion travelArea) {
        return terrain(geometry.cached(), travelArea).entry(settings.pitEntrance);
    }

    Vec3Point beginPatrol(PitRegion pit, Random random) {
        if (!onSafeGround(pit) || gapCommitted) return null;
        GroundRoute route = terrain(geometry.cached(), pit);
        List<Node> patrol = PitPatrol.route(route, self(), random);
        if (patrol.isEmpty()) return null;
        reset();
        plannedTerrain = route;
        path = patrol;
        plannedGoal = route.point(patrol.get(patrol.size() - 1));
        targetMode = false;
        activity = "Roaming pit";
        return plannedGoal;
    }

    void commit(Step step, boolean issuedJump, long tick) {
        debugWaypoint = step.waypoint;
        if (issuedJump && !jumpInProgress()) captureDebugArc(step);
        if (issuedJump && step.platformLeap != null && platformLeap == null) {
            preparation.launched();
            platformLeap = step.platformLeap;
            platformAirborne = false;
            platformStarted = tick;
            return;
        }
        if (platformLeap != null) return;
        if (issuedJump && step.gapLeap != null && gapLeap == null) {
            preparation.launched();
            if (!gapFromRoute) {
                // An independent shortcut has bypassed the old approach/search origin.
                // Resume from the actual landing rather than walking back along that prefix.
                path = Collections.emptyList();
                search = null;
                plannedTerrain = null;
                searchTerrain = null;
                hintedPath = false;
                pathHints = null;
                searchHints = null;
                hintRetryAt = tick;
            }
            gapLeap = step.gapLeap;
            gapAirborne = false;
            sprintGapAt = tick;
            gapPreparing = null;
            gapEdgeFrom = gapEdgeDelta = null;
            activity = "Sprint jumping " + gapLeap.candidate.emptyBlocks + "-block gap";
            return;
        }
        if (gapLeap != null) return;
        if (issuedJump && step.leap != null) {
            sprintLeap = step.leap;
            leapAirborne = false;
            leapStarted = tick;
            if (!hintedPath) plannedGoal = step.leap.goal;
            activity = "Sprint jumping";
            return;
        }
        if (!gapCommitted
                && issuedJump
                && step.jump
                && !path.isEmpty()
                && index > 0
                && index < path.size()
                && plannedTerrain.gap(pathPoint(index - 1), pathPoint(index))) {
            gapCommitted = true;
            wasAirborne = false;
            gapStarted = tick;
        } else if (issuedJump
                && step.jump
                && blockLanding == null
                && sprintLeap == null
                && step.waypoint != null
                && step.waypoint.y - mc.thePlayer.posY > .6) {
            blockLanding = step.waypoint;
            blockStart = self();
            blockAirborne = false;
            blockStarted = tick;
            blockSimple = step.normalClimb;
        }
    }

    CollisionGeometry collisionSnapshot() {
        return geometry.cached();
    }

    boolean travelJumpInProgress() {
        return sprintLeap != null;
    }

    boolean jumpInProgress() {
        return advanced.inFlight()
                || gapCommitted
                || sprintLeap != null
                || blockLanding != null
                || gapLeap != null
                || platformLeap != null;
    }

    boolean normalClimb(Step step) {
        return step.normalClimb || ordinaryClimbInProgress();
    }

    boolean ordinaryClimbInProgress() {
        return blockLanding != null && blockSimple;
    }

    boolean edgeLaunchPending(long tick) {
        return gapEdgeFrom != null && tick == gapEdgeAt + 1 && mc.thePlayer.onGround;
    }

    boolean preparingGap() {
        return gapPreparing != null || preparingHint();
    }

    boolean preparingHint() {
        return advanced.preparing();
    }

    Step continuePreparedGap(Vec3Point destination, PitRegion area, long tick) {
        if (gapPreparing == null || jumpInProgress() || destination == null) return null;
        if (plannedGoal != null
                && (plannedGoal.horizontalDistanceSq(destination) > 9
                        || Math.abs(plannedGoal.y - destination.y) > .6)
                && !sameGoalSurface(plannedGoal, destination, area)) {
            gapPreparing = null;
            gapEdgeFrom = gapEdgeDelta = null;
            return null;
        }
        // The cached goal can be an intermediate landing, not the opponent's platform.
        // Keep that edge's setup and native grounded-step proof until it launches.
        Step prepared = nearbyGap(gapPrepareGoal, area, tick, gapFromRoute);
        plannedGoal = destination;
        targetMode = true;
        return prepared;
    }

    void allowLongerSearch() {
        extendedSearch = true;
    }

    boolean holdGapJump() {
        return gapLeap != null && !mc.thePlayer.onGround;
    }

    boolean sprintHeading(Step step) {
        return step.leap != null
                || step.gapLeap != null
                || sprintLeap != null
                || gapLeap != null
                || preparingGap() && (step.alignOnly || step.sprint);
    }

    void attemptedMovement(boolean trying, long tick) {
        if (preparingGap()) {
            movementProgress.reset();
            return;
        }
        if (movementProgress.stuck(self(), tick, trying, mc.thePlayer.onGround)
                && !jumpInProgress()) {
            recoverRoute = true;
            escapeSteering.recover(tick);
        }
    }

    NearbySteering.Input simplePursuit(
            NearbySteering steering, Vec3Point goal, PitRegion pit, long tick) {
        return steering.choose(
                geometry.cached(), pit, self(), goal, mc.thePlayer.rotationYaw, tick);
    }

    boolean simplePursuitAllowed(Vec3Point goal, PitRegion area) {
        Vec3Point current = self();
        if (goal == null || Math.abs(goal.y - current.y) > .6 || preparingGap()) return false;
        // Keep an existing sequence of climbs/gaps instead of replacing it with
        // ground-only side steps as soon as the target comes within six blocks.
        if (chasingObstacleRoute()) return false;
        // A short void corridor needs a checked jump route, even on the same level.
        CollisionGeometry live = geometry.cached();
        double distance = Math.sqrt(current.horizontalDistanceSq(goal));
        int samples = Math.max(1, (int) Math.ceil(distance / .5));
        for (int i = 1; i < samples; i++) {
            double f = (double) i / samples;
            Vec3Point p =
                    new Vec3Point(
                            current.x + (goal.x - current.x) * f,
                            current.y,
                            current.z + (goal.z - current.z) * f);
            if (live.free(CollisionGeometry.body(p)) && !live.supported(p, .3)) return false;
        }
        return true;
    }

    boolean chasingObstacleRoute() {
        if (targetMode && index < path.size())
            for (int i = Math.max(1, index); i < path.size(); i++) {
                Vec3Point previous = pathPoint(i - 1), next = pathPoint(i);
                if (next.y - previous.y > .6 || previous.horizontalDistanceSq(next) > 2.01)
                    return true;
            }
        return false;
    }

    Step nearbyRise(Vec3Point goal, PitRegion pit, long tick) {
        decisionTick = tick;
        Vec3Point current = self();
        if (!mc.thePlayer.onGround
                || jumpInProgress()
                || goal == null
                || current.horizontalDistanceSq(goal) > 36
                || goal.y - current.y <= .6) return null;
        GroundRoute route = terrain(geometry.cached(), pit);
        Node start = route.start(current);
        if (start == null) return null;
        Node chosen = null;
        double best = current.horizontalDistanceSq(goal);
        for (int[] direction :
                new int[][] {
                    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
                }) {
            Node next = new Node(start.x + direction[0], start.y2 + 2, start.z + direction[1]);
            Vec3Point landing = route.point(next);
            if (landing == null
                    || landing.y - current.y <= .6
                    || !route.edgeClear(current, landing)) continue;
            double score = landing.horizontalDistanceSq(goal);
            if (score < best) {
                best = score;
                chosen = next;
            }
        }
        if (chosen == null) return null;
        resetRoute();
        plannedTerrain = route;
        path = Arrays.asList(start, chosen);
        index = 1;
        plannedGoal = goal;
        targetMode = true;
        activity = "Nearby block climb";
        Step normal = prepareAdjacentClimb(route.point(chosen), pit);
        return normal != null ? normal : preparePlatform(route.point(chosen), pit, tick);
    }

    private Step prepareAdjacentClimb(Vec3Point landing, PitRegion area) {
        Vec3Point current = self();
        double rise = landing.y - current.y,
                distance = Math.sqrt(current.horizontalDistanceSq(landing));
        if (!mc.thePlayer.onGround
                || mc.thePlayer.hurtTime > 0
                || mc.thePlayer.isPotionActive(Potion.jump)
                || rise <= .6
                || rise > 1.01
                || distance < .1
                || distance * distance > 2.01
                || Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ) > .2) return null;
        CollisionGeometry live = geometry.cached();
        GroundRoute route =
                new GroundRoute(live, area, OptimusMod.settings.hypixelPit, null, 63, false, true);
        if (!live.standable(area, current)
                || !live.standable(area, landing)
                || !route.edgeClear(current, landing)) return null;
        Vec3Point high = new Vec3Point(current.x, current.y + 1.3, current.z);
        double dx = (landing.x - current.x) / distance, dz = (landing.z - current.z) / distance;
        for (double offset : new double[] {-22.5, 0, 22.5}) {
            double a = Math.toRadians(offset),
                    x = dx * Math.cos(a) - dz * Math.sin(a),
                    z = dx * Math.sin(a) + dz * Math.cos(a);
            Vec3Point end =
                    new Vec3Point(current.x + x * distance, high.y, current.z + z * distance);
            if (!area.containsBody(end, .3, 1.8)
                    || !live.free(CollisionGeometry.body(high).union(CollisionGeometry.body(end)))
                    || !live.supported(new Vec3Point(end.x, landing.y, end.z), .3)) return null;
        }
        activity = "Jumping up adjacent block";
        return new Step(Status.MOVING, landing, true, false, false, null, true);
    }

    Step preparePlatform(Vec3Point landing, PitRegion area, long tick) {
        decisionTick = tick;
        Vec3Point current = self();
        double distance = Math.sqrt(current.horizontalDistanceSq(landing));
        if (distance < .1 || !PlatformJump.candidate(current, landing)) return null;
        double dx = (landing.x - current.x) / distance, dz = (landing.z - current.z) / distance;
        Vec3Point heading = new Vec3Point(current.x + dx * 3, current.y, current.z + dz * 3);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float alignment = WalkSteering.alignment(yaw, mc.thePlayer.rotationYaw);
        if (!preparation.observe(current, landing, alignment, tick)) {
            activity = "Platform alignment stalled; choosing another route";
            path = Collections.emptyList();
            search = null;
            retryAt = 0;
            return null;
        }
        if (mc.thePlayer.isSprinting()) {
            activity = "Releasing sprint for platform jump";
            return new Step(heading, (PlatformJump.Plan) null);
        }
        BlockPos below = new BlockPos(current.x, current.y - 1, current.z);
        if (!mc.theWorld.isBlockLoaded(below)) return null;
        double friction = mc.theWorld.getBlockState(below).getBlock().slipperiness * .91;
        double acceleration =
                mc.thePlayer.getAIMoveSpeed() * .16277136 / (friction * friction * friction) * .98;
        NearbySteering.Input keys = WalkSteering.input(yaw, mc.thePlayer.rotationYaw);
        Vec3Point inputDirection = keys.point(current, mc.thePlayer.rotationYaw, 1);
        PlatformJump.Plan plan =
                PlatformJump.plan(
                        geometry.cached(),
                        area,
                        current,
                        landing,
                        mc.thePlayer.motionX,
                        mc.thePlayer.motionZ,
                        acceleration,
                        mc.thePlayer.jumpMovementFactor * .98,
                        friction,
                        inputDirection.x - current.x,
                        inputDirection.z - current.z);
        if (plan != null) {
            activity = "Taking off toward platform";
            return new Step(
                    new Vec3Point(current.x + plan.dx * 3, current.y, current.z + plan.dz * 3),
                    plan);
        }
        if (Math.abs(alignment) > 2) {
            activity = "Adjusting platform input angle";
            return new Step(heading, (PlatformJump.Plan) null);
        }
        Vec3Point back = new Vec3Point(current.x - dx * .15, current.y, current.z - dz * .15);
        if (geometry.cached().walkEnd(current, back, area) != null
                && Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ) > .05) {
            activity = "Slowing for platform takeoff";
            return new Step(Status.MOVING, heading, false, true);
        }
        if (!SprintGap.candidate(current, landing)) {
            if (plannedTerrain != null && index > 0 && index < path.size()) {
                Vec3Point takeoff = pathPoint(index - 1);
                // A valid grid edge may need its checked origin after a diagonal
                // approach has coasted past it. Rejoin on supported ground before
                // rejecting the whole corridor for this fractional launch pose.
                if (current.horizontalDistanceSq(takeoff) > .05 * .05
                        && Math.abs(current.y - takeoff.y) < .06
                        && geometry.cached().autoStepClear(current, takeoff, area)
                        && PlatformJump.possible(geometry.cached(), area, takeoff, landing)) {
                    activity = "Rejoining checked platform takeoff";
                    return new Step(Status.MOVING, takeoff, false);
                }
            }
            // A prospective edge at an ideal speed is not an executable launch
            // from this pose. Keep walking detours eligible instead of walking
            // blindly into the obstacle and retrying the same theoretical arc.
            preparation.reject(current, landing, tick);
            path = Collections.emptyList();
            search = null;
            retryAt = 0;
            activity = "Platform takeoff unavailable; choosing another route";
        }
        return null;
    }

    Step nearbyGap(Vec3Point goal, PitRegion area, long tick) {
        return nearbyGap(goal, area, tick, false);
    }

    private Step nearbyGap(Vec3Point goal, PitRegion area, long tick, boolean fromRoute) {
        decisionTick = tick;
        if (!OptimusMod.settings.parkourEnabled
                || !mc.thePlayer.onGround
                || jumpInProgress()
                || mc.thePlayer.hurtTime > 0
                || mc.thePlayer.isPotionActive(Potion.jump)
                || mc.thePlayer.isPotionActive(Potion.blindness)
                || goal == null) return null;
        Vec3Point current = self();
        CollisionGeometry live = geometry.cached();
        if (gapPreparing == null
                || gapFromRoute != fromRoute
                || gapPrepareGoal.horizontalDistanceSq(goal) > 4
                || Math.abs(gapPrepareGoal.y - goal.y) > .5) {
            gapPreparing = SprintGap.find(live, area, current, goal);
            gapRunReady = false;
            gapPrepareGoal = goal;
            gapFromRoute = fromRoute;
            gapEdgeFrom = gapEdgeDelta = null;
        }
        if (gapPreparing == null) return null;
        SprintGap.Candidate candidate = gapPreparing;
        if (!SprintGap.launchSupported(live, area, candidate.takeoff)
                || !live.standable(area, candidate.landing)) {
            gapPreparing = null;
            return null;
        }
        plannedGoal = goal;
        double dx = candidate.dx, dz = candidate.dz;
        Vec3Point heading = new Vec3Point(current.x + dx * 3, current.y, current.z + dz * 3);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (!preparation.observe(
                current, candidate.landing, yaw - mc.thePlayer.rotationYaw, tick)) {
            gapPreparing = null;
            path = Collections.emptyList();
            search = null;
            retryAt = 0;
            activity = "Gap alignment stalled; choosing another route";
            return null;
        }
        double lateral =
                (current.x - candidate.takeoff.x) * dz - (current.z - candidate.takeoff.z) * dx;
        if (Math.abs(lateral) > .08) {
            if (!live.autoStepClear(current, candidate.runUp, area)) {
                gapPreparing = null;
                return null;
            }
            activity = "Aligning gap-jump runway";
            return new Step(Status.MOVING, candidate.runUp, false);
        }
        // Finish the heading before stepping along the narrow runway. Even the
        // ordinary turn deadzone can leave enough error for sideways drift.
        if (Math.abs(MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw)) > .01) {
            activity = "Aligning sprint-gap heading";
            return new Step(heading, (PlatformJump.Plan) null);
        }
        double toRunUp =
                (current.x - candidate.runUp.x) * dx + (current.z - candidate.runUp.z) * dz;
        if (!gapRunReady && toRunUp > .3) {
            Vec3Point back = new Vec3Point(current.x - dx * .28, current.y, current.z - dz * .28);
            Vec3Point floor = live.walkEnd(current, back, area);
            // Use the same full-foot support as the checked runway. A diagonal
            // seam can support the native body while its exact center is over air.
            if (floor == null || Math.abs(floor.y - current.y) > .06) {
                gapPreparing = null;
                return null;
            }
            activity = "Lining up sprint gap jump";
            return new Step(Status.MOVING, heading, false, true);
        }
        gapRunReady = true;
        double along =
                (current.x - candidate.takeoff.x) * dx + (current.z - candidate.takeoff.z) * dz;
        double speed = mc.thePlayer.motionX * dx + mc.thePlayer.motionZ * dz;
        BlockPos below = new BlockPos(current.x, current.y - 1, current.z);
        if (!mc.theWorld.isBlockLoaded(below)) {
            gapPreparing = null;
            return null;
        }
        double friction = mc.theWorld.getBlockState(below).getBlock().slipperiness * .91;
        double acceleration =
                mc.thePlayer.getAIMoveSpeed() * .16277136 / (friction * friction * friction) * .98;
        if (along >= -.12
                && speed >= .12
                && mc.thePlayer.isSprinting()
                && Math.abs(mc.thePlayer.motionX * dz - mc.thePlayer.motionZ * dx) <= .025
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw)) <= 2) {
            SprintGap.Plan plan =
                    gapEdgeFrom != null && tick == gapEdgeAt + 1
                            ? SprintGap.planAfterGroundedStep(
                                    live,
                                    area,
                                    candidate,
                                    gapEdgeFrom,
                                    current,
                                    gapEdgeDelta,
                                    speed,
                                    acceleration,
                                    mc.thePlayer.jumpMovementFactor * .98,
                                    friction)
                            : null;
            if (plan == null)
                plan =
                        SprintGap.plan(
                                live,
                                area,
                                candidate,
                                current,
                                speed,
                                acceleration,
                                mc.thePlayer.jumpMovementFactor * .98,
                                friction,
                                true);
            if (plan != null) {
                activity = "Taking off across " + candidate.emptyBlocks + "-block gap";
                return new Step(heading, true, false, true, plan);
            }
        }
        Vec3Point ahead = new Vec3Point(current.x + dx * .35, current.y, current.z + dz * .35);
        Vec3Point floor = live.walkEnd(current, ahead, area);
        if (floor == null || Math.abs(floor.y - current.y) > .06) {
            // For an edge launch, validate the actual next grounded pose AND its
            // complete jump before issuing the final sprint step. No blind fall
            // or synthetic onGround/velocity change is needed.
            if (mc.thePlayer.isSprinting()
                    && speed >= .12
                    && mc.thePlayer.motionY < -.005
                    && Math.abs(MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw))
                            <= 2) {
                // Match native moveFlying's float lookup, not double sin/cos:
                // even a tiny difference can reject the next tick's edge pose.
                float angle = mc.thePlayer.rotationYaw * .017453292f;
                // EntityLivingBase zeroes each tiny stored motion component before
                // applying this tick's ground input. Retaining it here predicts a
                // different edge pose and invalidates the next tick's step proof.
                double mx =
                        (Math.abs(mc.thePlayer.motionX) < .005 ? 0 : mc.thePlayer.motionX)
                                - MathHelper.sin(angle) * acceleration;
                double mz =
                        (Math.abs(mc.thePlayer.motionZ) < .005 ? 0 : mc.thePlayer.motionZ)
                                + MathHelper.cos(angle) * acceleration;
                Vec3Point edge =
                        live.jumpMove(current, new Vec3Point(mx, mc.thePlayer.motionY, mz), area);
                if (edge != null
                        && Math.abs(edge.y - current.y) < 1e-7
                        && edge.y > current.y + mc.thePlayer.motionY + 1e-7
                        && Math.abs(edge.x - current.x - mx) < 1e-7
                        && Math.abs(edge.z - current.z - mz) < 1e-7
                        && Math.abs(mx * dz - mz * dx) * friction <= .025
                        && SprintGap.planAfterGroundedStep(
                                        live,
                                        area,
                                        candidate,
                                        current,
                                        edge,
                                        new Vec3Point(mx, mc.thePlayer.motionY, mz),
                                        (mx * dx + mz * dz) * friction,
                                        acceleration,
                                        mc.thePlayer.jumpMovementFactor * .98,
                                        friction)
                                != null) {
                    gapEdgeFrom = current;
                    gapEdgeDelta = new Vec3Point(mx, mc.thePlayer.motionY, mz);
                    gapEdgeAt = tick;
                    activity = "Taking final grounded step for gap launch";
                    return new Step(Status.MOVING, heading, false, false, true, null);
                }
            }
            gapRunReady = false;
            activity = "Resetting checked gap-jump run-up";
            return new Step(Status.MOVING, heading, false, true);
        }
        activity = "Building sprint speed for gap jump";
        return new Step(Status.MOVING, heading, false, false, true, null);
    }

    boolean onSafeGround(PitRegion area) {
        return mc.thePlayer != null && mc.thePlayer.onGround && standable(area, self());
    }

    Vec3Point committedGoal() {
        return plannedGoal;
    }

    boolean safeCombatStep(Vec3Point from, Vec3Point to, PitRegion pit) {
        return geometry.walkEnd(from, to, pit) != null;
    }

    boolean safeCombatInput(int forward, int side, boolean sprint, boolean sneak, PitRegion pit) {
        double speed =
                mc.thePlayer.getAIMoveSpeed()
                        / (mc.thePlayer.isSprinting() ? 1.3 : 1)
                        * (sprint ? 1.3 : 1);
        return GroundMotion.safe(
                geometry.cached(),
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

    NearbySteering.Input combatBrake(PitRegion pit) {
        NearbySteering.Input best = NearbySteering.Input.NONE;
        double score = Double.POSITIVE_INFINITY;
        double angle = Math.toRadians(mc.thePlayer.rotationYaw);
        for (NearbySteering.Input input : NearbySteering.Input.values()) {
            double length = Math.max(1, Math.hypot(input.forward, input.side));
            double opposition =
                    ((-Math.sin(angle) * input.forward + Math.cos(angle) * input.side)
                                            * mc.thePlayer.motionX
                                    + (Math.cos(angle) * input.forward
                                                    + Math.sin(angle) * input.side)
                                            * mc.thePlayer.motionZ)
                            / length;
            if (opposition < score
                    && safeCombatInput(input.forward, input.side, false, true, pit)) {
                best = input;
                score = opposition;
            }
        }
        return best;
    }

    boolean autoStep(Vec3Point from, Vec3Point to, PitRegion area) {
        return geometry.cached().autoStepClear(from, to, area);
    }

    boolean combatHopPossible(
            CollisionGeometry.Box target,
            PitRegion area,
            java.util.function.BiPredicate<Vec3Point, Vec3Point> clearRay) {
        Vec3Point current = self();
        BlockPos below = new BlockPos(current.x, current.y - 1, current.z);
        if (!mc.thePlayer.onGround
                || !mc.theWorld.isBlockLoaded(below)
                || mc.thePlayer.isUsingItem()
                || mc.thePlayer.isPotionActive(Potion.jump)) return false;
        double friction = mc.theWorld.getBlockState(below).getBlock().slipperiness * .91;
        return CombatHop.possible(
                geometry.cached(),
                area,
                current,
                mc.thePlayer.getEyeHeight(),
                target,
                mc.thePlayer.motionX,
                mc.thePlayer.motionZ,
                friction,
                clearRay);
    }

    boolean combatHopSafe(Vec3Point origin, PitRegion area) {
        return CombatHop.safeFlight(
                geometry.cached(),
                area,
                origin,
                self(),
                mc.thePlayer.motionX,
                mc.thePlayer.motionY,
                mc.thePlayer.motionZ);
    }

    boolean safeWalkingInput(Vec3Point next, PitRegion area) {
        return geometry.cached().walkEnd(self(), next, area) != null;
    }

    NearbySteering.Input correctionInput(Vec3Point goal, PitRegion area) {
        Vec3Point current = self();
        CollisionGeometry live = geometry.cached();
        BlockPos below = new BlockPos(current.x, current.y - 1, current.z);
        if (!mc.theWorld.isBlockLoaded(below)) return NearbySteering.Input.NONE;
        double friction = mc.theWorld.getBlockState(below).getBlock().slipperiness * .91;
        double speed = mc.thePlayer.getAIMoveSpeed() / (mc.thePlayer.isSprinting() ? 1.3 : 1);
        double acceleration = speed * .16277136 / (friction * friction * friction);
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
                            mc.thePlayer.isUsingItem() ? .2 : 1);
            Vec3Point first =
                    live.walkEnd(
                            current,
                            new Vec3Point(current.x + delta.x, current.y, current.z + delta.z),
                            area);
            if (first == null || Math.abs(first.y - goal.y) > .6) continue;
            Vec3Point nextDelta =
                    GroundMotion.delta(
                            delta.x * friction,
                            delta.z * friction,
                            mc.thePlayer.rotationYaw,
                            input.forward,
                            input.side,
                            acceleration,
                            mc.thePlayer.isUsingItem() ? .2 : 1);
            Vec3Point second =
                    live.walkEnd(
                            first,
                            new Vec3Point(first.x + nextDelta.x, first.y, first.z + nextDelta.z),
                            area);
            if (second == null || Math.abs(second.y - goal.y) > .6) continue;
            double value =
                    first.horizontalDistanceSq(goal) * .35
                            + second.horizontalDistanceSq(goal)
                            + (input == NearbySteering.Input.NONE ? .002 : 0);
            if (value < score) {
                score = value;
                best = input;
            }
        }
        return best;
    }

    boolean precise(Step step, PitRegion area) {
        return step.alignOnly
                || step.jump
                || step.brake
                || jumpInProgress()
                || preparingGap()
                || !mc.thePlayer.onGround
                || (index + 1 < path.size()
                        && !autoStep(pathPoint(index), pathPoint(index + 1), area))
                || (step.waypoint.y < mc.thePlayer.posY - .6
                        && !autoStep(self(), step.waypoint, area))
                || (!path.isEmpty()
                        && index > 0
                        && index < path.size()
                        && plannedTerrain.gap(pathPoint(index - 1), pathPoint(index)));
    }

    Step sprintJump(Vec3Point goal, PitRegion area, boolean chasing) {
        if (!OptimusMod.settings.parkourEnabled
                || jumpInProgress()
                || preparingGap()
                || !mc.thePlayer.onGround
                || !mc.thePlayer.isSprinting()
                || mc.thePlayer.hurtTime > 0
                || mc.thePlayer.isCollidedHorizontally
                || mc.thePlayer.isPotionActive(Potion.jump)
                || mc.thePlayer.isPotionActive(Potion.blindness)) return null;
        Vec3Point current = self();
        CollisionGeometry live = geometry.cached();
        boolean corridor = hintedPath && plannedTerrain != null && index < path.size();
        if (corridor) {
            index =
                    WalkSteering.shortcut(
                            index,
                            path.size(),
                            current,
                            this::pathPoint,
                            p -> live.autoStepClear(current, p, area));
            Vec3Point next = pathPoint(index);
            if (!live.autoStepClear(current, next, area)) return null;
            // The route may run away from the final target to reach stairs. A
            // travel jump follows this checked segment, stopping before its turn.
            goal = next;
        }
        double distance = Math.sqrt(current.horizontalDistanceSq(goal));
        if (distance < (!corridor && chasing ? 7 : 6)) return null;
        double dx = (goal.x - current.x) / distance, dz = (goal.z - current.z) / distance;
        float angle = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (Math.abs(MathHelper.wrapAngleTo180_float(angle - mc.thePlayer.rotationYaw)) > 5)
            return null;
        double speed = mc.thePlayer.motionX * dx + mc.thePlayer.motionZ * dz;
        if (Math.abs(mc.thePlayer.motionX * dz - mc.thePlayer.motionZ * dx) > .025) return null;
        BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1, mc.thePlayer.posZ);
        if (!mc.theWorld.isBlockLoaded(below)) return null;
        double friction = mc.theWorld.getBlockState(below).getBlock().slipperiness * .91;
        double groundAcceleration =
                mc.thePlayer.getAIMoveSpeed() * .16277136 / (friction * friction * friction) * .98;
        SprintJump.Plan plan =
                SprintJump.plan(
                        live,
                        area,
                        current,
                        goal,
                        speed,
                        groundAcceleration,
                        mc.thePlayer.jumpMovementFactor * .98,
                        friction,
                        !corridor && chasing);
        return plan == null
                ? null
                : new Step(
                        Status.MOVING,
                        new Vec3Point(current.x + dx * 3, current.y, current.z + dz * 3),
                        true,
                        false,
                        true,
                        plan);
    }

    Vec3Point pursuitDestination(Vec3Point target, boolean airborne, PitRegion pit) {
        return geometry.cached().pursuitDestination(target, airborne, pit);
    }

    boolean directPursuit(Vec3Point from, Vec3Point target, PitRegion pit, double range) {
        CollisionGeometry live = geometry.cached();
        return mc.thePlayer.onGround
                && live.pursuitClear(from, target, pit, range)
                && (Math.abs(from.y - target.y) <= .6 || live.autoStepClear(from, target, pit));
    }

    boolean safeKnockbackStep(Vec3Point from, Vec3Point to, PitRegion pit) {
        CollisionGeometry live = geometry.cached();
        Vec3Point clipped =
                live.jumpMove(
                        from, new Vec3Point(to.x - from.x, to.y - from.y, to.z - from.z), pit);
        return clipped != null && live.knockbackControlClear(from, clipped, pit);
    }

    private boolean goalReached(
            Vec3Point point, Vec3Point destination, boolean target, PitRegion area) {
        double tolerance = target ? 2.0 : 0.45;
        if (point.horizontalDistanceSq(destination) > tolerance * tolerance
                || Math.abs(point.y - destination.y) > (target ? .6 : .15)) return false;
        if (target && !geometry.cached().autoStepClear(point, destination, area)) return false;
        return !target
                || mc.theWorld.rayTraceBlocks(
                                new Vec3(point.x, point.y + mc.thePlayer.getEyeHeight(), point.z),
                                new Vec3(
                                        destination.x,
                                        destination.y + mc.thePlayer.getEyeHeight(),
                                        destination.z))
                        == null;
    }

    Step step(final Vec3Point destination, final PitRegion area, final boolean target, long tick) {
        decisionTick = tick;
        Vec3Point current = self();
        boolean landedJump = false;
        if (!within(area, current) || !area.contains(destination))
            return failed("Outside current travel/pursuit bounds");
        if (advanced.active()) {
            boolean groundedSetup = advanced.groundedSetup(tick);
            if (groundedSetup
                    && plannedGoal != null
                    && (plannedGoal.horizontalDistanceSq(destination) > 9
                            || Math.abs(plannedGoal.y - destination.y) > .6)
                    && !hintStillUseful(destination, area))
                return abandonHint("Opponent moved; refreshing hinted route");
            if (groundedSetup && hintStillUseful(destination, area)) {
                plannedGoal = destination;
                planUpdatedAt = tick;
            }
            Step special = hintStep(area, tick);
            if (special != null) return special;
            landedJump = true;
        }
        if (platformLeap != null) {
            PlatformJump.Plan plan = platformLeap;
            Vec3Point heading =
                    new Vec3Point(current.x + plan.dx * 3, current.y, current.z + plan.dz * 3);
            if (tick - platformStarted > 35) return failed("Platform jump timed out");
            if (!mc.thePlayer.onGround) {
                platformAirborne = true;
                boolean brake = tick - platformStarted >= plan.forwardTicks;
                if (PlatformJump.projected(
                                geometry.cached(),
                                area,
                                plan,
                                current,
                                mc.thePlayer.motionX,
                                mc.thePlayer.motionY,
                                mc.thePlayer.motionZ,
                                mc.thePlayer.jumpMovementFactor * .98,
                                brake)
                        == null) return failed("Platform jump corridor changed");
                activity = "Jumping toward platform";
                return new Step(Status.MOVING, heading, false, brake);
            }
            if (!platformAirborne) {
                if (tick - platformStarted > 5) return failed("Platform jump did not start");
                return new Step(Status.MOVING, heading, true);
            }
            if (!standable(area, current)
                    || Math.abs(current.y - plan.landing.y) > .15
                    || current.horizontalDistanceSq(plan.landing) > .75 * .75)
                return failed("Platform jump missed supported landing");
            double speed = mc.thePlayer.motionX * plan.dx + mc.thePlayer.motionZ * plan.dz;
            if (Math.abs(speed) > .06) {
                activity = "Settling platform landing";
                return new Step(Status.MOVING, heading, false, speed > 0);
            }
            platformLeap = null;
            platformAirborne = false;
            progress = null;
            landedJump = true;
        }
        if (!jumpInProgress() && mc.thePlayer.onGround && escapeSteering.recovering(tick)) {
            NearbySteering.Input input =
                    escapeSteering.choose(
                            geometry.cached(),
                            area,
                            current,
                            destination,
                            mc.thePlayer.rotationYaw,
                            tick);
            activity = "Backing out of blocked corner";
            if (input != NearbySteering.Input.NONE)
                return new Step(input, input.point(current, mc.thePlayer.rotationYaw, .48));
            return new Step(Status.WAITING, null, false);
        }
        if (gapLeap != null) {
            SprintGap.Candidate candidate = gapLeap.candidate;
            Vec3Point heading =
                    new Vec3Point(
                            current.x + candidate.dx * 3, current.y, current.z + candidate.dz * 3);
            if (tick - sprintGapAt > 35) return failed("Sprint gap jump timed out");
            if (!mc.thePlayer.onGround) {
                gapAirborne = true;
                double airInput = mc.thePlayer.jumpMovementFactor * .98;
                Vec3Point next =
                        new Vec3Point(
                                current.x + mc.thePlayer.motionX + candidate.dx * airInput,
                                current.y + mc.thePlayer.motionY,
                                current.z + mc.thePlayer.motionZ + candidate.dz * airInput);
                if (!SprintGap.continuing(geometry.cached(), area, gapLeap, current, next))
                    return failed("Sprint-gap corridor changed");
                activity = "Sprint jumping " + candidate.emptyBlocks + "-block gap";
                return new Step(Status.MOVING, heading, true, false, true, null);
            }
            if (!gapAirborne) {
                if (tick - sprintGapAt > 5) return failed("Sprint gap jump did not start");
                return new Step(Status.MOVING, heading, true, false, true, null);
            }
            if (!standable(area, current) || Math.abs(current.y - candidate.landing.y) > .35)
                return failed("Sprint gap landing missed");
            double speed =
                    mc.thePlayer.motionX * candidate.dx + mc.thePlayer.motionZ * candidate.dz;
            if (speed > .06) {
                activity = "Braking supported gap landing";
                return new Step(Status.MOVING, heading, false, true);
            }
            gapLeap = null;
            gapAirborne = false;
            progress = null;
            landedJump = true;
        }
        if (blockLanding != null) {
            if (tick - blockStarted > 30
                    || !standable(area, blockLanding)
                    || !terrain(geometry.cached(), area).edgeClear(blockStart, blockLanding))
                return failed("Block-jump landing changed");
            if (!mc.thePlayer.onGround) {
                blockAirborne = true;
                double distance = Math.sqrt(current.horizontalDistanceSq(blockLanding));
                if (distance < .3) {
                    double motion =
                            Math.sqrt(
                                    mc.thePlayer.motionX * mc.thePlayer.motionX
                                            + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
                    if (motion > .03)
                        return new Step(
                                Status.MOVING,
                                new Vec3Point(
                                        current.x + mc.thePlayer.motionX / motion,
                                        current.y,
                                        current.z + mc.thePlayer.motionZ / motion),
                                false,
                                true);
                    return new Step(Status.WAITING, null, false);
                }
                Vec3Point next =
                        new Vec3Point(
                                current.x + (blockLanding.x - current.x) / distance * .15,
                                current.y,
                                current.z + (blockLanding.z - current.z) / distance * .15);
                // A rising body can contact the block's side until it clears the top.
                // Vanilla collision resolves that contact; the lift/corridor was checked before
                // launch.
                if (!within(area, next)) return failed("Block jump left its allowed area");
                return new Step(Status.MOVING, blockLanding, false);
            }
            if (!blockAirborne) {
                if (tick - blockStarted > 5) return failed("Block jump did not start");
                return new Step(Status.MOVING, blockLanding, true);
            }
            if (current.horizontalDistanceSq(blockLanding) > .64
                    || Math.abs(current.y - blockLanding.y) > .35
                    || !standable(area, current))
                return failed("Block jump missed supported landing");
            blockLanding = null;
            blockAirborne = false;
            progress = null;
            landedJump = true;
        }
        if (sprintLeap != null) {
            if (!mc.thePlayer.onGround) {
                leapAirborne = true;
                double airInput = mc.thePlayer.jumpMovementFactor * .98;
                Vec3Point motion =
                        new Vec3Point(
                                mc.thePlayer.motionX + sprintLeap.dx * airInput,
                                mc.thePlayer.motionY,
                                mc.thePlayer.motionZ + sprintLeap.dz * airInput);
                CollisionGeometry live = geometry.cached();
                Vec3Point next = live.jumpMove(current, motion, area);
                // The landing tick clips downward motion against the floor. An
                // unclipped prediction incorrectly treats every normal landing
                // as a new obstruction and discards the route just before it.
                if (tick - leapStarted > 30
                        || next == null
                        || Math.abs(next.x - current.x - motion.x) > 1e-6
                        || Math.abs(next.z - current.z - motion.z) > 1e-6
                        || !SprintJump.continuing(live, area, sprintLeap, current, next))
                    return failed("Sprint-jump corridor changed");
                return new Step(
                        Status.MOVING,
                        new Vec3Point(
                                current.x + sprintLeap.dx * 3,
                                current.y,
                                current.z + sprintLeap.dz * 3),
                        false,
                        false,
                        true,
                        null);
            }
            if (!leapAirborne) {
                if (tick - leapStarted > 5) return failed("Sprint jump did not start");
                return new Step(
                        Status.MOVING,
                        new Vec3Point(
                                current.x + sprintLeap.dx * 3,
                                current.y,
                                current.z + sprintLeap.dz * 3),
                        true,
                        false,
                        true,
                        null);
            }
            if (!standable(area, current)) return failed("Sprint jump missed supported ground");
            sprintLeap = null;
            leapAirborne = false;
            progress = null;
            landedJump = true;
        }
        if (recoverRoute && !jumpInProgress()) {
            path = Collections.emptyList();
            search = null;
            retryAt = 0;
            localRetryAt = 0;
            recoverRoute = false;
            activity = "Recovering blocked corner";
            progress = current;
            progressAt = tick;
        }
        if (!gapCommitted
                && mc.thePlayer.onGround
                && standable(area, current)
                && goalReached(current, destination, target, area)) {
            reset();
            return new Step(Status.ARRIVED, null, false);
        }
        if (progress == null
                || current.horizontalDistanceSq(progress) > 0.16
                || Math.abs(current.y - progress.y) > 0.5) {
            progress = current;
            progressAt = tick;
        } else if (search == null && !path.isEmpty() && tick - progressAt > (target ? 20 : 80))
            return failed(
                    target
                            ? "Pursuit obstructed; refreshing route"
                            : "Path obstructed or stuck for four seconds");
        if (!mc.thePlayer.onGround) {
            wasAirborne = true;
            if (airborneAt == 0) airborneAt = tick;
            if (tick - airborneAt > 60) return failed("No supported landing");
            // Continue an already validated one-block jump/drop; never plan while airborne.
            if (!path.isEmpty() && index < path.size()) {
                Vec3Point landing = pathPoint(index);
                if (!standable(area, landing)) return failed("Landing became unsafe during flight");
                boolean gap = index > 0 && plannedTerrain.gap(pathPoint(index - 1), landing);
                boolean deepFall = index > 0 && pathPoint(index - 1).y - landing.y > 3;
                if (deepFall
                        && !OptimusMod.settings.hypixelPit.containsBody(
                                pathPoint(index - 1), .3, 1.8)
                        && !DropGate.insideFallCorridor(
                                OptimusMod.settings.dropRim,
                                OptimusMod.settings.dropFloor,
                                current))
                    return failed("Left the authorized pit-entry fall corridor");
                if (gap && !gapCommitted) return failed("Unplanned fall before parkour takeoff");
                if ((gap || deepFall) && current.horizontalDistanceSq(landing) < 0.36) {
                    double motion =
                            Math.sqrt(
                                    mc.thePlayer.motionX * mc.thePlayer.motionX
                                            + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
                    if (motion > 0.02)
                        return new Step(
                                Status.MOVING,
                                new Vec3Point(
                                        current.x + mc.thePlayer.motionX / motion,
                                        current.y,
                                        current.z + mc.thePlayer.motionZ / motion),
                                false,
                                true);
                    return new Step(Status.WAITING, null, false);
                }
                if (current.horizontalDistanceSq(landing) < 0.09)
                    return new Step(Status.WAITING, null, false);
                double distance = Math.sqrt(current.horizontalDistanceSq(landing));
                Vec3Point next =
                        new Vec3Point(
                                current.x + (landing.x - current.x) / distance * 0.22,
                                current.y,
                                current.z + (landing.z - current.z) / distance * 0.22);
                if (!within(area, next) || !free(body(current).union(body(next))))
                    return new Step(Status.WAITING, null, false);
                return new Step(Status.MOVING, landing, false);
            }
            return new Step(Status.WAITING, null, false);
        }
        airborneAt = 0;
        if (gapCommitted) {
            if (!wasAirborne && tick - gapStarted > 5) return failed("Parkour jump did not start");
            if (wasAirborne) {
                Vec3Point landing = pathPoint(index);
                if (current.horizontalDistanceSq(landing) > 0.64
                        || Math.abs(current.y - landing.y) > 0.35)
                    return failed("Parkour landing missed");
                index++;
                gapCommitted = wasAirborne = false;
                landedJump = true;
            }
        }
        if (landedJump) advanceGroundCursor(current, area);
        if (target && hintedPath && hintStillUseful(destination, area)) {
            plannedGoal = destination;
            planUpdatedAt = tick;
        }
        boolean changed =
                targetMode != target
                        || plannedGoal == null
                        || (target
                                ? PursuitGoal.refresh(
                                        plannedGoal,
                                        destination,
                                        search != null,
                                        tick - planUpdatedAt)
                                : plannedGoal.horizontalDistanceSq(destination) > 1.0
                                        || Math.abs(plannedGoal.y - destination.y) > .5);
        boolean replan =
                !gapCommitted
                        && search == null
                        && (path.isEmpty() || index >= path.size() || changed);
        if (changed && !gapCommitted) {
            search = null;
            replan = true;
            retryAt = 0;
            // Keep a previously completed, still checked route while its replacement searches.
            if (!target || targetMode != target) path = Collections.emptyList();
        }
        if (replan) {
            if (tick < retryAt) return new Step(Status.WAITING, null, false);
            searchTerrain = terrain(geometry.cached(), area);
            final GroundRoute route = searchTerrain;
            searchHints = target && OptimusMod.routeHintsEnabled ? OptimusMod.routeHints : null;
            final java.util.function.ToDoubleFunction<Node> cachedEstimate =
                    searchHints == null
                            ? n -> Double.NaN
                            : searchHints.estimateTo(
                                    destination,
                                    area,
                                    OptimusMod.settings.parkourEnabled
                                            && !mc.thePlayer.isPotionActive(Potion.jump),
                                    key -> rejectedHints.getOrDefault(key, 0L) > decisionTick);
            GridPathfinder.Goal goal =
                    new GridPathfinder.Goal() {
                        public boolean reached(Node n) {
                            return goalReached(route.point(n), destination, target, area);
                        }

                        public double estimate(Node n) {
                            Vec3Point p = route.point(n);
                            double known = cachedEstimate.applyAsDouble(n);
                            if (Double.isFinite(known)) return known * 1.05;
                            // Favor progress in this obstacle-dense map without charging
                            // diagonal movement its old Manhattan distance. This is a
                            // weighted search, not a guarantee of the globally shortest route.
                            return 1.2
                                            * Math.max(
                                                    0,
                                                    Math.sqrt(p.horizontalDistanceSq(destination))
                                                            - (target ? 2 : 0))
                                    + Math.max(0, Math.abs(p.y - destination.y) - .6) * .2;
                        }
                    };
            Node start = searchTerrain.start(current);
            plannedGoal = destination;
            targetMode = target;
            planUpdatedAt = tick;
            if (start == null) {
                retryAt = tick + 20;
                return failed("Waiting for safe ground alignment");
            }
            GridPathfinder.Terrain choices =
                    searchHints == null
                            ? route
                            : searchHints.augment(
                                    route,
                                    geometry.cached(),
                                    area,
                                    OptimusMod.settings.parkourEnabled
                                            && !mc.thePlayer.isPotionActive(Potion.jump),
                                    key -> rejectedHints.getOrDefault(key, 0L) > decisionTick);
            // Hints are optional edges in this search, not a route installed
            // before ordinary walking, drops and live connections can compete.
            if (path.isEmpty() || index >= path.size()) {
                hintedPath = false;
                pathHints = null;
            }
            search =
                    new GridPathfinder.Search(
                            start, choices, goal, extendedSearch ? 131072 : 65536);
        }
        if (search != null) {
            GridPathfinder.State state = search.advance(256, 5000000L);
            progress = current;
            progressAt = tick;
            activity = "Finding path: " + search.visited() + " cells";
            if (state == GridPathfinder.State.SEARCHING) {
                if ((path.isEmpty() || index >= path.size())
                        && target
                        && tick >= localRetryAt
                        && (searchHints == null || destination.y > current.y + .6)) {
                    GroundRoute localTerrain = terrain(geometry.cached(), area);
                    List<Node> local = LocalApproach.route(localTerrain, current, destination);
                    localRetryAt = tick + 5;
                    if (!local.isEmpty()) {
                        path = local;
                        plannedTerrain = localTerrain;
                        index = 0;
                        activity = "Following checked local approach";
                    }
                }
                if (path.isEmpty() || index >= path.size())
                    return new Step(Status.WAITING, null, false);
            } else {
                if (state != GridPathfinder.State.FOUND) {
                    retryAt = tick + (target ? 10 : 40);
                    return failed("Route " + state.toString().toLowerCase(Locale.ROOT));
                }
                path = search.path();
                plannedTerrain = searchTerrain;
                search = null;
                index = 0;
                planUpdatedAt = tick;
                pathHints = searchHints;
                hintedPath = pathHints != null && pathHints.uses(path);
                searchHints = null;
                if (!hintedPath) pathHints = null;
                // Local progress may have moved us past the old search origin. Connect
                // only to a reachable ground node; never jump to a disconnected suffix.
                CollisionGeometry currentGeometry = geometry.cached();
                for (int i = 0; i < path.size(); i++) {
                    Vec3Point point = pathPoint(i);
                    if (current.horizontalDistanceSq(point) <= 4
                            && currentGeometry.autoStepClear(current, point, area)) index = i;
                }
                activity = "Following " + path.size() + " waypoints";
            }
        }
        advanceGroundCursor(current, area);
        if (index >= path.size()) {
            path = Collections.emptyList();
            return new Step(Status.WAITING, null, false);
        }
        if (search == null)
            activity =
                    (hintedPath ? "Following hinted corridor: " : "Following ")
                            + path.size()
                            + " waypoints";
        if (hintedPath && gapPreparing == null) {
            CollisionGeometry live = geometry.cached();
            index =
                    WalkSteering.shortcut(
                            index,
                            path.size(),
                            current,
                            this::pathPoint,
                            p -> live.autoStepClear(current, p, area));
            Vec3Point walkTo =
                    live.autoStepClear(current, destination, area) ? destination : pathPoint(index);
            if (live.autoStepClear(current, walkTo, area)) {
                // A saved incoming jump is unnecessary when this landing can be
                // reached on foot from our actual position. Keep its metadata
                // for routes that still require it; only bypass the old prefix.
                boolean sprint =
                        TravelSprint.allowed(live, area, current, walkTo, destination, target);
                return new Step(Status.MOVING, walkTo, false, false, sprint, null);
            }
        }
        Vec3Point next = pathPoint(index);
        Vec3Point previous = index > 0 ? pathPoint(index - 1) : current;
        RouteHints.Jump special =
                hintedPath && index > 0
                        ? pathHints.jump(path.get(index - 1), path.get(index))
                        : null;
        if (special != null) {
            advanced.begin(special, tick);
            return hintStep(area, tick);
        }
        boolean gap = plannedTerrain.gap(previous, next);
        boolean deep = previous.y - next.y > 3;
        boolean drop = plannedTerrain.drop(previous, next);
        if (!standable(area, next)
                || !edgeClear(gap || deep || drop ? previous : current, next, area)) {
            // Reaching the waypoint radius is not enough to turn past a tight
            // obstacle from every WASD approach. Rejoin its checked corner first.
            if (!gap
                    && !deep
                    && !drop
                    && index > 0
                    && standable(area, previous)
                    && geometry.cached().autoStepClear(current, previous, area)
                    && edgeClear(previous, next, area)) {
                activity = "Aligning route corner";
                return new Step(Status.MOVING, previous, false);
            }
            if (hintedPath) return failed("Hinted corridor changed; using live search");
            path = Collections.emptyList();
            return new Step(Status.WAITING, null, false);
        }
        if (drop) {
            activity = "Walking down to lower landing";
            return new Step(Status.MOVING, next, false);
        }
        if (!autoStep(current, next, area)) {
            Step normal = prepareAdjacentClimb(next, area);
            if (normal != null) return normal;
        }
        if (!autoStep(current, next, area)
                && PlatformJump.candidate(current, next)
                && PlatformJump.possible(geometry.cached(), area, current, next)) {
            Step platform = preparePlatform(next, area, tick);
            if (platform != null) return platform;
            if (!preparation.allowed(current, next, tick))
                return new Step(Status.WAITING, null, false);
        }
        if (gap) {
            if (SprintGap.candidate(previous, next)) {
                Step preparation = nearbyGap(next, area, tick, true);
                // Keep the ultimate destination pinned while this edge commits its own landing.
                plannedGoal = destination;
                if (preparation == null) {
                    // An executable runway can disappear after the route was planned.
                    // Do not retain that same edge indefinitely without movement.
                    this.preparation.reject(current, next, tick);
                    path = Collections.emptyList();
                    search = null;
                    retryAt = 0;
                    activity = "Gap runway unavailable; choosing another route";
                }
                return preparation == null ? new Step(Status.WAITING, null, false) : preparation;
            }
            if (current.horizontalDistanceSq(previous) > 0.65 * 0.65 && !gapCommitted)
                return new Step(Status.MOVING, previous, false);
            double distance = Math.sqrt(previous.horizontalDistanceSq(next));
            double dx = (next.x - previous.x) / distance, dz = (next.z - previous.z) / distance;
            double speed = mc.thePlayer.motionX * dx + mc.thePlayer.motionZ * dz;
            if (speed > 0.24) return new Step(Status.MOVING, next, false, true);
            if (speed < 0.14 && !gapCommitted)
                return new Step(
                        Status.MOVING,
                        new Vec3Point(previous.x + dx * 0.25, previous.y, previous.z + dz * 0.25),
                        false);
            return new Step(Status.MOVING, next, true);
        }
        boolean walk = autoStep(current, next, area);
        if (walk) {
            // Skip grid zigzags only where every small sweep follows safe actual surfaces.
            // Advance the route cursor too: a diagonal shortcut can pass more than .5m
            // from an old grid corner, which passed-waypoint detection correctly rejects.
            CollisionGeometry currentGeometry = geometry.cached();
            index =
                    WalkSteering.lookAhead(
                            index,
                            path.size(),
                            current,
                            this::pathPoint,
                            candidate -> currentGeometry.autoStepClear(current, candidate, area));
            next = pathPoint(index);
        }
        boolean sprint =
                walk
                        && TravelSprint.allowed(
                                geometry.cached(), area, current, next, destination, target);
        return new Step(Status.MOVING, next, !walk && next.y - current.y > .6, false, sprint, null);
    }

    private void advanceGroundCursor(Vec3Point current, PitRegion area) {
        while (index < path.size()) {
            Vec3Point next = pathPoint(index), previous = index > 0 ? pathPoint(index - 1) : null;
            // The .55 waypoint radius is wider than the .45 arrival radius.
            // Keep a terminal destination until the actual supported arrival
            // check succeeds; otherwise a one-node route can repeat forever.
            if (index + 1 == path.size()
                    && plannedGoal != null
                    && goalReached(next, plannedGoal, targetMode, area)) break;
            boolean exact =
                    index + 1 < path.size()
                            && !autoStep(next, pathPoint(index + 1), area)
                            && !plannedTerrain.drop(next, pathPoint(index + 1));
            if (exact) {
                if (current.horizontalDistanceSq(next) >= .09
                        || Math.abs(current.y - next.y) >= .35) break;
            } else if ((current.horizontalDistanceSq(next) >= .55 * .55
                            || Math.abs(current.y - next.y) >= .6)
                    && !WalkSteering.passed(previous, next, current)) break;
            index++;
        }
    }

    void cancelHint(String reason) {
        failed(reason);
    }

    private Step abandonHint(String reason) {
        // The target changed; the terrain edge itself has not failed.
        hintedPath = false;
        hintRetryAt = decisionTick;
        return failed(reason);
    }

    private boolean sameGoalSurface(Vec3Point from, Vec3Point to, PitRegion area) {
        return from != null
                && to != null
                && Math.abs(from.y - to.y) <= .6
                && geometry.cached().autoStepClear(from, to, area);
    }

    private boolean hintStillUseful(Vec3Point destination, PitRegion area) {
        return hintedPath
                && plannedTerrain != null
                && index < path.size()
                && sameGoalSurface(pathPoint(path.size() - 1), destination, area);
    }

    private Step hintStep(PitRegion area, long tick) {
        Step step = advanced.step(geometry.cached(), area, tick);
        if (step == null) {
            advanced.reset();
            index++;
            progress = null;
        } else if (step.status == Status.FAILED) {
            return failed(advanced.failure());
        } else {
            if (advanced.activity() != null) activity = advanced.activity();
        }
        return step;
    }
}