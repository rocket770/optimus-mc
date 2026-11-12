package dev.optimus.core.guard;

/** Where a guarded player's swing was observed relative to server processing. */
public enum GuardSwingSource {
    LOCAL_KEYS,
    CLIENT_RELAY,
    SERVER_OBSERVED
}