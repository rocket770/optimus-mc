package dev.optimus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.lwjgl.input.Keyboard;

@Mod(
        modid = OptimusMod.MODID,
        name = "Optimus",
        version = OptimusMod.VERSION,
        acceptedMinecraftVersions = "[1.8.9]",
        clientSideOnly = true)
public final class OptimusMod {
    public static final String MODID = "optimus";
    public static final String VERSION = "1.0.4";
    static ClientSettings settings;
    static ClientEvents events;
    static KeyBinding toggleKey;
    static KeyBinding stopKey;
    static TerminalBridge terminal;
    static dev.optimus.client.map.MapExportService mapExports;
    static RouteHints routeHints;
    static java.io.File routeDirectory;
    static String routeIssue = "";
    static boolean routeHintsEnabled = true;
    private static final ThreadLocal<java.util.List<String>> terminalNotices = new ThreadLocal<>();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        settings = new ClientSettings(event.getModConfigurationDirectory());
        routeDirectory = event.getModConfigurationDirectory();
        routeHintsEnabled = settings.routeHintsEnabled;
        reloadRoutes();
    }

    static boolean reloadRoutes() {
        try {
            RouteHints replacement = RouteHints.load(routeDirectory);
            routeHints = replacement;
            routeIssue = "";
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            routeIssue = error.getMessage();
            return false;
        }
    }

    static String routeStatus() {
        return "Routes: "
                + (!routeHintsEnabled
                        ? "off"
                        : routeHints == null
                                ? "unavailable"
                                : routeHints.areaCount
                                        + " areas, "
                                        + routeHints.linkCount
                                        + " connections ("
                                        + routeHints.source
                                        + ")")
                + (routeIssue.isEmpty() ? "" : "; " + routeIssue);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        toggleKey = new KeyBinding("Optimus: toggle Duels lobby bot", Keyboard.KEY_F8, "Optimus");
        stopKey = new KeyBinding("Optimus: emergency stop", Keyboard.KEY_F9, "Optimus");
        ClientRegistry.registerKeyBinding(toggleKey);
        ClientRegistry.registerKeyBinding(stopKey);
        mapExports =
                new dev.optimus.client.map.MapExportService(
                        Minecraft.getMinecraft(),
                        new java.io.File(Minecraft.getMinecraft().mcDataDir, "optimus/maps")
                                .toPath(),
                        OptimusMod::notice);
        Runtime.getRuntime().addShutdownHook(new Thread(mapExports::close, "Optimus map cleanup"));
        events = new ClientEvents();
        Runtime.getRuntime()
                .addShutdownHook(new Thread(events::closeRecording, "Optimus recording cleanup"));
        MinecraftForge.EVENT_BUS.register(events);
        FMLCommonHandler.instance().bus().register(events);
        ClientCommandHandler.instance.registerCommand(new OptimusCommand());
        try {
            java.io.File directory =
                    new java.io.File(System.getProperty("user.home"), ".optimus/terminal");
            terminal =
                    new TerminalBridge(
                            directory,
                            Minecraft.getMinecraft().getSession().getUsername(),
                            Minecraft.getMinecraft().mcDataDir,
                            VERSION);
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        try {
                                            terminal.close();
                                        } catch (java.io.IOException ignored) {
                                        }
                                    },
                                    "Optimus terminal cleanup"));
        } catch (java.io.IOException | RuntimeException error) {
            notice("Terminal control unavailable: " + error.getMessage());
        }
    }

    static java.util.List<String> terminalCommand(String[] args, boolean emergency) {
        if (emergency
                && ("start".equals(args[0])
                        || "guard".equals(args[0])
                                && args.length == 2
                                && !args[1].equalsIgnoreCase("off")
                                && !args[1].equalsIgnoreCase("status")))
            throw new IllegalArgumentException("Start blocked while F9 is held.");
        java.util.List<String> replies = new java.util.ArrayList<>();
        terminalNotices.set(replies);
        try {
            new OptimusCommand().processCommand(Minecraft.getMinecraft().thePlayer, args);
            if ("start".equals(args[0])) events.processRequestedStart();
            return replies;
        } finally {
            terminalNotices.remove();
        }
    }

    static void notice(String message) {
        java.util.List<String> terminalReply = terminalNotices.get();
        if (terminalReply != null) {
            terminalReply.add(message);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(
                    new ChatComponentText(
                            EnumChatFormatting.AQUA
                                    + "[Optimus] "
                                    + EnumChatFormatting.RESET
                                    + message));
        } else {
            System.out.println("[Optimus] " + message);
        }
    }
}