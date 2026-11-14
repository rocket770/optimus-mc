package dev.optimus.client;

import dev.optimus.client.runtime.ClientRuntime;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MouseHelper;
import sun.misc.Unsafe;

/** Verifies presentation isolation without creating a display or loading mouse natives. */
public final class ClientRuntimeSelfTest {
    private static int checks;

    private static void expect(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        minecraft.gameSettings = new GameSettings();
        minecraft.gameSettings.pauseOnLostFocus = true;
        minecraft.gameSettings.enableVsync = true;
        minecraft.gameSettings.limitFramerate = 120;
        MouseHelper original = new MouseHelper();
        minecraft.mouseHelper = original;

        ClientRuntime desktop = new ClientRuntime(false);
        desktop.initialize(minecraft);
        desktop.tick(minecraft);
        expect(minecraft.mouseHelper == original, "Desktop retains its input provider");
        expect(minecraft.gameSettings.pauseOnLostFocus, "Desktop retains background pause");
        expect(minecraft.gameSettings.enableVsync, "Desktop retains vsync");
        expect(minecraft.gameSettings.limitFramerate == 120, "Desktop retains its frame limit");

        ClientRuntime headless = new ClientRuntime(true);
        headless.initialize(minecraft);
        expect(minecraft.mouseHelper != original, "Headless replaces desktop mouse polling");
        expect(!minecraft.gameSettings.pauseOnLostFocus, "Headless ticks without focus");
        expect(!minecraft.gameSettings.enableVsync, "Headless does not wait for a display");
        expect(minecraft.gameSettings.limitFramerate == 30, "Headless limits frame work");
        minecraft.mouseHelper.deltaX = 17;
        minecraft.mouseHelper.deltaY = -9;
        minecraft.mouseHelper.grabMouseCursor();
        minecraft.mouseHelper.mouseXYChange();
        minecraft.mouseHelper.ungrabMouseCursor();
        expect(minecraft.mouseHelper.deltaX == 0 && minecraft.mouseHelper.deltaY == 0,
                "Headless mouse never changes bot aim or accesses native input");
        minecraft.gameSettings.pauseOnLostFocus = true;
        headless.tick(minecraft);
        expect(!minecraft.gameSettings.pauseOnLostFocus, "Headless remains active after settings reload");
        expect(minecraft.currentScreen == null && minecraft.thePlayer == null,
                "Presentation setup does not connect, start gameplay or fabricate a player");
        System.out.println("PASS: " + checks + " client runtime isolation checks");
    }
}