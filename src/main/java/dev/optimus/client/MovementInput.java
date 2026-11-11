package dev.optimus.client;

import dev.optimus.core.navigation.MovementState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/** Owns bot-held keys and vanilla sprint state. Callers must authorize each input first. */
final class MovementInput {
    private final Minecraft mc;
    private final KeyBinding[] moving;
    private final boolean[] held = new boolean[6];
    private boolean botSprinting;
    private boolean heldSprint;

    MovementInput(Minecraft mc) {
        this.mc = mc;
        moving =
                new KeyBinding[] {
                    mc.gameSettings.keyBindForward,
                    mc.gameSettings.keyBindBack,
                    mc.gameSettings.keyBindLeft,
                    mc.gameSettings.keyBindRight,
                    mc.gameSettings.keyBindJump,
                    mc.gameSettings.keyBindSneak
                };
    }

    MovementState snapshot() {
        return MovementState.of(held[0], held[1], held[2], held[3], held[4], held[5], heldSprint);
    }

    void reassert() {
        for (int i = 0; i < held.length; i++) {
            if (held[i]) KeyBinding.setKeyBindState(moving[i].getKeyCode(), true);
        }
        if (heldSprint && canSprint())
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
    }

    void clearCombatInput() {
        for (KeyBinding key :
                new KeyBinding[] {mc.gameSettings.keyBindAttack, mc.gameSettings.keyBindUseItem}) {
            KeyBinding.setKeyBindState(key.getKeyCode(), false);
            while (key.isPressed()) {} // setKeyBindState alone does not drain queued presses.
        }
    }

    void releaseMovement() {
        if (heldSprint)
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        heldSprint = false;
        if (botSprinting && mc.thePlayer != null) mc.thePlayer.setSprinting(false);
        botSprinting = false;
        for (int i = 0; i < held.length; i++) {
            if (held[i]) KeyBinding.setKeyBindState(moving[i].getKeyCode(), false);
            held[i] = false;
        }
    }

    void sprint(boolean desired) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), desired);
        heldSprint = desired;
        mc.thePlayer.setSprinting(desired);
        botSprinting = desired;
    }

    boolean canSprint() {
        return mc.thePlayer != null
                && !mc.thePlayer.isCollidedHorizontally
                && !mc.thePlayer.isUsingItem()
                && !mc.thePlayer.isPotionActive(net.minecraft.potion.Potion.blindness)
                && (mc.thePlayer.getFoodStats().getFoodLevel() > 6
                        || mc.thePlayer.capabilities.allowFlying);
    }

    void move(boolean forward, boolean back, boolean left, boolean right, boolean jump) {
        move(forward, back, left, right, jump, false);
    }

    void move(
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak) {
        boolean[] keys = {forward, back, left, right, jump, sneak};
        // Menus/focus changes can clear vanilla key state behind our cached ownership flags.
        for (int i = 0; i < moving.length; i++) {
            KeyBinding.setKeyBindState(moving[i].getKeyCode(), keys[i]);
            held[i] = keys[i];
        }
    }
}