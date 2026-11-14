package dev.optimus.client.runtime;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import sun.misc.Unsafe;

/** Checks real Minecraft server metadata without opening a socket or a display. */
public final class HeadlessConnectionSelfTest {
    private static int checks;

    private static void expect(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        int[] connects = {0};
        HeadlessConnection startup = new HeadlessConnection("mc.hypixel.net", (client, server) -> {
            expect(client.getCurrentServerData() == server, "Identity is published before connecting");
            expect(server.serverIP.equals("mc.hypixel.net"), "Requested hostname is retained");
            connects[0]++;
        });
        startup.tick(minecraft);
        expect(connects[0] == 0, "Startup waits for Minecraft to finish loading");
        minecraft.currentScreen = new GuiScreen() {};
        startup.tick(minecraft);
        expect(connects[0] == 0, "Startup preserves unrelated screens");
        minecraft.currentScreen = (GuiMainMenu) unsafe.allocateInstance(GuiMainMenu.class);
        startup.tick(minecraft);
        expect(connects[0] == 1, "Main menu triggers exactly one connection");
        startup.tick(minecraft);
        expect(connects[0] == 1, "A failed connection or disconnect never reconnects automatically");
        new HeadlessConnection("", (client, server) -> {
            throw new AssertionError("No server was requested");
        }).tick(minecraft);
        expect(connects[0] == 1, "Offline/menu-only launches remain disconnected");
        System.out.println("PASS: " + checks + " headless connection identity checks");
    }
}