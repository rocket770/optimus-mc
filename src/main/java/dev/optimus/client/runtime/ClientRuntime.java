package dev.optimus.client.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;

/** Coordinates headless startup and presentation; gameplay remains in the client controllers. */
public final class ClientRuntime {
    private final boolean headless;
    private final HeadlessConnection connection;

    public ClientRuntime(boolean headless) {
        this(headless, new HeadlessConnection(""));
    }

    private ClientRuntime(boolean headless, HeadlessConnection connection) {
        this.headless = headless;
        this.connection = connection;
    }

    public static ClientRuntime fromSystemProperties() {
        return new ClientRuntime(Boolean.getBoolean("optimus.headless"),
                new HeadlessConnection(System.getProperty("optimus.server", "")));
    }

    public boolean headless() {
        return headless;
    }

    public void initialize(Minecraft minecraft) {
        if (!headless) return;
        // A console-controlled client must never capture or poll the desktop mouse.
        minecraft.mouseHelper = new ConsoleMouse();
        minecraft.gameSettings.enableVsync = false;
        minecraft.gameSettings.limitFramerate = 30;
        tick(minecraft);
    }

    public void tick(Minecraft minecraft) {
        if (!headless) return;
        minecraft.gameSettings.pauseOnLostFocus = false;
        connection.tick(minecraft);
    }

    private static final class ConsoleMouse extends MouseHelper {
        @Override
        public void mouseXYChange() {
            deltaX = deltaY = 0;
        }

        @Override
        public void grabMouseCursor() {}

        @Override
        public void ungrabMouseCursor() {}
    }
}