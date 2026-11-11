package dev.optimus.core.guard;

/** Track one assisted victim's observed damage and unacknowledged requests. */
public final class GuardCombatWindow {
    private String victim = "";
    private int previousHurt;
    private float previousHealth;
    private long retryAt;

    public void reset() {
        victim = "";
        retryAt = 0;
        previousHurt = 0;
        previousHealth = 0;
    }

    public boolean observe(String uuid, int hurt, float health, long tick) {
        if (!victim.equals(uuid)) {
            reset();
            victim = uuid;
            previousHurt = hurt;
            previousHealth = health;
            return false;
        }
        boolean damage = hurt > previousHurt || health < previousHealth - .01f;
        previousHurt = hurt;
        previousHealth = health;
        if (damage) retryAt = Math.min(retryAt, tick);
        return damage;
    }

    public boolean ready(long tick, int hurt, int roundTripMillis) {
        return tick >= retryAt && GuardAssist.hitWindow(hurt, roundTripMillis);
    }

    public void attackSent(long tick, int roundTripMillis) {
        // Leave a network-update margin for an unacknowledged request;
        // actual damage feedback can release this wait earlier.
        retryAt = tick + Math.max(2, (Math.max(0, roundTripMillis) + 49) / 50 + 2);
    }
}