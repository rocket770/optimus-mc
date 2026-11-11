package dev.optimus.learning.recording;

import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous JSONL output and file rotation. */
final class RecordingWriter {
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(2048);
    private final AtomicLong dropped = new AtomicLong();
    private final File directory;
    private final String id = UUID.randomUUID().toString(), mode, version;
    private volatile boolean running = true, failed;
    private BufferedWriter writer;
    private long bytes;
    private int part;
    private final Thread thread;

    RecordingWriter(File directory, String mode, String version) throws IOException {
        this.directory = directory;
        this.mode = mode;
        this.version = version;
        Files.createDirectories(directory.toPath());
        openPart();
        thread = new Thread(this::run, "optimus-fight-recorder");
        thread.setDaemon(true);
        thread.start();
    }

    private void openPart() throws IOException {
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

    long dropped() {
        return dropped.get();
    }

    boolean failed() {
        return failed;
    }

    void offer(JsonObject value) {
        if (!running || failed) return;
        if (!queue.offer(value.toString())) dropped.incrementAndGet();
    }

    private void write(String row) throws IOException {
        writer.write(row);
        writer.newLine();
        bytes += row.length() + 1;
    }

    private void run() {
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

    void close(long timeoutMillis) {
        // Interrupting a writer backed by FileChannel can discard its
        // buffered final rows. Poll is bounded; let it drain normally.
        running = false;
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}