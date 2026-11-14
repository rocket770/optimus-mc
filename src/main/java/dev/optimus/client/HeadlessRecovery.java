package dev.optimus.client;

import dev.optimus.client.runtime.HeadlessMenus;
import dev.optimus.client.runtime.ServerConnector;
import dev.optimus.core.safety.ServerSafety;
import dev.optimus.core.safety.SessionRecovery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;

/** Minecraft actions for the independent session recovery policy. */
final class HeadlessRecovery {
    interface Host {
        boolean connected();

        void probe();

        void lobbyCommand(String command);

        void resume();

        void fail(String reason);
    }

    private final boolean enabled;
    private final Minecraft minecraft;
    private final ServerConnector connector;
    private final SessionRecovery policy = new SessionRecovery();
    private final HeadlessMenus menus = new HeadlessMenus();
    private String reconnectAddress = "";

    HeadlessRecovery(boolean enabled, Minecraft minecraft) {
        this(enabled, minecraft, ServerConnector.vanilla());
    }

    HeadlessRecovery(boolean enabled, Minecraft minecraft, ServerConnector connector) {
        this.enabled = enabled;
        this.minecraft = minecraft;
        this.connector = connector;
    }

    void arm() {
        if (!enabled) return;
        ServerData server = minecraft.getCurrentServerData();
        reconnectAddress =
                server != null && ServerSafety.isHypixel(server.serverIP) ? server.serverIP : "";
        policy.arm();
    }

    void cancel() {
        policy.cancel();
        menus.reset();
        reconnectAddress = "";
    }

    boolean requested() {
        return enabled && policy.requested();
    }

    boolean recovering() {
        return enabled && policy.recovering();
    }

    boolean interrupt(long now) {
        return enabled && policy.interrupt(now);
    }

    void prepareStart(boolean fromController) {
        if (enabled && fromController
                && HeadlessMenus.canClose(minecraft.currentScreen))
            HeadlessMenus.close(minecraft);
    }

    void location(String server, String game, String lobby, boolean match, long now) {
        policy.location(server, game, lobby, match, now);
    }

    boolean menus(long now) {
        return !requested() || menus.tick(minecraft, now);
    }

    static boolean reconnectScreen(GuiScreen screen) {
        return screen instanceof GuiDisconnected || screen instanceof GuiMainMenu
                || screen instanceof GuiMultiplayer;
    }

    void tick(long now, Host host) {
        boolean canReconnect = minecraft.theWorld == null && reconnectScreen(minecraft.currentScreen);
        switch (policy.tick(now, host.connected(), canReconnect)) {
            case PROBE:
                host.probe();
                break;
            case JOIN_DUELS:
                host.lobbyCommand("/lobby duels");
                break;
            case JOIN_ONE:
                host.lobbyCommand("/swaplobby 1");
                break;
            case RESUME:
                host.resume();
                break;
            case FAIL:
                host.fail(policy.failure());
                break;
            case RECONNECT:
                if (reconnectAddress.isEmpty())
                    host.fail("Recovery cannot identify the original Hypixel server");
                else {
                    // Minecraft clears currentServerData while unloading a world.
                    ServerData server = new ServerData("Optimus recovery", reconnectAddress, false);
                    minecraft.setServerData(server);
                    connector.connect(minecraft, server);
                }
                break;
            default:
                break;
        }
    }
}