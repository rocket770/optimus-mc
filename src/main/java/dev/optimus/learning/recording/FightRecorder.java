package dev.optimus.learning.recording;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local observations for later training. No identity/chat data and no live weight updates. */
public final class FightRecorder implements CombatRecording, AutoCloseable {
    private final File directory;
    private Session session;
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

    public void start(String mode, String version) {
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
            session = new Session(directory, mode, version);
        } catch (IOException error) {
            System.err.println("[Optimus ML] Fight recording unavailable: " + error);
        }
    }

    public String status() {
        Session s = session;
        return s == null ? "off" : s.failed ? "failed" : "active; dropped=" + s.dropped.get();
    }

    public boolean active() {
        return session != null && !session.failed;
    }

    public void observe(long tick, Object target, String name, JsonObject state) {
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

    public void combatDecision(long tick, Object target, String name, JsonObject state) {
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

    public void end(long tick, String reason) {
        end(tick, reason, null);
    }

    public void end(long tick, String reason, JsonObject observation) {
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

    public void beginLife(long tick) {
        if (!active() || inLife || awaitingReturn) return;
        inLife = true;
        life++;
        lifeStarted = latestObservation = tick;
        lastTick = Long.MIN_VALUE;
        JsonObject start = event("life_start", tick);
        start.addProperty("life", life);
        start.addProperty("dropped_total", session.dropped.get());
        session.offer(start);
    }

    public void endLife(long tick, String reason, boolean death, JsonObject observation) {
        if (!active() || !inLife) return;
        end(tick, reason, observation);
        JsonObject end = event("life_end", tick);
        end.addProperty("life", life);
        end.addProperty("reason", reason);
        end.addProperty("death", death);
        end.addProperty("dropped_total", session.dropped.get());
        if (observation != null) end.add("observation", observation);
        session.offer(end);
        inLife = false;
        if (death) {
            lastDeathLife = life;
            lastDeathTick = tick;
            deathChatSeen = false;
        }
    }

    public void physicalDeath(long tick, String reason, JsonObject observation) {
        if (awaitingReturn) {
            awaitingReturn = false;
            return;
        }
        endLife(tick, reason, true, observation);
    }

    public void confirmedOutcome(long tick, boolean death, String other) {
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

    public void close() {
        Session s = session;
        if (s == null) return;
        endLife(latestObservation, "session_stopped", false, null);
        end(lastTick == Long.MIN_VALUE ? 0 : lastTick, "session_stopped");
        session = null;
        s.close();
    }

    private static JsonObject event(String type, long tick) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        e.addProperty("tick", tick);
        return e;
    }

    private static final class Session implements AutoCloseable {
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(2048);
        final AtomicLong dropped = new AtomicLong();
        final File directory;
        final String id = UUID.randomUUID().toString(), mode, version;
        volatile boolean running = true, failed;
        BufferedWriter writer;
        long bytes;
        int part;
        final Thread thread;

        Session(File directory, String mode, String version) throws IOException {
            this.directory = directory;
            this.mode = mode;
            this.version = version;
            Files.createDirectories(directory.toPath());
            openPart();
            thread = new Thread(this::run, "duelguard-fight-recorder");
            thread.setDaemon(true);
            thread.start();
        }

        void openPart() throws IOException {
            File file = new File(directory, "fight-" + id + "-" + (part++) + ".jsonl");
            writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
            bytes = 0;
            JsonObject meta = new JsonObject();
            meta.addProperty("type", "session");
            meta.addProperty("schema", 2);
            meta.addProperty("session", id);
            meta.addProperty("version", version);
            meta.addProperty("mode", mode);
            meta.addProperty("feature_contract", "DGML16-v1");
            meta.addProperty("pit_return_is_death", true);
            meta.addProperty("outcome_contract", "server-feed-v1");
            meta.addProperty("combat_contract", "aim-timing-v1");
            meta.addProperty(
                    "health_note",
                    "remote health and hurt are observations; hit attribution is uncertain in a multiplayer pit");
            write(meta.toString());
            writer.flush();
        }

        void offer(JsonObject value) {
            if (!running || failed) return;
            if (!queue.offer(value.toString())) dropped.incrementAndGet();
        }

        void write(String row) throws IOException {
            writer.write(row);
            writer.newLine();
            bytes += row.length() + 1;
        }

        void run() {
            long flushed = System.nanoTime();
            try {
                while (running || !queue.isEmpty()) {
                    String row;
                    try {
                        row = queue.poll(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        continue;
                    }
                    if (row != null) write(row);
                    if (bytes >= 32L * 1024 * 1024) {
                        writer.close();
                        openPart();
                    }
                    if (System.nanoTime() - flushed >= 1000000000L) {
                        writer.flush();
                        flushed = System.nanoTime();
                    }
                }
                JsonObject end = new JsonObject();
                end.addProperty("type", "session_end");
                end.addProperty("dropped", dropped.get());
                write(end.toString());
            } catch (IOException error) {
                failed = true;
                System.err.println("[Optimus ML] Fight recording stopped: " + error);
            } finally {
                try {
                    writer.close();
                } catch (IOException error) {
                    failed = true;
                    System.err.println("[Optimus ML] Fight recording close failed: " + error);
                }
            }
        }

        public void close() {
            // Interrupting a writer backed by FileChannel can discard its
            // buffered final rows. Poll is bounded; let it drain normally.
            running = false;
            try {
                thread.join(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}