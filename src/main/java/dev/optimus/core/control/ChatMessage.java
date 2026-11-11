package dev.optimus.core.control;

/** Minecraft 1.8.9 plain-chat payload, never a forwarded server command. */
public final class ChatMessage {
    private ChatMessage() {}

    public static String validate(String raw) {
        if (raw == null) throw new IllegalArgumentException("Usage: /o say <message>");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 32 || c == 127 || c == '\u00a7')
                throw new IllegalArgumentException(
                        "Chat contains an unsupported control/formatting character.");
        }
        String message = raw.trim();
        if (message.isEmpty()) throw new IllegalArgumentException("Usage: /o say <message>");
        if (message.startsWith("/"))
            throw new IllegalArgumentException(
                    "Say accepts plain text, not server slash commands.");
        if (message.length() > 100)
            throw new IllegalArgumentException("Chat messages must be at most 100 characters.");
        return message;
    }

    public static String fromArguments(String[] arguments, int first) {
        StringBuilder message = new StringBuilder();
        for (int i = first; i < arguments.length; i++) {
            if (i > first) message.append(' ');
            message.append(arguments[i]);
        }
        return validate(message.toString());
    }
}