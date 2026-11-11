package dev.optimus.client;

import dev.optimus.client.guard.GuardController;
import dev.optimus.client.guard.GuardTestInputs;
import dev.optimus.core.geometry.PitRegion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;

/** Optional exact native state capture for comparisons between controller implementations. */
final class GuardReplayTrace {
    private static final boolean ENABLED = Boolean.getBoolean("optimus.guardParity");

    private GuardReplayTrace() {}

    static void capture(
            List<String> rows,
            String scenario,
            int millis,
            String phase,
            Minecraft mc,
            GuardController guard,
            int attacks)
            throws Exception {
        if (!ENABLED) return;
        EntityPlayer player = mc.thePlayer;
        GameSettings keys = mc.gameSettings;
        int controls =
                (keys.keyBindForward.isKeyDown() ? 1 : 0)
                        | (keys.keyBindBack.isKeyDown() ? 2 : 0)
                        | (keys.keyBindLeft.isKeyDown() ? 4 : 0)
                        | (keys.keyBindRight.isKeyDown() ? 8 : 0)
                        | (keys.keyBindJump.isKeyDown() ? 16 : 0)
                        | (keys.keyBindSneak.isKeyDown() ? 32 : 0)
                        | (keys.keyBindSprint.isKeyDown() ? 64 : 0);
        Object owner = GuardTestInputs.owner(guard, "assistTarget");
        Field selected = owner.getClass().getDeclaredField("assistTarget");
        selected.setAccessible(true);
        EntityPlayer target = (EntityPlayer) selected.get(owner);
        Method engaged = owner.getClass().getDeclaredMethod("engaged", PitRegion.class, long.class);
        engaged.setAccessible(true);
        boolean fighting =
                (boolean) engaged.invoke(owner, OptimusMod.settings.hypixelPit, guard.tick());
        Field paused = guard.getClass().getDeclaredField("paused");
        paused.setAccessible(true);
        String state =
                bits(player.posX)
                        + ":"
                        + bits(player.posY)
                        + ":"
                        + bits(player.posZ)
                        + ":"
                        + bits(player.motionX)
                        + ":"
                        + bits(player.motionY)
                        + ":"
                        + bits(player.motionZ)
                        + ":"
                        + Integer.toHexString(Float.floatToIntBits(player.rotationYaw))
                        + ":"
                        + Integer.toHexString(Float.floatToIntBits(player.rotationPitch))
                        + ":"
                        + controls
                        + ":"
                        + player.onGround
                        + ":"
                        + player.isSprinting()
                        + ":"
                        + attacks
                        + ":"
                        + (target == null ? "none" : target.getName())
                        + ":"
                        + guard.tick()
                        + ":"
                        + paused.getBoolean(guard)
                        + ":"
                        + fighting;
        rows.add(scenario + "," + millis + ",parity," + phase + "," + state + ",0,0,0,0");
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToLongBits(value));
    }
}