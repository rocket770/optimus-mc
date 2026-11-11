package dev.optimus.core.safety;

/** Run intent survives commanded lobby joins and reverified same-world respawns. */
public final class RunSafety {
    public enum Phase {
        OFF,
        CHECK_LOBBY,
        JOIN_DUELS,
        JOIN_ONE,
        ACTIVE,
        RECOVERING
    }

    private Phase phase = Phase.OFF;
    private String instance = "", activeLobby = "";
    private Object world;
    private long deadline;

    public Phase phase() {
        return phase;
    }

    public boolean running() {
        return phase != Phase.OFF;
    }

    public boolean joining() {
        return phase == Phase.JOIN_DUELS || phase == Phase.JOIN_ONE;
    }

    public boolean checking() {
        return phase == Phase.CHECK_LOBBY;
    }

    public boolean mayControl() {
        return phase == Phase.ACTIVE;
    }

    public void stop() {
        phase = Phase.OFF;
        instance = activeLobby = "";
        world = null;
    }

    public void start(long now) {
        stop();
        phase = Phase.JOIN_DUELS;
        deadline = now + 30000;
    }

    public void startChecking(long now) {
        stop();
        phase = Phase.CHECK_LOBBY;
        deadline = now + 30000;
    }

    public void joinDuels() {
        if (checking()) phase = Phase.JOIN_DUELS;
    }

    public void joiningOne() {
        if (phase == Phase.JOIN_DUELS) phase = Phase.JOIN_ONE;
    }

    public boolean activate(String lobby, String server, Object currentWorld) {
        if ((phase != Phase.JOIN_ONE && !checking())
                || lobby == null
                || !lobby.matches("duelslobby[0-9]+")
                || server == null
                || !server.matches("dynamiclobby[A-Za-z0-9]+")
                || currentWorld == null) {
            stop();
            return false;
        }
        activeLobby = lobby;
        instance = server;
        world = currentWorld;
        phase = Phase.ACTIVE;
        return true;
    }

    public void worldTransfer() {
        if (!joining()) stop();
    }

    public void respawn(long now) {
        if (phase == Phase.ACTIVE || phase == Phase.RECOVERING) {
            if (phase == Phase.ACTIVE) deadline = now + 8000;
            phase = Phase.RECOVERING;
        }
    }

    public boolean recover(String lobby, String server, Object currentWorld) {
        if (phase != Phase.RECOVERING
                || !activeLobby.equals(lobby)
                || !instance.equals(server)
                || world != currentWorld) {
            stop();
            return false;
        }
        phase = Phase.ACTIVE;
        return true;
    }

    public boolean expired(long now) {
        return (checking() || joining() || phase == Phase.RECOVERING) && now > deadline;
    }
}