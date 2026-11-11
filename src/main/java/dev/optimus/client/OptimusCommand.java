package dev.optimus.client;

import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.control.ChatMessage;
import dev.optimus.core.control.CommandInput;
import dev.optimus.core.safety.ServerSafety;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

/** Client commands; the controller sends only explicit lobby joins and location checks. */
public final class OptimusCommand extends CommandBase {
    private List<String> remoteReplies;

    private void notice(String message) {
        OptimusMod.notice(message);
        if (remoteReplies != null) remoteReplies.add(message);
    }

    List<String> processRemote(ICommandSender sender, String[] args) {
        remoteReplies = new ArrayList<>();
        try {
            processCommand(sender, args);
            return new ArrayList<>(remoteReplies);
        } finally {
            remoteReplies = null;
        }
    }

    @Override
    public String getCommandName() {
        return "o";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/o help";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        try {
            args = CommandInput.normalize(args);
        } catch (IllegalArgumentException error) {
            notice(error.getMessage());
            return;
        }
        if (args[0].equals("help")) {
            printHelp();
            return;
        }
        String action = args[0].toLowerCase(java.util.Locale.ROOT);
        ClientSettings settings = OptimusMod.settings;
        Minecraft mc = Minecraft.getMinecraft();
        switch (action) {
            case "ml":
                notice(
                        args.length <= 2
                                ? OptimusMod.events.mlCommand(args.length == 1 ? "status" : args[1])
                                : "Usage: /o ml off|shadow|live|reload|status");
                return;
            case "logging":
            case "record":
                notice(
                        args.length <= 2
                                ? OptimusMod.events.loggingCommand(
                                        args.length == 1 ? "status" : args[1])
                                : "Usage: /o logging on|off|status");
                return;
            case "map":
                handleMap(args);
                return;
            case "visuals":
                handleVisuals(settings, args);
                return;
            case "weapon":
                handleWeapon(settings, args);
                return;
            case "operator":
                handleOperator(settings, args);
                return;
            case "routes":
                handleRoutes(args);
                return;
            case "controller":
                handleController(settings, args);
                return;
            case "say":
                try {
                    OptimusMod.events.say(ChatMessage.fromArguments(args, 1));
                } catch (IllegalArgumentException error) {
                    notice(error.getMessage());
                }
                return;
            case "add":
                if (args.length != 2) {
                    notice("Usage: /o add <Minecraft_username>");
                    return;
                }
                if (settings.whitelist.add(args[1])
                        || settings.targetAll && settings.whitelist.contains(args[1])) {
                    settings.targetAll = false;
                    settings.save();
                    OptimusMod.events.targetsChanged();
                    notice("Added " + args[1]);
                } else notice("Invalid name or already on the target list.");
                return;
            case "remove":
                if (args.length != 2) {
                    notice("Usage: /o remove <username>");
                    return;
                }
                if (settings.whitelist.remove(args[1])) {
                    settings.targetAll = false;
                    settings.save();
                    OptimusMod.events.targetsChanged();
                    notice("Removed " + args[1]);
                } else notice("Not on the target list.");
                return;
            case "list":
                notice("Targets: " + settings.targetSummary());
                return;
            case "all":
            case "listed":
                settings.targetAll = action.equals("all");
                settings.save();
                OptimusMod.events.targetsChanged();
                notice("Targets: " + settings.targetSummary() + ".");
                return;
            case "clear":
                settings.targetAll = false;
                settings.whitelist.clear();
                settings.save();
                OptimusMod.events.targetsChanged();
                notice("Targets cleared.");
                return;
            case "status":
                OptimusMod.events.printStatus();
                return;
            case "profile":
                if (args.length != 2) {
                    notice("Usage: /o profile balanced|baseline|paced");
                    return;
                }
                CombatProfile profile;
                try {
                    profile = CombatProfile.parse(args[1]);
                } catch (IllegalArgumentException error) {
                    notice("Unknown profile. Use balanced, baseline or paced.");
                    return;
                }
                OptimusMod.events.stop("Combat profile changed");
                settings.combatProfile = profile;
                settings.save();
                notice("Profile: " + profile.id() + ". /o start to resume.");
                return;
            case "seed":
                if (args.length != 2) {
                    notice("Usage: /o seed <signed 64-bit integer>");
                    return;
                }
                long seed;
                try {
                    seed = Long.parseLong(args[1]);
                } catch (NumberFormatException error) {
                    notice("Invalid integer seed.");
                    return;
                }
                OptimusMod.events.stop("Combat seed changed");
                settings.combatSeed = seed;
                settings.save();
                notice("Seed: " + seed + ". /o start to resume.");
                return;
            case "testserver":
            case "route":
                OptimusMod.events.stop("Legacy automation command rejected");
                notice("That command was removed. Use /o start in a Duels lobby.");
                return;
            case "pit":
                if (args.length != 2
                        || (!args[1].equalsIgnoreCase("a") && !args[1].equalsIgnoreCase("b"))) {
                    notice("Usage: /o pit a OR /o pit b");
                    return;
                }
                OptimusMod.events.recordPit(args[1].equalsIgnoreCase("a"));
                return;
            case "nav":
                notice("No navigation corners needed. Only the pit needs corners.");
                return;
            case "entrance":
                if (args.length != 1) {
                    notice("Usage: /o entrance");
                    return;
                }
                OptimusMod.events.recordEntrance();
                return;
            case "preset":
                if (args.length != 2 || !args[1].equalsIgnoreCase("supplied")) {
                    notice("Usage: /o preset supplied");
                    return;
                }
                OptimusMod.events.applySuppliedArena();
                return;
            case "parkour":
                if (args.length != 2
                        || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
                    notice("Usage: /o parkour on|off");
                    return;
                }
                OptimusMod.events.setParkour(args[1].equalsIgnoreCase("on"));
                return;
            case "start":
                OptimusMod.events.requestStart(remoteReplies != null);
                notice("Starting...");
                return;
            case "stop":
                OptimusMod.events.stop("Manual stop");
                notice("Stopped.");
                return;
            case "locraw":
                if (!ServerSafety.isHypixel(OptimusMod.events.connectedServer())) {
                    notice("/locraw verification is only used on Hypixel.");
                    return;
                }
                if (mc.thePlayer != null) {
                    OptimusMod.events.requestLocraw();
                }
                return;
            default:
                notice("Unknown command. Use /o help");
        }
    }

    @Override
    public List<String> addTabCompletionOptions(
            ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 0) return Collections.emptyList();
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(
                    args,
                    "help",
                    "ml",
                    "logging",
                    "record",
                    "say",
                    "start",
                    "startz",
                    "stop",
                    "target",
                    "status",
                    "controller",
                    "operator",
                    "weapon",
                    "add",
                    "remove",
                    "list",
                    "clear",
                    "locraw",
                    "preset",
                    "parkour",
                    "profile",
                    "pit",
                    "entrance",
                    "seed",
                    "routes",
                    "visuals",
                    "map");
        if (args.length == 2 && args[0].equalsIgnoreCase("ml"))
            return getListOfStringsMatchingLastWord(
                    args, "off", "shadow", "live", "reload", "status");
        if (args.length == 2
                && (args[0].equalsIgnoreCase("logging") || args[0].equalsIgnoreCase("record")))
            return getListOfStringsMatchingLastWord(args, "on", "off", "status");
        boolean target = args[0].equalsIgnoreCase("target") || args[0].equalsIgnoreCase("targets");
        if (target && args.length == 2)
            return getListOfStringsMatchingLastWord(
                    args, "add", "remove", "list", "clear", "all", "listed");
        if (args.length == 3
                && args[0].equalsIgnoreCase("operator")
                && args[1].equalsIgnoreCase("remove"))
            return getListOfStringsMatchingLastWord(args, OptimusMod.settings.operators.keySet());
        if ((!target && args.length == 2 && args[0].equalsIgnoreCase("remove"))
                || (target && args.length == 3 && args[1].equalsIgnoreCase("remove")))
            return getListOfStringsMatchingLastWord(args, OptimusMod.settings.whitelist.all());
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("map"))
                return getListOfStringsMatchingLastWord(args, "export", "status", "cancel");
            if (args[0].equalsIgnoreCase("visuals"))
                return getListOfStringsMatchingLastWord(args, "on", "off", "status");
            if (args[0].equalsIgnoreCase("weapon"))
                return getListOfStringsMatchingLastWord(args, "sword", "fist");
            if (args[0].equalsIgnoreCase("operator"))
                return getListOfStringsMatchingLastWord(args, "add", "remove", "list", "clear");
            if (args[0].equalsIgnoreCase("routes"))
                return getListOfStringsMatchingLastWord(args, "status", "reload", "on", "off");
            if (args[0].equalsIgnoreCase("profile"))
                return getListOfStringsMatchingLastWord(args, "balanced", "baseline", "paced");
            if (args[0].equalsIgnoreCase("parkour"))
                return getListOfStringsMatchingLastWord(args, "on", "off");
            if (args[0].equalsIgnoreCase("preset"))
                return getListOfStringsMatchingLastWord(args, "supplied");
            if (args[0].equalsIgnoreCase("pit"))
                return getListOfStringsMatchingLastWord(args, "a", "b");
        }
        return Collections.emptyList();
    }

    private void printHelp() {
        notice("/o ml off|shadow|live|reload|status | /o logging on|off|status");
        notice("/o map export <name> [radius 8-64] | status | cancel (loaded terrain only)");
        notice("/o start | stop | status (F8 starts, F9 stops)");
        notice("/o target add <name> | remove <name> | list | clear | all | listed");
        notice("/o weapon sword|fist (fist uses empty hotbar slot 2)");
        notice(
                "/o operator add <name> | remove <name> | list | clear (targets, weapons and start/stop)");
        notice("/o add, remove, list and clear also work");
        notice("/o say <message>");
        notice("/o parkour on|off | /o profile balanced|baseline|paced | /o seed <integer>");
        notice("/o routes status|reload|on|off (reload reads config/optimus-route-hints.json)");
        notice("/o visuals on|off|status (waypoints, blocks, jump projections and movement trail)");
        notice("Changing parkour, profile or seed stops the bot; /o start resumes");
        notice("/o controller <main name> pairs chat control: !o [all|@BotName] <command>");
        notice("/o pit a|b saves pit corners | /o entrance saves the entry point");
        notice("/o preset supplied loads saved coordinates | /o locraw checks the lobby");
    }

    private void handleVisuals(ClientSettings settings, String[] args) {
        if (args.length == 2
                && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            settings.debugVisuals = args[1].equalsIgnoreCase("on");
            settings.save();
            OptimusMod.events.clearVisuals();
        } else if (args.length > 2 || args.length == 2 && !args[1].equalsIgnoreCase("status")) {
            notice("Usage: /o visuals on|off|status");
            return;
        }
        notice(
                "Navigation visuals: "
                        + (settings.debugVisuals ? "on" : "off")
                        + " (local display; run state unchanged).");
    }

    private void handleWeapon(ClientSettings settings, String[] args) {
        if (args.length != 2
                || (!args[1].equalsIgnoreCase("sword") && !args[1].equalsIgnoreCase("fist"))) {
            notice("Usage: /o weapon sword|fist");
            return;
        }
        settings.swordEnabled = args[1].equalsIgnoreCase("sword");
        settings.save();
        OptimusMod.events.weaponChanged();
        notice(
                "Weapon: "
                        + (settings.swordEnabled ? "sword" : "fist (slot 2 must be empty)")
                        + ".");
    }

    private void handleOperator(ClientSettings settings, String[] args) {
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("list")) {
            notice("Operators (targets, weapons and start/stop): " + settings.operators.keySet());
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
            notice(OptimusMod.events.addOperator(args[2]));
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            boolean removed = settings.operators.remove(args[2]) != null;
            settings.save();
            notice(removed ? "Operator removed: " + args[2] : "Not an operator.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
            settings.operators.clear();
            settings.save();
            notice("Operators cleared.");
            return;
        }
        notice("Usage: /o operator add <name> | remove <name> | list | clear");
    }

    private void handleRoutes(String[] args) {
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            notice(OptimusMod.routeStatus());
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
            OptimusMod.events.stop("Reloading route hints");
            notice(
                    OptimusMod.reloadRoutes()
                            ? OptimusMod.routeStatus() + ". /o start to resume."
                            : "Routes not replaced: " + OptimusMod.routeIssue);
            return;
        }
        if (args.length == 2
                && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            OptimusMod.events.stop("Route hints changed");
            OptimusMod.routeHintsEnabled = args[1].equalsIgnoreCase("on");
            notice(OptimusMod.routeStatus() + ". /o start to resume.");
            return;
        }
        notice("Usage: /o routes status|reload|on|off");
    }

    private void handleController(ClientSettings settings, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("debug")) {
            OptimusMod.events.printRemoteDebug();
            return;
        }
        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("status"))) {
            notice(
                    "Controller: "
                            + (settings.controllerName.isEmpty()
                                    ? "off"
                                    : settings.controllerName));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
            OptimusMod.events.clearController();
            return;
        }
        if (args.length == 2) {
            OptimusMod.events.setController(args[1]);
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            OptimusMod.events.setController(args[2]);
            return;
        }
        notice("Usage: /o controller <main name> | set <main name> | off | status | debug");
    }

    private void handleMap(String[] args) {
        if (OptimusMod.mapExports == null) {
            notice("Map export is not initialized.");
            return;
        }
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            notice(OptimusMod.mapExports.status());
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            OptimusMod.mapExports.cancel();
            notice("Map export cancelled.");
            return;
        }
        if ((args.length == 3 || args.length == 4) && args[1].equalsIgnoreCase("export")) {
            try {
                int radius = args.length == 4 ? Integer.parseInt(args[3]) : 32;
                OptimusMod.mapExports.start(args[2], radius);
                OptimusMod.events.stop("Map export requested");
            } catch (IllegalArgumentException error) {
                notice(error.getMessage());
            }
            return;
        }
        notice("Usage: /o map export <name> [radius 8-64] | status | cancel");
    }
}