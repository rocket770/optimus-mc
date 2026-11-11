package dev.optimus.core.safety;

import java.util.Locale;

/** Address helpers. Legacy private training authorization is deliberately disabled. */
public final class ServerSafety {
    private ServerSafety() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String address = raw.trim().toLowerCase(Locale.ROOT);
        if (address.isEmpty()) return "";
        // Minecraft 1.8.9 primarily supports hostname or IPv4 with optional port.
        if (address.endsWith(".")) address = address.substring(0, address.length() - 1);
        if (!address.contains(":")) address += ":25565";
        return address;
    }

    private static String hostname(String raw) {
        String a = normalize(raw);
        int colon = a.lastIndexOf(':');
        return colon < 0 ? a : a.substring(0, colon);
    }

    public static boolean isHypixel(String raw) {
        String host = hostname(raw);
        return host.equals("hypixel.net")
                || host.endsWith(".hypixel.net")
                || host.equals("hypixel.io")
                || host.endsWith(".hypixel.io");
    }

    public static boolean trainingAuthorized(String connected, String configured) {
        return false;
    }
}