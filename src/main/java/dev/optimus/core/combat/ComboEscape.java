package dev.optimus.core.combat;

import dev.optimus.core.navigation.NearbySteering;
import java.util.Random;
import java.util.function.Predicate;

/** Short ordinary-key disengagements after repeated incoming hits, independent of click pacing. */
public final class ComboEscape {
    private final Random random = new Random();
    private long lastHit = Long.MIN_VALUE,
            lastCounter = Long.MIN_VALUE,
            lastAttack = Long.MIN_VALUE;
    private long start = Long.MIN_VALUE, end = Long.MIN_VALUE, next = Long.MIN_VALUE;
    private int hits, previousHurt = -1, backTicks, side = 1;

    public void seed(long seed) {
        random.setSeed(seed);
        reset();
    }

    public void reset() {
        lastHit = lastCounter = lastAttack = start = end = next = Long.MIN_VALUE;
        hits = 0;
        previousHurt = -1;
        backTicks = 0;
        side = 1;
    }

    public void attacked(long tick) {
        lastAttack = tick;
    }

    public void observe(
            long tick, boolean incoming, int targetHurt, boolean eligible, double distance) {
        boolean counter =
                previousHurt >= 0
                        && targetHurt > previousHurt
                        && lastAttack != Long.MIN_VALUE
                        && tick - lastAttack <= 8;
        previousHurt = targetHurt;
        if (!eligible || distance > 4.5) {
            hits = 0;
            lastHit = lastCounter = lastAttack = Long.MIN_VALUE;
            start = end = Long.MIN_VALUE;
            return;
        }
        // A request alone is not a landed counter. A nearby observed hurt rise is
        // only a heuristic in a multi-player pit, so it raises the trigger to three
        // incoming hits rather than indefinitely suppressing defensive movement.
        if (counter) lastCounter = tick;
        if (!incoming) return;
        hits = lastHit != Long.MIN_VALUE && tick - lastHit <= 18 ? Math.min(3, hits + 1) : 1;
        lastHit = tick;
        int threshold = lastCounter != Long.MIN_VALUE && tick - lastCounter <= 12 ? 3 : 2;
        if (hits < threshold || tick < next || tick < end) return;
        start = tick + 1 + random.nextInt(3);
        backTicks = 2 + random.nextInt(3);
        end = start + 6 + random.nextInt(5);
        next = end + 16 + random.nextInt(9);
        side = random.nextBoolean() ? 1 : -1;
        hits = 0;
    }

    public boolean responding(long tick) {
        return tick >= start && tick < end;
    }

    public NearbySteering.Input input(
            long tick, double distance, Predicate<NearbySteering.Input> safe) {
        if (!responding(tick)) return NearbySteering.Input.NONE;
        boolean back = tick < start + backTicks && distance <= 2.8;
        NearbySteering.Input same =
                side > 0 ? NearbySteering.Input.LEFT : NearbySteering.Input.RIGHT;
        NearbySteering.Input opposite =
                side > 0 ? NearbySteering.Input.RIGHT : NearbySteering.Input.LEFT;
        NearbySteering.Input diagonal =
                side > 0 ? NearbySteering.Input.BACK_LEFT : NearbySteering.Input.BACK_RIGHT;
        NearbySteering.Input otherDiagonal =
                side > 0 ? NearbySteering.Input.BACK_RIGHT : NearbySteering.Input.BACK_LEFT;
        NearbySteering.Input[] choices =
                back
                        ? new NearbySteering.Input[] {
                            diagonal, same, opposite, otherDiagonal, NearbySteering.Input.BACK
                        }
                        : new NearbySteering.Input[] {same, opposite};
        for (NearbySteering.Input choice : choices)
            if (safe.test(choice)) {
                // If terrain forces the other side, keep it instead of alternating
                // every frame. Every subsequent frame still rechecks actual momentum.
                if (choice.side != 0) side = choice.side;
                return choice;
            }
        return NearbySteering.Input.NONE;
    }
}