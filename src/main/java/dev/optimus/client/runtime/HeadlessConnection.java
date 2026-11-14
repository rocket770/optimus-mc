package dev.optimus.client.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;

/** Connects once through vanilla's ServerData path, preserving server identity for the mod. */
public final class HeadlessConnection {
    private final String address;
    private final ServerConnector connector;
    private boolean attempted;

    public HeadlessConnection(String address) {
        this(address, ServerConnector.vanilla());
    }

    HeadlessConnection(String address, ServerConnector connector) {
        this.address = address;
        this.connector = connector;
    }

    public void tick(Minecraft minecraft) {
        if (attempted || address.isEmpty() || minecraft.theWorld != null
                || !(minecraft.currentScreen instanceof GuiMainMenu)) return;
        attempted = true;
        ServerData server = new ServerData("Optimus headless", address, false);
        minecraft.setServerData(server);
        connector.connect(minecraft, server);
    }
}