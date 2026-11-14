package dev.optimus.client.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

/** Dismisses gameplay menus without clicking slots or accepting prompts. */
public final class HeadlessMenus {
    public enum Action {
        NONE, CLOSE, RESPAWN, BLOCKED
    }

    private static final long RESPAWN_RETRY_MS = 2000;
    private static final long BLOCKED_MENU_TIMEOUT_MS = 30000;
    private long respawnAt;
    private GuiScreen blockedScreen;
    private long blockedAt;

    public static Action classify(GuiScreen screen, boolean alive) {
        if (screen == null) return Action.NONE;
        if (screen instanceof GuiGameOver) return alive ? Action.NONE : Action.RESPAWN;
        if (canClose(screen)) return Action.CLOSE;
        return Action.BLOCKED;
    }

    public static boolean canClose(GuiScreen screen) {
        return screen instanceof GuiChat || screen instanceof GuiIngameMenu
                || screen instanceof GuiContainer;
    }

    public static void close(Minecraft minecraft) {
        if (minecraft.currentScreen instanceof GuiContainer && minecraft.thePlayer != null)
            minecraft.thePlayer.closeScreen();
        else minecraft.displayGuiScreen(null);
    }

    /** False means an unknown blocking screen exceeded its grace period. */
    public boolean tick(Minecraft minecraft, long now) {
        boolean alive = minecraft.thePlayer != null && minecraft.thePlayer.isEntityAlive()
                && minecraft.thePlayer.getHealth() > 0;
        Action action = classify(minecraft.currentScreen, alive);
        if (action != Action.BLOCKED) blockedScreen = null;
        if (action == Action.CLOSE) {
            close(minecraft);
        } else if (action == Action.RESPAWN && minecraft.thePlayer != null && now >= respawnAt) {
            minecraft.thePlayer.respawnPlayer();
            respawnAt = now + RESPAWN_RETRY_MS;
        } else if (action == Action.BLOCKED) {
            if (blockedScreen != minecraft.currentScreen) {
                blockedScreen = minecraft.currentScreen;
                blockedAt = now;
            }
            return now - blockedAt < BLOCKED_MENU_TIMEOUT_MS;
        }
        return true;
    }

    public void reset() {
        respawnAt = blockedAt = 0;
        blockedScreen = null;
    }
}