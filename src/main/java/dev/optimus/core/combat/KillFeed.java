package dev.optimus.core.combat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Whole server-feed lines only; names are used in memory, never persisted. */
public final class KillFeed {
    private static final Pattern LINE =
            Pattern.compile(
                    "^([A-Za-z0-9_]{3,16}) was killed by ([A-Za-z0-9_]{3,16})!(?: ([0-9]+) KILL STREAK!)?$",
                    Pattern.CASE_INSENSITIVE);

    public static final class Outcome {
        public final boolean death;
        public final String other;

        private Outcome(boolean death, String other) {
            this.death = death;
            this.other = other;
        }
    }

    private final Map<String, Long> recent = new LinkedHashMap<>();

    public void reset() {
        recent.clear();
    }

    public Outcome accept(String text, String self, long tick) {
        if (text == null || self == null || !self.matches("[A-Za-z0-9_]{3,16}")) return null;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < 32 || text.charAt(i) == 127) return null;
        Matcher match = LINE.matcher(text.trim());
        if (!match.matches()) return null;
        boolean death = match.group(1).equalsIgnoreCase(self),
                kill = match.group(2).equalsIgnoreCase(self);
        if (!death && !kill) return null;
        String key = match.group().toLowerCase(Locale.ROOT);
        Long previous = recent.get(key);
        if (previous != null && tick >= previous && tick - previous < 40) return null;
        recent.put(key, tick);
        if (recent.size() > 64) recent.remove(recent.keySet().iterator().next());
        return new Outcome(death, death ? match.group(2) : match.group(1));
    }
}