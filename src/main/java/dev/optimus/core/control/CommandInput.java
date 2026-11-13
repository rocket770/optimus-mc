package dev.optimus.core.control;

import java.util.Arrays;
import java.util.Locale;

/** Shared command aliases and no-argument control validation. */
public final class CommandInput {
    public static final String CHAT_PREFIX = "!o";

    private CommandInput() {}

    public static boolean isChatCommand(String body) {
        return body != null
                && (body.equalsIgnoreCase(CHAT_PREFIX)
                        || body.regionMatches(
                                true, 0, CHAT_PREFIX + " ", 0, CHAT_PREFIX.length() + 1));
    }

    public static String[] normalize(String[] raw) {
        if (raw.length == 0) return new String[] {"help"};
        String[] args = raw.clone();
        String action = args[0].toLowerCase(Locale.ROOT);
        if ("guard".equals(action)
                && args.length > 1
                && args[1].equalsIgnoreCase("exceptions")) {
            args = Arrays.copyOfRange(args, 1, args.length);
            action = "exceptions";
        }
        if ("target".equals(action) || "targets".equals(action)) {
            if (args.length == 1) return new String[] {"list"};
            args = Arrays.copyOfRange(args, 1, args.length);
            action = args[0].toLowerCase(Locale.ROOT);
            if (!"add".equals(action)
                    && !"remove".equals(action)
                    && !"list".equals(action)
                    && !"clear".equals(action)
                    && !"all".equals(action)
                    && !"listed".equals(action)
                    && !"exceptions".equals(action))
                throw new IllegalArgumentException(
                        "Usage: /o target add <name> | remove <name> | list | clear | all | listed | exceptions");
        }
        if ("startz".equals(action) || "on".equals(action)) action = "start";
        if ("stopz".equals(action) || "off".equals(action)) action = "stop";
        if ("commands".equals(action)) action = "help";
        if ("chat".equals(action)) action = "say";
        args[0] = action;
        if (("start".equals(action)
                        || "stop".equals(action)
                        || "status".equals(action)
                        || "list".equals(action)
                        || "clear".equals(action)
                        || "all".equals(action)
                        || "listed".equals(action)
                        || "help".equals(action)
                        || "locraw".equals(action))
                && args.length != 1) throw new IllegalArgumentException("Usage: /o " + action);
        return args;
    }
}