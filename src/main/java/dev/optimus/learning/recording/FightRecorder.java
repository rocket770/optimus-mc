package dev.optimus.learning.recording;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;

/** Local observations for later training. No identity/chat data and no live weight updates. */
public final class FightRecorder implements CombatRecording, AutoCloseable {
    private final File directory;
    private RecordingWriter session;
    private Object opponent;
    private int episode;
    private int life;
    private boolean inLife;
    private long lifeStarted, latestObservation;
    private long lastTick = Long.MIN_VALUE;
    private boolean awaitingReturn, deathChatSeen;
    private int lastDeathLife;
    private long lastDeathTick = Long.MIN_VALUE;
    private long outcomeSerial, decisionSerial;
    private final ArrayDeque<SeenTarget> targets = new ArrayDeque<>();

    private static final class SeenTarget {
        final Object target;
        final String name;
        final int life, episode;
        long tick;
        boolean credited;

        SeenTarget(Object target, String name, int life, int episode, long tick) {
            this.target = target;
            this.name = name;
            this.life = life;
            this.episode = episode;
            this.tick = tick;
        }
    }

    public FightRecorder(File directory) {
        this.directory = directory;
    }

    public synchronized void start(String mode, String version) {
        close();
        episode = life = 0;
        inLife = false;
        opponent = null;
        lastTick = Long.MIN_VALUE;
        awaitingReturn = deathChatSeen = false;
        lastDeathLife = 0;
        lastDeathTick = Long.MIN_VALUE;
        outcomeSerial = decisionSerial = 0;
        targets.clear();
        try {
            session = new RecordingWriter(directory, mode, version);
        } catch (IOException error) {
            System.err.println("[Optimus ML] Fight recording unavailable: " + error);
        }
    }

    public synchronized String status() {
        RecordingWriter s = session;
        return s == null ? "off" : s.failed() ? "failed" : "active; dropped=" + s.dropped();
    }

    public synchronized boolean active() {
        return session != null && !session.failed();
    }

    public synchronized void observe(long tick, Object target, String name, JsonObject state) {
        if (!select(tick, target, name)) return;
        if (tick == lastTick) return;
        lastTick = tick;
        if (!targets.isEmpty()) targets.peekLast().tick = tick;
        state.addProperty("type", "frame");
        state.addProperty("tick", tick);
        state.addProperty("episode", episode);
        state.addProperty("life", life);
        session.offer(state);
    }

    private boolean select(long tick, Object target, String name) {
        if (!active()) return false;
        beginLife(tick);
        if (!inLife) return false;
        if (target == null) {
            end(tick, "target_lost");
            return false;
        }
        if (target != opponent) {
            end(tick, "target_changed");
            opponent = target;
            episode++;
            targets.addLast(new SeenTarget(target, name == null ? "" : name, life, episode, tick));
            if (targets.size() > 64) targets.removeFirst();
            JsonObject start = event("episode_start", tick);
            start.addProperty("episode", episode);
            start.addProperty("life", life);
            session.offer(start);
        }
        if (!targets.isEmpty()) targets.peekLast().tick = tick;
        latestObservation = Math.max(latestObservation, tick);
        return true;
    }

    public synchronized void combatDecision(
            long tick, Object target, String name, JsonObject state) {
        if (target == null) {
            if (!active()) return;
            beginLife(tick);
            if (!inLife) return;
            end(tick, "target_unresolved");
            latestObservation = Math.max(latestObservation, tick);
            state.addProperty("type", "combat_animation");
        } else {
            if (!select(tick, target, name)) return;
            state.addProperty("type", "combat_decision");
            state.addProperty("episode", episode);
        }
        state.addProperty("tick", tick);
        state.addProperty("life", life);
        state.addProperty("decision_id", ++decisionSerial);
        session.offer(state);
    }

    public synchronized void end(long tick, String reason) {
        end(tick, reason, null);
    }

    public synchronized void end(long tick, String reason, JsonObject observation) {
        if (session != null && opponent != null) {
            JsonObject end = event("episode_end", tick);
            end.addProperty("episode", episode);
            end.addProperty("reason", reason);
            end.addProperty("life", life);
            if (observation != null) end.add("observation", observation);
            session.offer(end);
        }
        opponent = null;
    }

    public synchronized void beginLife(long tick) {
        if (!active() || inLife || awaitingReturn) return;
        inLife = true;
        life++;
        lifeStarted = latestObservation = tick;
        lastTick = Long.MIN_VALUE;
        JsonObject start = event("life_start", tick);
        start.addProperty("life", life);
        start.addProperty("dropped_total", session.dropped());
        session.offer(start);
    }

    public synchronized void endLife(
            long tick, String reason, boolean death, JsonObject observation) {
        if (!active() || !inLife) return;
        end(tick, reason, observation);
        JsonObject end = event("life_end", tick);
        end.addProperty("life", life);
        end.addProperty("reason", reason);
        end.addProperty("death", death);
        end.addProperty("dropped_total", session.dropped());
        if (observation != null) end.add("observation", observation);
        session.offer(end);
        inLife = false;
        if (death) {
            lastDeathLife = life;
            lastDeathTick = tick;
            deathChatSeen = false;
        }
    }

    public synchronized void physicalDeath(long tick, String reason, JsonObject observation) {
        if (awaitingReturn) {
            awaitingReturn = false;
            return;
        }
        endLife(tick, reason, true, observation);
    }

    public synchronized void confirmedOutcome(long tick, boolean death, String other) {
        if (!active()) return;
        JsonObject row = event("combat_outcome", tick);
        row.addProperty("source", "server_kill_feed");
        if (death) {
            if (lastDeathLife > 0
                    && !deathChatSeen
                    && tick >= lastDeathTick
                    && tick - lastDeathTick <= 100) {
                row.addProperty("life", lastDeathLife);
            } else {
                if (!inLife) return;
                endLife(tick, "self_death_chat", true, null);
                awaitingReturn = true;
                row.addProperty("life", life);
            }
            deathChatSeen = true;
            row.addProperty("outcome", "death");
        } else {
            SeenTarget matched = null;
            for (java.util.Iterator<SeenTarget> iterator = targets.descendingIterator();
                    iterator.hasNext(); ) {
                SeenTarget seen = iterator.next();
                if (tick >= seen.tick
                        && tick - seen.tick <= 100
                        && seen.name.equalsIgnoreCase(other)) {
                    matched = seen;
                    break;
                }
            }
            if (matched != null && matched.credited) return;
            if (life == 0) return;
            row.addProperty("outcome", "kill");
            row.addProperty("life", matched == null ? life : matched.life);
            row.addProperty("matched_target", matched != null);
            if (matched != null) {
                matched.credited = true;
                row.addProperty("episode", matched.episode);
                if (matched.target == opponent
                        && matched.life == life
                        && matched.episode == episode) end(tick, "opponent_killed_chat");
            }
        }
        row.addProperty("outcome_id", ++outcomeSerial);
        session.offer(row);
    }

    public synchronized void close() {
        finish(250);
    }

    public synchronized void shutdown() {
        finish(2000);
    }

    private void finish(long timeoutMillis) {
        RecordingWriter s = session;
        if (s == null) return;
        endLife(latestObservation, "session_stopped", false, null);
        end(lastTick == Long.MIN_VALUE ? 0 : lastTick, "session_stopped");
        session = null;
        s.close(timeoutMillis);
    }

    private static JsonObject event(String type, long tick) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        e.addProperty("tick", tick);
        return e;
    }
}