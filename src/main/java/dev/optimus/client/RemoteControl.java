package dev.optimus.client;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;

/** Authenticated remote starts may dismiss chat/pause and temporarily prevent focus-loss pause. */
final class RemoteControl {
    private boolean overridden, previousPause;

    static boolean closeOnStart(GuiScreen screen) {
        return screen instanceof GuiChat || screen instanceof GuiIngameMenu;
    }

    static boolean closeWhileRunning(boolean running, GuiScreen screen) {
        return running && screen instanceof GuiIngameMenu;
    }

    static boolean closeAfterRespawn(boolean returning, GuiScreen screen) {
        return returning && screen instanceof GuiContainer && !(screen instanceof GuiInventory);
    }

    static boolean replyTo(String action) {
        return !"start".equals(action);
    }

    boolean keepActive(boolean currentPause) {
        if (!overridden) previousPause = currentPause;
        overridden = true;
        return false;
    }

    boolean restorePause(boolean currentPause) {
        if (!overridden) return currentPause;
        overridden = false;
        return previousPause;
    }
}