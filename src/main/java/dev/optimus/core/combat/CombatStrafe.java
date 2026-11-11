package dev.optimus.core.combat;

import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.NearbySteering;
import java.util.function.Predicate;

/** Checked side choices with an actual minimum hold instead of per-tick rescoring. */
public final class CombatStrafe {
    private int preferred = 1;
    private int heldSide;
    private boolean held;
    private long holdUntil;

    public void reset() {
        preferred = 1;
        heldSide = 0;
        held = false;
        holdUntil = 0;
    }

    public NearbySteering.Input choose(
            long tick,
            Vec3Point self,
            Vec3Point target,
            Vec3Point targetDelta,
            float yaw,
            double motionX,
            double motionZ,
            int forward,
            boolean allowSide,
            Predicate<NearbySteering.Input> safe) {
        if (held && tick < holdUntil && (allowSide || heldSide == 0)) {
            for (NearbySteering.Input input : NearbySteering.Input.values())
                if (input.forward == forward && input.side == heldSide && safe.test(input))
                    return input;
        }
        NearbySteering.Input best = NearbySteering.Input.NONE;
        double score = Double.POSITIVE_INFINITY;
        boolean found = false;
        double angle = Math.toRadians(yaw);
        boolean lateralMotion =
                Math.abs(targetDelta.x * Math.cos(angle) + targetDelta.z * Math.sin(angle)) > .035;
        for (NearbySteering.Input input : NearbySteering.Input.values()) {
            if (input.forward != forward || !allowSide && input.side != 0 || !safe.test(input))
                continue;
            Vec3Point delta =
                    GroundMotion.delta(motionX, motionZ, yaw, input.forward, input.side, .1, 1);
            double dx = target.x + targetDelta.x * 2 - self.x - delta.x * 2;
            double dz = target.z + targetDelta.z * 2 - self.z - delta.z * 2;
            double distance = Math.hypot(dx, dz);
            double turn =
                    Math.abs(CombatTurn.wrap((float) Math.toDegrees(Math.atan2(-dx, dz)) - yaw));
            double cost = Math.abs(distance - 2.55) + turn / 180 * .25;
            if (allowSide && lateralMotion && input.side == 0) cost += .13;
            if (input.side != 0 && input.side != preferred) cost += .03;
            if (cost < score) {
                score = cost;
                best = input;
                found = true;
            }
        }
        held = found;
        heldSide = best.side;
        holdUntil = tick + 8;
        if (best.side != 0) preferred = best.side;
        return best;
    }
}