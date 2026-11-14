package dev.optimus.core.safety;

/** Bounded recovery of explicit run intent; the client supplies observed location replies. */
public final class SessionRecovery {
    public enum Action {
        NONE, PROBE, JOIN_DUELS, JOIN_ONE, RECONNECT, RESUME, FAIL
    }

    private enum Phase {
        STOPPED, RUNNING, LOCATING, JOIN_DUELS, JOIN_ONE, RESUMING, FAILED
    }

    private static final int MAX_RECOVERIES = 3;
    private static final int MAX_RECONNECTS = 3;
    private static final long RECOVERY_TIMEOUT_MS = 60000;
    private static final long SETTLE_DELAY_MS = 1000;
    private static final long LOCATION_DELAY_MS = 1500;
    private static final long TRANSFER_DELAY_MS = 2000;
    private static final long PROBE_INTERVAL_MS = 4000;
    private static final long RECONNECT_INTERVAL_MS = 10000;

    private Phase phase = Phase.STOPPED;
    private int recoveries, reconnects;
    private long deadline, nextAction;
    private String failure = "";

    public void arm() {
        cancel();
        phase = Phase.RUNNING;
    }

    public void cancel() {
        phase = Phase.STOPPED;
        recoveries = reconnects = 0;
        deadline = nextAction = 0;
        failure = "";
    }

    public boolean requested() {
        return phase != Phase.STOPPED;
    }

    public boolean recovering() {
        return phase != Phase.STOPPED && phase != Phase.RUNNING;
    }

    public String failure() {
        return failure;
    }

    public boolean interrupt(long now) {
        if (!requested()) return false;
        if (recovering()) {
            // Evidence belongs to the world/connection that produced it.
            if (phase != Phase.LOCATING && phase != Phase.FAILED) {
                phase = Phase.LOCATING;
                nextAction = now + SETTLE_DELAY_MS;
            }
            return true;
        }
        phase = Phase.LOCATING;
        reconnects = 0;
        deadline = now + RECOVERY_TIMEOUT_MS;
        nextAction = now + SETTLE_DELAY_MS;
        if (++recoveries > MAX_RECOVERIES) fail("Recovery limit reached; start again to retry");
        return true;
    }

    /** Called only for a reply accepted by the client's pending-request scope. */
    public void location(String server, String game, String lobby, boolean match, long now) {
        if (!recovering() || phase == Phase.FAILED || now > deadline) return;
        if (match) {
            fail("Recovery encountered a game match");
        } else if (server != null && !server.isEmpty()
                && ("limbo".equals(server) || "MAIN".equals(game) || "LOBBY".equals(game))) {
            phase = Phase.JOIN_DUELS;
        } else if ("DUELS".equals(game) && lobby != null
                && lobby.matches("duelslobby[0-9]+") && server != null
                && server.matches("dynamiclobby[A-Za-z0-9]+")) {
            phase = "duelslobby1".equals(lobby) ? Phase.RESUMING : Phase.JOIN_ONE;
        } else {
            fail("Recovery received an unsupported location");
        }
        nextAction = now + LOCATION_DELAY_MS;
    }

    public Action tick(long now, boolean connected, boolean canReconnect) {
        if (!recovering()) return Action.NONE;
        if (now > deadline && phase != Phase.FAILED) fail("Recovery timed out");
        if (phase == Phase.FAILED) return Action.FAIL;
        if (now < nextAction) return Action.NONE;
        if (!connected) {
            if (!canReconnect || reconnects >= MAX_RECONNECTS) return Action.NONE;
            reconnects++;
            nextAction = now + RECONNECT_INTERVAL_MS;
            return Action.RECONNECT;
        }
        switch (phase) {
            case RESUMING:
                phase = Phase.RUNNING;
                return Action.RESUME;
            case JOIN_DUELS:
                phase = Phase.LOCATING;
                nextAction = now + TRANSFER_DELAY_MS;
                return Action.JOIN_DUELS;
            case JOIN_ONE:
                phase = Phase.LOCATING;
                nextAction = now + TRANSFER_DELAY_MS;
                return Action.JOIN_ONE;
            default:
                nextAction = now + PROBE_INTERVAL_MS;
                return Action.PROBE;
        }
    }

    private void fail(String reason) {
        phase = Phase.FAILED;
        failure = reason;
    }
}