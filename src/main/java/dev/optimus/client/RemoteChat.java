package dev.optimus.client;

import dev.optimus.core.control.CommandInput;
import java.util.regex.Pattern;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

/** Extracts server-supplied sender metadata from the header; no raw-name fallback. */
final class RemoteChat {
    static final class Envelope {
        final String sender, body;

        Envelope(String sender, String body) {
            this.sender = sender;
            this.body = body;
        }
    }

    private static String strip(String text) {
        return EnumChatFormatting.getTextWithoutFormattingCodes(text);
    }

    private static String senderAction(ClickEvent click) {
        if (click == null || click.getValue() == null) return null;
        String[] args = click.getValue().trim().split(" +");
        String name = null;
        if (click.getAction() == ClickEvent.Action.RUN_COMMAND
                && args.length >= 2
                && args[0].equalsIgnoreCase("/socialoptions")) name = args[1];
        if (click.getAction() == ClickEvent.Action.RUN_COMMAND
                && args.length >= 3
                && args[0].equalsIgnoreCase("/socialoptions")
                && args[1].equalsIgnoreCase("sb")) name = args[2];
        if (click.getAction() == ClickEvent.Action.SUGGEST_COMMAND
                && args.length == 2
                && args[0].equalsIgnoreCase("/msg")) name = args[1];
        if (click.getAction() == ClickEvent.Action.RUN_COMMAND
                && args.length == 2
                && args[0].equalsIgnoreCase("/viewprofile")) name = args[1];
        return name != null && name.matches("[A-Za-z0-9_]{3,16}") ? name : null;
    }

    static boolean chatType(byte type) {
        return type == 0 || type == 1;
    }

    static String commandBody(IChatComponent message) {
        if (message == null) return null;
        String raw = strip(message.getUnformattedText());
        if (raw == null || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) return null;
        int colon = raw.indexOf(':');
        if (colon <= 0) return null;
        String body = raw.substring(colon + 1).trim();
        return CommandInput.isChatCommand(body) ? body : null;
    }
    /** Diagnostic hint only; never authenticates or executes a command. */
    static String headerHint(IChatComponent message) {
        if (commandBody(message) == null) return null;
        String raw = strip(message.getUnformattedText());
        java.util.regex.Matcher name =
                Pattern.compile("([A-Za-z0-9_]{3,16})\\s*$")
                        .matcher(raw.substring(0, raw.indexOf(':')));
        return name.find() ? name.group(1) : null;
    }

    static String diagnosticJson(IChatComponent message) {
        String json = IChatComponent.Serializer.componentToJson(message);
        return json.length() > 8192 ? json.substring(0, 8192) : json;
    }

    static Envelope extract(IChatComponent message) {
        String body = commandBody(message);
        if (body == null) return null;
        String raw = strip(message.getUnformattedText());
        int colon = raw.indexOf(':');
        String sender = null;
        int offset = 0;
        for (IChatComponent part : message) {
            String text = strip(part.getUnformattedTextForChat());
            if (text == null) return null;
            int available = Math.min(text.length(), Math.max(0, colon - offset));
            if (available > 0) {
                String candidate = senderAction(part.getChatStyle().getChatClickEvent());
                if (candidate != null
                        && Pattern.compile(
                                        "(?<![A-Za-z0-9_])"
                                                + Pattern.quote(candidate)
                                                + "(?![A-Za-z0-9_])")
                                .matcher(text.substring(0, available))
                                .find()) {
                    if (sender != null && !sender.equalsIgnoreCase(candidate)) return null;
                    sender = candidate;
                }
            }
            offset += text.length();
            if (offset >= colon) break;
        }
        return sender == null ? null : new Envelope(sender, body);
    }
}