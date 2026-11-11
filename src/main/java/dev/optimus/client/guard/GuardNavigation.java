package dev.optimus.client.guard;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;

/** Guard-specific geometry queries; route execution remains with the navigation controller. */
public interface GuardNavigation {
    void beginGuardQueries();

    void endGuardQueries();

    void reset();

    boolean jumpInProgress();

    boolean preparingGap();

    boolean travelJumpInProgress();

    Vec3Point guardDestination(Vec3Point rear, Vec3Point leader, boolean airborne, PitRegion pit);

    Vec3Point guardPrediction(Vec3Point from, Vec3Point predicted, PitRegion pit);

    boolean guardPredictionClear(Vec3Point from, Vec3Point to, PitRegion area);

    boolean guardDrop(Vec3Point from, Vec3Point to, PitRegion pit);

    boolean autoStep(Vec3Point from, Vec3Point to, PitRegion area);

    Vec3Point pursuitDestination(Vec3Point target, boolean airborne, PitRegion pit);

    boolean safeGuardInput(
            NearbySteering.Input input, boolean sprint, boolean sneak, PitRegion pit);

    boolean safeGuardJump(NearbySteering.Input input, boolean sprint, PitRegion pit);

    boolean safeCombatInput(int forward, int side, boolean sprint, boolean sneak, PitRegion pit);

    boolean safeKnockbackStep(Vec3Point from, Vec3Point to, PitRegion pit);
}