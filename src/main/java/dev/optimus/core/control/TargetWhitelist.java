package dev.optimus.core.control;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Exact-name, case-insensitive allowlist. Not based on nick/substring matching. */
public final class TargetWhitelist {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private final Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

    public boolean add(String username) {
        if (username == null || !NAME.matcher(username).matches()) return false;
        return names.add(username);
    }

    public boolean remove(String username) {
        return names.remove(username);
    }

    public boolean contains(String username) {
        return username != null && names.contains(username);
    }

    public Set<String> all() {
        return Collections.unmodifiableSet(names);
    }

    public void clear() {
        names.clear();
    }
}