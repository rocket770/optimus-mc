package dev.optimus.core.combat;

import java.util.Random;

/** Short ordinary-key sprint resets after an observed response to a recent attack request. */
public final class CombatTap {
    public enum Input {
        NONE,
        W_RELEASE,
        S_BACK
    }

    private final Random random = new Random();
    private int previousHurt = -1;
    private long requested = Long.MIN_VALUE,
            start = Long.MIN_VALUE,
            end = Long.MIN_VALUE,
            next = Long.MIN_VALUE;
    private Input planned = Input.NONE;

    public void seed(long seed) {
        random.setSeed(seed);
        reset();
    }

    public void reset() {
        previousHurt = -1;
        requested = start = end = next = Long.MIN_VALUE;
        planned = Input.NONE;
    }

    public void cancel() {
        planned = Input.NONE;
        start = end = Long.MIN_VALUE;
    }

    public void attacked(long tick, boolean sprinting) {
        if (sprinting) requested = tick;
    }

    public void observe(
            int hurt,
            long tick,
            boolean eligible,
            double distance,
            boolean separating,
            boolean reacting) {
        boolean response = previousHurt >= 0 && hurt > previousHurt;
        previousHurt = hurt;
        if (!response
                || !eligible
                || requested == Long.MIN_VALUE
                || tick < requested
                || tick - requested > 8
                || tick < next
                || !Double.isFinite(distance)
                || distance < 1.25
                || distance > 3.05) return;
        // This is a likely response, not proof of which player caused damage.
        planned = !separating && distance < 2.05 ? Input.S_BACK : Input.W_RELEASE;
        start = tick + (reacting ? 0 : random.nextInt(3));
        end = start + 1 + (reacting ? 0 : random.nextInt(2));
        next = end + 10 + random.nextInt(7);
        requested = Long.MIN_VALUE;
    }

    public Input input(
            long tick, boolean eligible, double distance, boolean separating, boolean reacting) {
        if (!eligible
                || !Double.isFinite(distance)
                || distance > 3.1
                || (separating && distance > 2.5)) cancel();
        if (planned == Input.NONE || tick < start) return Input.NONE;
        if (tick >= end) {
            cancel();
            return Input.NONE;
        }
        if (planned == Input.S_BACK && separating) return Input.W_RELEASE;
        return planned;
    }
}