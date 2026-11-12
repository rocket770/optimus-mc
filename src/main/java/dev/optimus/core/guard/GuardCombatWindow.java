package dev.optimus.core.guard;

/** Track one assisted victim's observed damage and unacknowledged requests. */
public final class GuardCombatWindow {
    private static final int MAX_OBSERVED_WAIT_TICKS = 3;
    private String victim = "";
    private int previousHurt;
    private float previousHealth;
    private long retryAt, feedbackUntil;
    private boolean primed, awaitingDamage;

    public void reset() {
        victim = "";
        retryAt = 0;
        previousHurt = 0;
        previousHealth = 0;
        feedbackUntil = 0;
        primed = awaitingDamage = false;
    }

    public void awaitDamage(String uuid, long tick, int roundTripMillis, GuardSwingSource source) {
        if (victim.equals(uuid)) return;
        reset();
        victim = uuid;
        awaitingDamage = true;
        long feedback = feedbackAt(tick, roundTripMillis);
        // A server-observed swing has already crossed the outbound network leg.
        feedbackUntil =
                source == GuardSwingSource.SERVER_OBSERVED
                        ? Math.min(feedback, tick + MAX_OBSERVED_WAIT_TICKS)
                        : feedback;
    }

    public boolean observe(String uuid, int hurt, float health, long tick) {
        if (!victim.equals(uuid)) {
            reset();
            victim = uuid;
        }
        boolean damage = primed && (hurt > previousHurt || health < previousHealth - .01f);
        primed = true;
        previousHurt = hurt;
        previousHealth = health;
        if (hurt > 0) awaitingDamage = false;
        if (damage) retryAt = Math.min(retryAt, tick);
        return damage;
    }

    public boolean ready(long tick, int hurt, int roundTripMillis) {
        return (!awaitingDamage || tick >= feedbackUntil)
                && tick >= retryAt
                && GuardAssist.hitWindow(hurt, roundTripMillis);
    }

    public void attackSent(long tick, int roundTripMillis) {
        // Leave a network-update margin for an unacknowledged request;
        // actual damage feedback can release this wait earlier.
        retryAt = feedbackAt(tick, roundTripMillis);
    }

    private static long feedbackAt(long tick, int roundTripMillis) {
        // END can use the next logical tick before another physical update.
        return tick + Math.max(2, (Math.max(0, roundTripMillis) + 49) / 50 + 2);
    }
}