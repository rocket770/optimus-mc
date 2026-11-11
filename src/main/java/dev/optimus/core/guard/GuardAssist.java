package dev.optimus.core.guard;

/** One recent swing, never a backlog. Server hit acceptance remains authoritative. */
public final class GuardAssist {
    private String observed = "", victim = "";
    private long expires = Long.MIN_VALUE;
    private long retryAt = Long.MAX_VALUE;
    private boolean attempted, followup;

    public void reset() {
        observed = "";
        cancel();
    }

    public void cancel() {
        victim = "";
        expires = Long.MIN_VALUE;
        retryAt = Long.MAX_VALUE;
        attempted = followup = false;
    }

    public void observe(String event, String target, long tick) {
        if (event == null || event.isEmpty() || event.equals(observed)) return;
        observed = event;
        victim = target == null ? "" : target;
        expires = tick + 12;
        retryAt = Long.MAX_VALUE;
        attempted = followup = false;
    }

    public boolean live(long tick) {
        return tick <= expires;
    }

    public boolean pending(long tick) {
        return live(tick) && (!attempted || followup || tick >= retryAt);
    }

    public void damageObserved(long tick) {
        if (live(tick) && attempted) followup = true;
    }

    public void attackSent(long tick) {
        if (attempted) {
            cancel();
            return;
        }
        attempted = true;
        followup = false;
        expires = Math.max(expires, tick + 16);
    }

    public void attackSent(long tick, int roundTripMillis) {
        attackSent(tick);
        if (attempted) retryAt = tick + Math.max(2, (Math.max(0, roundTripMillis) + 49) / 50 + 2);
    }

    public String victim() {
        return victim;
    }

    public void retarget(String target) {
        String next = target == null ? "" : target;
        if (!victim.equals(next)) {
            attempted = followup = false;
            retryAt = Long.MAX_VALUE;
        }
        victim = next;
    }

    /**
     * Remote hurt animation starts at ten ticks on receipt. A round trip has elapsed by the time
     * our attack reaches the server. Preserve the full measured 250-ms round trip rather than
     * truncating it to three ticks.
     */
    public static boolean hitWindow(int observedHurtTicks, int roundTripMillis) {
        int compensation = Math.min(8, (Math.max(0, roundTripMillis) + 49) / 50);
        return observedHurtTicks <= compensation;
    }
}