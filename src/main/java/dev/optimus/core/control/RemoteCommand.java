package dev.optimus.core.control;

import java.util.*;

/** UUID-bound chat controls; operator permissions are checked after alias normalization. */
public final class RemoteCommand {
    private static final Set<String> ALLOWED =
            new HashSet<>(
                    Arrays.asList(
                            "guard",
                            "ml",
                            "logging",
                            "record",
                            "say",
                            "start",
                            "stop",
                            "add",
                            "remove",
                            "list",
                            "clear",
                            "all",
                            "listed",
                            "status",
                            "help",
                            "profile",
                            "seed",
                            "weapon",
                            "operator",
                            "visuals"));
    private static final Set<String> OPERATOR =
            new HashSet<>(
                    Arrays.asList(
                            "start", "stop", "add", "remove", "list", "clear", "all", "listed",
                            "weapon", "guard"));

    private RemoteCommand() {}

    public static String[] parse(String body, String botName) {
        return parse(body, botName, false);
    }

    public static String[] parse(String body, String botName, boolean limited) {
        if (body == null) return null;
        String value = body.trim();
        if (!CommandInput.isChatCommand(value)) return null;
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 32 || value.charAt(i) == 127 || value.charAt(i) == '\u00a7')
                throw new IllegalArgumentException(
                        "Remote command contains unsupported characters.");
        String remainder = value.substring(CommandInput.CHAT_PREFIX.length()).trim();
        String[] args = remainder.isEmpty() ? new String[0] : remainder.split(" +");
        if (args.length > 0 && args[0].startsWith("@")) {
            if (!args[0].substring(1).equalsIgnoreCase(botName)) return null;
            args = Arrays.copyOfRange(args, 1, args.length);
        } else if (args.length > 0 && args[0].equalsIgnoreCase("all"))
            args = Arrays.copyOfRange(args, 1, args.length);
        // Keep the requested say payload intact, including repeated spaces between words.
        if (args.length > 0
                && (args[0].equalsIgnoreCase("say") || args[0].equalsIgnoreCase("chat"))) {
            String start = args[0];
            int routeEnd = 0;
            if (remainder.startsWith("@") || remainder.regionMatches(true, 0, "all ", 0, 4)) {
                routeEnd = remainder.indexOf(' ');
                remainder = routeEnd < 0 ? "" : remainder.substring(routeEnd + 1).trim();
            }
            int end = remainder.indexOf(' ');
            args = new String[] {start, end < 0 ? "" : remainder.substring(end + 1)};
        }
        args = CommandInput.normalize(args);
        if (!ALLOWED.contains(args[0]))
            throw new IllegalArgumentException(
                    "Remote control cannot change pairing/calibration or run server commands.");
        if (args[0].equals("guard") && args.length > 1 && args[1].equalsIgnoreCase("share"))
            throw new IllegalArgumentException("Sharing is local only.");
        if (args[0].equals("guard")
                && args.length == 3
                && args[1].equalsIgnoreCase("keys")
                && args[2].equalsIgnoreCase("bind"))
            throw new IllegalArgumentException("Window binding is local only.");
        if (limited
                && args[0].equals("guard")
                && (args.length > 2 || args.length == 2 && args[1].equalsIgnoreCase("keys")))
            throw new IllegalArgumentException("Controller only.");
        if (limited && !OPERATOR.contains(args[0]))
            throw new IllegalArgumentException("Controller only.");
        return args;
    }

    public static boolean ownerMatches(
            String savedName, String savedUuid, String sender, String currentUuid) {
        if (savedName == null
                || sender == null
                || savedUuid == null
                || currentUuid == null
                || savedName.isEmpty()
                || savedUuid.isEmpty()
                || !savedName.equalsIgnoreCase(sender)) return false;
        try {
            return UUID.fromString(savedUuid).equals(UUID.fromString(currentUuid));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}