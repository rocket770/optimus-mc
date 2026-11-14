package dev.optimus.client;

import dev.optimus.client.runtime.HeadlessMenus;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import sun.misc.Unsafe;

/** Real client event integration plus menu actions, without network access or a display. */
public final class HeadlessRecoverySelfTest {
    private static int checks;

    private static void expect(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static final class Client extends Minecraft {
        private Client() { super(null); }
        @Override public void displayGuiScreen(GuiScreen screen) { currentScreen = screen; }
    }

    private static final class Player extends EntityPlayerSP {
        boolean living = true;
        int closes, respawns;
        List<String> sent;
        EmbeddedChannel channel;
        private Player() { super(null, null, null, null); }
        @Override public boolean isEntityAlive() { return living; }
        @Override public String getName() { return "OfflineBot"; }
        @Override public void addChatMessage(IChatComponent message) {}
        @Override public void setSprinting(boolean sprint) {}
        @Override public void closeScreen() { closes++; Minecraft.getMinecraft().currentScreen = null; }
        @Override public void respawnPlayer() { respawns++; }
        @Override public void sendChatMessage(String message) {
            sent.add(message);
            channel.writeOutbound(new C01PacketChatMessage(message));
        }
    }

    private static void location(ClientEvents events, String json) {
        events.onChat(new ClientChatReceivedEvent((byte) 0, new ChatComponentText(json)));
    }

    public static void main(String[] args) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Field singleton = field(Minecraft.class, "theMinecraft");
        Object previous = singleton.get(null);
        ClientSettings previousSettings = OptimusMod.settings;
        EmbeddedChannel channel = null;
        ClientEvents events = null;
        try {
            Client minecraft = (Client) unsafe.allocateInstance(Client.class);
            singleton.set(null, minecraft);
            minecraft.gameSettings = new GameSettings();
            set(minecraft, "mcDataDir", Files.createTempDirectory("optimus-recovery").toFile());
            minecraft.theWorld = (WorldClient) unsafe.allocateInstance(WorldClient.class);
            Player player = (Player) unsafe.allocateInstance(Player.class);
            player.living = true; player.sent = new ArrayList<>();
            net.minecraft.entity.DataWatcher watcher = new net.minecraft.entity.DataWatcher(player);
            watcher.addObject(6, Float.valueOf(20));
            set(player, "dataWatcher", watcher);
            player.inventory = new InventoryPlayer(player);
            player.capabilities = new PlayerCapabilities();
            minecraft.thePlayer = player;
            minecraft.setServerData(new ServerData("Fixture", "mc.hypixel.net", false));
            NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient handler = (NetHandlerPlayClient) unsafe.allocateInstance(NetHandlerPlayClient.class);
            set(handler, "netManager", manager); set(player, "sendQueue", handler);
            OptimusMod.settings = new ClientSettings(minecraft.mcDataDir);
            OptimusMod.settings.applySuppliedArena("duelslobby1", "dynamiclobbyTEST");
            HeadlessRecovery recovery = new HeadlessRecovery(true, minecraft);
            events = new ClientEvents(recovery);
            LobbySafety safety = (LobbySafety) get(events, "lobbySafety");
            TransitionWatch watch = new TransitionWatch(safety, manager);
            channel = new EmbeddedChannel(watch); player.channel = channel;
            set(manager, "channel", channel);
            set(events, "transitionWatch", watch); set(events, "watchedManager", manager);
            set(events, "armed", true);
            recovery.arm();
            long now = System.nanoTime() / 1000000L;
            safety.request("mc.hypixel.net", minecraft.theWorld, now);
            player.sent.clear();
            location(events, "{\"server\":\"limbo\"}");
            expect(recovery.recovering() && !(boolean) get(events, "armed"), "Observed limbo suspends gameplay but retains intent");
            expect(!safety.verified("mc.hypixel.net", minecraft.theWorld, now), "Limbo never authorizes movement");
            HeadlessRecovery.Host host = (HeadlessRecovery.Host) get(events, "recoveryHost");
            set(events, "settleUntil", 0L);
            recovery.tick(now + 2000, host);
            expect(player.sent.contains("/lobby duels"), "Recovery issues its authorized lobby command while disarmed");
            expect(watch.fatalSerial() == 0, "Recovery commands pass through the actual packet observer");
            Method changed = ClientEvents.class.getDeclaredMethod("worldChanged"); changed.setAccessible(true);
            changed.invoke(events);
            expect(recovery.recovering(), "World transfer preserves the recovery policy");
            set(events, "settleUntil", 0L);
            recovery.tick(now + 5000, host);
            location(events, "{\"server\":\"dynamiclobbyTEST\",\"gametype\":\"DUELS\",\"lobbyname\":\"duelslobby3\"}");
            recovery.tick(now + 7000, host);
            expect(player.sent.contains("/swaplobby 1"), "Recovery chooses lobby 1 after identifying another Duels lobby");
            set(events, "settleUntil", 0L);
            recovery.tick(now + 10000, host);
            location(events, "{\"server\":\"dynamiclobbyTEST\",\"gametype\":\"DUELS\",\"lobbyname\":\"duelslobby1\"}");
            changed.invoke(events);
            set(events, "settleUntil", 0L);
            recovery.tick(now + 12000, host);
            expect(!(boolean) get(events, "armed"), "A transfer after destination evidence discards the pending resume");
            expect(player.sent.get(player.sent.size() - 1).equals("/locraw"), "The new world requires another observed location reply");
            location(events, "{\"server\":\"dynamiclobbyTEST\",\"gametype\":\"DUELS\",\"lobbyname\":\"duelslobby1\"}");
            recovery.tick(now + 14000, host);
            expect((boolean) get(events, "armed") && !recovery.recovering(), "Destination evidence resumes the client");
            expect(((RunSafety) get(events, "run")).checking(), "Resume repeats normal lobby verification before movement");
            expect(!events.controlAllowed(), "Recovery cannot bypass the normal gameplay predicates");
            events.stop("Manual stop");
            changed.invoke(events);
            expect(!recovery.requested() && !recovery.recovering(), "Explicit stop prevents subsequent world events restarting the bot");

            HeadlessMenus menus = new HeadlessMenus();
            minecraft.currentScreen = new GuiChat(); menus.tick(minecraft, 0);
            expect(minecraft.currentScreen == null, "Headless chat is dismissed");
            minecraft.currentScreen = new GuiIngameMenu(); menus.tick(minecraft, 1);
            expect(minecraft.currentScreen == null, "Headless pause is dismissed");
            minecraft.currentScreen = (GuiInventory) unsafe.allocateInstance(GuiInventory.class);
            menus.tick(minecraft, 2);
            expect(player.closes == 1 && minecraft.currentScreen == null, "Inventory closes through the player's container path");
            minecraft.currentScreen = (GuiChest) unsafe.allocateInstance(GuiChest.class);
            menus.tick(minecraft, 3);
            expect(player.closes == 2, "Server menus close without clicking any slot");
            player.living = false; minecraft.currentScreen = new GuiGameOver();
            menus.tick(minecraft, 10); menus.tick(minecraft, 11);
            expect(player.respawns == 1, "Death requests respawn with backoff");
            menus.tick(minecraft, 2010);
            expect(player.respawns == 2, "An unanswered respawn request can retry");
            player.living = true; menus.tick(minecraft, 4010);
            expect(player.respawns == 2, "A living player never gets a respawn request");
            minecraft.currentScreen = new GuiScreen() {};
            expect(menus.tick(minecraft, 5000), "Unknown prompts receive a grace period");
            expect(!menus.tick(minecraft, 35000), "Unknown prompts time out without selecting buttons");
            expect(minecraft.currentScreen != null, "Unknown interfaces remain untouched");
            HeadlessRecovery disabled = new HeadlessRecovery(false, minecraft);
            disabled.arm();
            expect(!disabled.interrupt(0) && disabled.menus(40000), "Desktop recovery is disabled");
            expect(minecraft.currentScreen != null, "Desktop menus remain under user control");
            minecraft.currentScreen = new GuiChat();
            disabled.prepareStart(true);
            expect(minecraft.currentScreen != null, "Desktop startup does not acquire headless menu behavior");
            recovery.prepareStart(false);
            expect(minecraft.currentScreen != null, "Menu dismissal requires an authorized remote/local terminal start");
            recovery.prepareStart(true);
            expect(minecraft.currentScreen == null, "Headless startup dismisses known menus through its adapter");
            expect(HeadlessRecovery.reconnectScreen((GuiMultiplayer) unsafe.allocateInstance(GuiMultiplayer.class)),
                    "HeadlessMC's server-list disconnect screen supports reconnect");
            expect(HeadlessRecovery.reconnectScreen((GuiDisconnected) unsafe.allocateInstance(GuiDisconnected.class)),
                    "A dropped-connection screen supports reconnect");
            expect(HeadlessRecovery.reconnectScreen((GuiMainMenu) unsafe.allocateInstance(GuiMainMenu.class)),
                    "The main menu supports reconnect after a running session");
            expect(!HeadlessRecovery.reconnectScreen(new GuiScreen() {}), "Unknown prompts never trigger a reconnect");
            minecraft.theWorld = null;
            minecraft.currentScreen = (GuiMultiplayer) unsafe.allocateInstance(GuiMultiplayer.class);
            minecraft.setServerData(new ServerData("Fixture", "mc.hypixel.net", false));
            int[] reconnects = {0};
            HeadlessRecovery reconnecting = new HeadlessRecovery(true, minecraft, (client, server) -> {
                expect(server.serverIP.equals("mc.hypixel.net"), "Reconnect retains the original hostname");
                expect(client.getCurrentServerData() == server, "Reconnect restores identity before opening the connection screen");
                reconnects[0]++;
            });
            reconnecting.arm();
            minecraft.setServerData(null);
            reconnecting.interrupt(0);
            HeadlessRecovery.Host disconnected = new HeadlessRecovery.Host() {
                public boolean connected() { return false; }
                public void probe() { throw new AssertionError("No connection"); }
                public void lobbyCommand(String command) { throw new AssertionError("No connection"); }
                public void resume() { throw new AssertionError("No connection"); }
                public void fail(String reason) { throw new AssertionError(reason); }
            };
            reconnecting.tick(1000, disconnected);
            expect(reconnects[0] == 1, "Server-list recovery works after Minecraft clears server metadata");
            reconnecting.cancel();
            reconnecting.tick(20000, disconnected);
            expect(reconnects[0] == 1, "Stop revokes the saved reconnect destination");
            System.out.println("PASS: " + checks + " actual recovery events, menu and cancellation checks");
        } finally {
            if (events != null) events.stop("Fixture cleanup");
            if (channel != null) channel.finish();
            OptimusMod.settings = previousSettings;
            singleton.set(null, previous);
        }
    }
}