package dev.optimus.core.combat;

/** Real attacks keep their own pacing; a nearby air swing cannot delay a valid hit. */
public final class CombatClicks {
    public enum Action {
        NONE,
        AIR_SWING,
        ATTACK
    }

    public static final double ATTACK_REACH = 2.75, SWING_REACH = 3.5;
    private long nextAttack, nextSwing, lastAction = Long.MIN_VALUE;

    public void defer(long tick, int delay) {
        nextAttack = nextSwing = tick + Math.max(1, delay);
        lastAction = Long.MIN_VALUE;
    }

    public long remaining(long tick) {
        return Math.max(0, nextAttack - tick);
    }

    public boolean ready(long tick) {
        return tick >= nextAttack;
    }

    /** Call only with fresh context, visibility and ray checks; no packets are sent here. */
    public Action choose(long tick, boolean allowed, boolean hit, boolean nearMiss) {
        if (!allowed || tick <= lastAction || !ready(tick)) return Action.NONE;
        if (hit) return Action.ATTACK;
        return nearMiss && tick >= nextSwing ? Action.AIR_SWING : Action.NONE;
    }

    public void performed(Action action, long tick, int attackDelay) {
        if (action == Action.NONE) return;
        lastAction = tick;
        nextSwing =
                tick + 4; // Five air swings/second at normal client tick rate, no catch-up burst.
        if (action == Action.ATTACK) nextAttack = tick + Math.max(1, attackDelay);
    }
}