package dev.optimus.core.guard;

import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.NearbySteering;

/**
 * Reconcile observed delayed positions against past input predictions, rather than pulling current
 * movement back toward the server's older position.
 */
public final class GuardInputPrediction {
    private final Vec3Point[] history = new Vec3Point[32];
    private final long[] times = new long[32];
    private final Vec3Point[] momenta = new Vec3Point[32];
    private final NearbySteering.Input[] inputs = new NearbySteering.Input[32];
    private final boolean[] runningHistory = new boolean[32], sneakingHistory = new boolean[32];
    private Vec3Point estimated, observed, motion = new Vec3Point(0, 0, 0);
    private long lastTick = Long.MIN_VALUE;
    private long lastMeasurementChange, lastInputChange;
    private boolean stalled;
    private NearbySteering.Input held = NearbySteering.Input.NONE;
    private float yaw;
    private boolean sprint, sneak;

    public void reset() {
        estimated = observed = null;
        motion = new Vec3Point(0, 0, 0);
        lastTick = Long.MIN_VALUE;
        stalled = false;
        java.util.Arrays.fill(history, null);
        java.util.Arrays.fill(momenta, null);
        java.util.Arrays.fill(inputs, null);
    }

    public Vec3Point update(
            Vec3Point measurement,
            NearbySteering.Input input,
            float heading,
            boolean running,
            boolean ducking,
            long tick,
            int delay) {
        if (estimated == null
                || tick < lastTick
                || tick - lastTick > 5
                || observed != null && observed.horizontalDistanceSq(measurement) > 16) {
            reset();
            estimated = measurement;
            lastTick = lastMeasurementChange = lastInputChange = tick;
        }
        boolean advanced = lastTick < tick,
                moved = observed != null && observed.horizontalDistanceSq(measurement) > 1e-8;
        if (moved) lastMeasurementChange = tick;
        if (input != held
                || Math.abs(CombatTurn.wrap(heading - yaw)) > 3
                || running != sprint
                || ducking != sneak) lastInputChange = tick;
        while (lastTick < tick) {
            Vec3Point delta =
                    stalled
                            ? new Vec3Point(0, 0, 0)
                            : GroundMotion.delta(
                                    motion.x,
                                    motion.z,
                                    yaw,
                                    held.forward,
                                    held.side,
                                    sprint ? .13 : .1,
                                    sneak ? .3 : 1);
            estimated = new Vec3Point(estimated.x + delta.x, measurement.y, estimated.z + delta.z);
            motion = new Vec3Point(delta.x * .546, 0, delta.z * .546);
            lastTick++;
            history[(int) (lastTick & 31)] = estimated;
            times[(int) (lastTick & 31)] = lastTick;
            momenta[(int) (lastTick & 31)] = motion;
            inputs[(int) (lastTick & 31)] = held;
            runningHistory[(int) (lastTick & 31)] = sprint;
            sneakingHistory[(int) (lastTick & 31)] = sneak;
        }
        // A newly observed turn belongs to the delayed sample. Replay the
        // already-known keys since that sample using the observed heading,
        // preserving world-space momentum instead of rotating it with view.
        if (delay > 0 && Math.abs(CombatTurn.wrap(heading - yaw)) >= 15)
            replayTurn(measurement, heading, tick, delay);
        stalled =
                input != NearbySteering.Input.NONE
                        && tick - Math.max(lastMeasurementChange, lastInputChange)
                                > Math.max(0, delay) + 2;
        if (stalled) {
            estimated = measurement;
            motion = new Vec3Point(0, 0, 0);
            java.util.Arrays.fill(history, null);
        } else if (observed != null && (advanced || moved)) {
            long source = tick - Math.max(0, Math.min(12, delay));
            int index = (int) (source & 31);
            Vec3Point previous = times[index] == source ? history[index] : null;
            if (previous != null) {
                double dx = measurement.x - previous.x, dz = measurement.z - previous.z;
                double scale = Math.min(.35, .16 / Math.max(.16, Math.hypot(dx, dz)));
                dx *= scale;
                dz *= scale;
                estimated = new Vec3Point(estimated.x + dx, measurement.y, estimated.z + dz);
                for (int i = 0; i < history.length; i++)
                    if (history[i] != null)
                        history[i] =
                                new Vec3Point(history[i].x + dx, history[i].y, history[i].z + dz);
            }
        }
        estimated = new Vec3Point(estimated.x, measurement.y, estimated.z);
        observed = measurement;
        held = input;
        yaw = heading;
        sprint = running;
        sneak = ducking;
        history[(int) (tick & 31)] = estimated;
        times[(int) (tick & 31)] = tick;
        momenta[(int) (tick & 31)] = motion;
        inputs[(int) (tick & 31)] = input;
        runningHistory[(int) (tick & 31)] = running;
        sneakingHistory[(int) (tick & 31)] = ducking;
        return estimated;
    }

    private void replayTurn(Vec3Point measurement, float heading, long tick, int delay) {
        long source = tick - Math.max(0, Math.min(12, delay));
        int index = (int) (source & 31);
        if (times[index] != source || history[index] == null || momenta[index] == null) return;
        Vec3Point at = measurement, velocity = momenta[index];
        history[index] = at;
        for (long frame = source + 1; frame <= tick; frame++) {
            int previous = (int) ((frame - 1) & 31), current = (int) (frame & 31);
            NearbySteering.Input input = inputs[previous] == null ? held : inputs[previous];
            Vec3Point delta =
                    GroundMotion.delta(
                            velocity.x,
                            velocity.z,
                            heading,
                            input.forward,
                            input.side,
                            runningHistory[previous] ? .13 : .1,
                            sneakingHistory[previous] ? .3 : 1);
            at = new Vec3Point(at.x + delta.x, measurement.y, at.z + delta.z);
            velocity = new Vec3Point(delta.x * .546, 0, delta.z * .546);
            history[current] = at;
            momenta[current] = velocity;
        }
        estimated = at;
        motion = velocity;
    }

    public Vec3Point nextMotion(
            NearbySteering.Input input, float heading, boolean running, boolean ducking) {
        if (stalled) return new Vec3Point(0, 0, 0);
        return GroundMotion.delta(
                motion.x,
                motion.z,
                heading,
                input.forward,
                input.side,
                running ? .13 : .1,
                ducking ? .3 : 1);
    }

    public boolean stalled() {
        return stalled;
    }
}