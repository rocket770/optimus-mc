package dev.optimus.core.safety;

/**
 * World-bound evidence with nonblocking routine refreshes and immediate transition invalidation.
 */
public final class LobbySafety {
    public static final long REQUEST_TIMEOUT_MS = 3000;
    public static final long REFRESH_INTERVAL_MS = 60000;
    public static final long MAX_AGE_MS = REFRESH_INTERVAL_MS + REQUEST_TIMEOUT_MS + 2000;
    private String address = "", lobby = "", instance = "";
    private Object world;
    private long requestedAt, confirmedAt;
    private boolean pending, confirmed, refreshing;

    public synchronized void invalidate() {
        address = lobby = instance = "";
        world = null;
        pending = confirmed = refreshing = false;
    }

    public synchronized void request(String connected, Object currentWorld, long now) {
        if (!ServerSafety.isHypixel(connected) || currentWorld == null) {
            invalidate();
            return;
        }
        if (!address.equals(ServerSafety.normalize(connected)) || world != currentWorld)
            invalidate();
        address = ServerSafety.normalize(connected);
        world = currentWorld;
        requestedAt = now;
        pending = true;
        refreshing = false; // explicit/start/recovery requests block control
    }

    public synchronized boolean refreshDue(String connected, Object currentWorld, long now) {
        return !pending
                && verified(connected, currentWorld, now)
                && age(now) >= REFRESH_INTERVAL_MS;
    }

    public synchronized boolean refresh(String connected, Object currentWorld, long now) {
        if (!refreshDue(connected, currentWorld, now)) return false;
        requestedAt = now;
        pending = refreshing = true;
        return true;
    }

    public synchronized boolean confirm(
            String connected,
            Object currentWorld,
            long now,
            String game,
            String newLobby,
            String newInstance,
            boolean hasMatchFields) {
        if (!pending || currentWorld != world || !address.equals(ServerSafety.normalize(connected)))
            return false;
        boolean valid =
                now >= requestedAt
                        && now - requestedAt <= REQUEST_TIMEOUT_MS
                        && "DUELS".equals(game)
                        && newLobby != null
                        && newLobby.matches("duelslobby[0-9]+")
                        && newInstance != null
                        && newInstance.matches("dynamiclobby[A-Za-z0-9]+")
                        && !hasMatchFields;
        // Refresh may not silently transfer an armed session to another lobby instance.
        if (confirmed) valid &= lobby.equals(newLobby) && instance.equals(newInstance);
        if (!valid) {
            invalidate();
            return false;
        }
        lobby = newLobby;
        instance = newInstance;
        confirmedAt = now;
        confirmed = true;
        pending = refreshing = false;
        return true;
    }

    public synchronized boolean verified(String connected, Object currentWorld, long now) {
        return confirmed
                && (!pending || refreshing)
                && currentWorld != null
                && currentWorld == world
                && ServerSafety.isHypixel(connected)
                && address.equals(ServerSafety.normalize(connected))
                && now >= confirmedAt
                && now - confirmedAt <= MAX_AGE_MS;
    }

    public synchronized boolean awaitingReply(String connected, Object currentWorld, long now) {
        return pending
                && currentWorld != null
                && currentWorld == world
                && address.equals(ServerSafety.normalize(connected))
                && now >= requestedAt
                && now - requestedAt <= REQUEST_TIMEOUT_MS;
    }

    public synchronized boolean pending() {
        return pending;
    }

    public synchronized boolean blocking() {
        return pending && !refreshing;
    }

    public synchronized String lobby() {
        return lobby;
    }

    public synchronized String instance() {
        return instance;
    }

    public synchronized long age(long now) {
        return now - confirmedAt;
    }
}