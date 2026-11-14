package dev.optimus.core;

import dev.optimus.core.safety.SessionRecovery;
import dev.optimus.core.safety.SessionRecovery.Action;

public final class SessionRecoverySelfTest {
    private static int checks;
    private static void expect(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        SessionRecovery recovery = new SessionRecovery();
        expect(!recovery.interrupt(0), "Stopped sessions never start recovery");
        recovery.arm();
        expect(recovery.interrupt(0), "An explicit run can recover");
        expect(recovery.tick(999, true, false) == Action.NONE, "World changes settle first");
        expect(recovery.tick(1000, true, false) == Action.PROBE, "Recovery requests location");
        expect(recovery.tick(1001, true, false) == Action.NONE, "Probes are rate limited");
        recovery.location("limbo", null, null, false, 1100);
        expect(recovery.tick(2600, true, false) == Action.JOIN_DUELS, "Limbo returns through the lobby command");
        expect(recovery.tick(2601, true, false) == Action.NONE, "Lobby commands are not repeated each tick");
        expect(recovery.tick(4600, true, false) == Action.PROBE, "Transfer requires a new location reply");
        recovery.location("dynamiclobbyA", "DUELS", "duelslobby4", false, 4700);
        expect(recovery.tick(6200, true, false) == Action.JOIN_ONE, "Other Duels lobbies switch to lobby 1");
        expect(recovery.tick(8200, true, false) == Action.PROBE, "Lobby 1 is independently checked");
        recovery.location("dynamiclobbyB", "DUELS", "duelslobby1", false, 8300);
        expect(recovery.tick(9800, true, false) == Action.RESUME, "Verified destination resumes through normal start");
        expect(recovery.requested() && !recovery.recovering(), "Intent survives successful recovery");
        expect(recovery.tick(9801, true, false) == Action.NONE, "Resume occurs once");
        for (int i = 0; i < 2; i++) {
            long time = 10000 + i * 5000;
            recovery.interrupt(time);
            recovery.location("dynamiclobbyB", "DUELS", "duelslobby1", false, time + 1);
            expect(recovery.tick(time + 1501, true, false) == Action.RESUME, "Retry budget permits bounded later recovery");
        }
        recovery.interrupt(20000);
        expect(recovery.tick(21000, true, false) == Action.FAIL, "Repeated interruptions exhaust the run budget");
        recovery.arm(); recovery.interrupt(0);
        expect(recovery.tick(1000, false, false) == Action.NONE, "An in-progress connection is left alone");
        expect(recovery.tick(1000, false, true) == Action.RECONNECT, "Disconnected screens can reconnect");
        expect(recovery.tick(1001, false, true) == Action.NONE, "Reconnects have backoff");
        expect(recovery.tick(11000, false, true) == Action.RECONNECT, "Second reconnect follows backoff");
        expect(recovery.tick(21000, false, true) == Action.RECONNECT, "Third reconnect is bounded");
        expect(recovery.tick(31000, false, true) == Action.NONE, "A fourth connection is not attempted");
        recovery.interrupt(59000);
        expect(recovery.tick(60001, false, true) == Action.FAIL, "Further transfer events cannot extend the deadline");
        recovery.arm(); recovery.interrupt(0);
        recovery.location("limbo", null, null, false, 1000);
        recovery.cancel();
        expect(recovery.tick(5000, true, false) == Action.NONE, "Stop cancels queued lobby commands");
        expect(!recovery.requested() && !recovery.interrupt(6000), "Stop prevents later restart or reconnect");
        for (boolean match : new boolean[] {false, true}) {
            recovery.arm(); recovery.interrupt(0);
            recovery.location("mini123", "DUELS", "duelslobby1", match, 1000);
            expect(recovery.tick(2500, true, false) == Action.FAIL, "Matches and malformed destinations cannot resume control");
        }
        recovery.arm();
        recovery.interrupt(0);
        recovery.location("dynamiclobbyA", "DUELS", "duelslobby1", false, 1000);
        recovery.interrupt(1500);
        expect(recovery.tick(2500, true, false) == Action.PROBE,
                "A transfer discards destination evidence rather than resuming in a new world");
        recovery.location("dynamiclobbyB", "DUELS", "duelslobby3", false, 2600);
        expect(recovery.tick(4100, true, false) == Action.JOIN_ONE,
                "The new world's lobby is selected before control can resume");
        recovery.arm();
        recovery.interrupt(0);
        recovery.location(null, "MAIN", null, false, 1000);
        expect(recovery.tick(2500, true, false) == Action.FAIL,
                "A lobby reply without a server identity is rejected");
        recovery.location("dynamiclobbyA", "DUELS", "duelslobby1", false, 2600);
        expect(recovery.tick(5000, true, false) == Action.FAIL,
                "A late reply cannot revive a failed recovery");
        System.out.println("PASS: " + checks + " session recovery, cancellation and retry checks");
    }
}