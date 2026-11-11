package dev.optimus.core.combat;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Retain a valid selected target and notice damage on the first client tick that observes it. */
public final class CombatFocus<T> {
    private T target;
    private boolean followingDirect;
    private long reconsiderAt;
    private float previousHealth = Float.NaN;
    private int previousHurt;
    private long reactiveUntil = Long.MIN_VALUE;
    private long combatUntil = Long.MIN_VALUE;
    private long knockbackUntil = Long.MIN_VALUE;

    public void reset() {
        target = null;
        followingDirect = false;
        reconsiderAt = 0;
        previousHealth = Float.NaN;
        previousHurt = 0;
        reactiveUntil = combatUntil = knockbackUntil = Long.MIN_VALUE;
    }

    public void targetsChanged() {
        target = null;
        followingDirect = false;
        reconsiderAt = 0;
    }

    public double pursuitRange() {
        return followingDirect ? 12 : 8;
    }

    public void followingDirect(boolean value) {
        followingDirect = target != null && value;
    }

    public T select(
            Iterable<T> candidates, Predicate<T> allowed, ToDoubleFunction<T> distanceSquared) {
        return select(candidates, allowed, distanceSquared, false, 0);
    }
    /** All-target mode reconsiders the nearest opponent at least every four ticks. */
    public T select(
            Iterable<T> candidates,
            Predicate<T> allowed,
            ToDoubleFunction<T> distanceSquared,
            boolean nearestFirst,
            long tick) {
        T selected = null;
        double nearest = 48 * 48;
        double retained = Double.POSITIVE_INFINITY;
        for (T candidate : candidates) {
            if (!allowed.test(candidate)) continue;
            double distance = distanceSquared.applyAsDouble(candidate);
            if (candidate == target && distance < 48 * 48) {
                if (!nearestFirst) return target;
                retained = distance;
            }
            if (distance < nearest
                    || distance == nearest && distance < 48 * 48 && candidate == target) {
                nearest = distance;
                selected = candidate;
            }
        }
        // A short aim commitment avoids per-frame switches between crossing players.
        // A substantially closer threat, or an invalid old target, wins immediately.
        if (nearestFirst
                && selected != target
                && retained < 48 * 48
                && tick < reconsiderAt
                && Math.sqrt(retained) - Math.sqrt(nearest) < 1) return target;
        if (target != selected) {
            followingDirect = false;
            reconsiderAt = tick + 4;
        }
        target = selected;
        return target;
    }

    public boolean observeDamage(float health, int hurtTime, long tick) {
        boolean hit =
                (!Float.isNaN(previousHealth) && health < previousHealth - .001f)
                        || hurtTime > previousHurt;
        previousHealth = health;
        previousHurt = hurtTime;
        if (hit) {
            reactiveUntil = tick + 10;
            combatUntil = tick + 12;
            knockbackUntil = tick + 30;
        }
        return hit;
    }

    public boolean reacting(long tick) {
        return tick < reactiveUntil;
    }

    public void attacked(long tick) {
        combatUntil = tick + 12;
    }

    public boolean recentCombat(long tick) {
        return tick < combatUntil;
    }

    public boolean airborneResponse(long tick, boolean grounded) {
        return !grounded && tick < knockbackUntil;
    }
}