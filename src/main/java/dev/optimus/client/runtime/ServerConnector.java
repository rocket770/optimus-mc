package dev.optimus.client.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;

/** Shared connection boundary; callers publish server identity before connecting. */
@FunctionalInterface
public interface ServerConnector {
    void connect(Minecraft minecraft, ServerData server);

    static ServerConnector vanilla() {
        return (minecraft, server) -> minecraft.displayGuiScreen(
                new GuiConnecting(minecraft.currentScreen, minecraft, server));
    }
}