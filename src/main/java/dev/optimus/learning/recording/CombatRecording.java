package dev.optimus.learning.recording;

import com.google.gson.JsonObject;

/** Observation port. Target keys and names stay in memory for anonymous outcome matching. */
public interface CombatRecording {
    boolean active();

    void beginLife(long tick);

    void observe(long tick, Object target, String name, JsonObject state);

    void end(long tick, String reason);

    void physicalDeath(long tick, String reason, JsonObject observation);

    void confirmedOutcome(long tick, boolean death, String other);

    void combatDecision(long tick, Object target, String name, JsonObject state);
}