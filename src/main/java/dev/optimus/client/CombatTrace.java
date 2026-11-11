package dev.optimus.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** Local CSV audit trail; never sent to the server. IO failures stop the bot. */
public final class CombatTrace implements AutoCloseable {
    private final BufferedWriter writer;
    public final File file;

    public CombatTrace(File directory) throws IOException {
        Files.createDirectories(directory.toPath());
        file = File.createTempFile("combat-", ".csv", directory);
        writer =
                Files.newBufferedWriter(
                        file.toPath(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
        try {
            writer.write(
                    "utc_ms,tick,event,server,profile,seed,target,distance,yaw_step,pitch_step,yaw_error,pitch_error,policy_allowed,aiming,cooldown_ready,attacked\n");
            writer.flush();
        } catch (IOException error) {
            try {
                writer.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    public static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ') + "\"";
    }

    public void row(
            long tick,
            String event,
            String server,
            String profile,
            long seed,
            String target,
            double distance,
            float yawStep,
            float pitchStep,
            float yawError,
            float pitchError,
            boolean policy,
            boolean aiming,
            boolean ready,
            boolean attacked)
            throws IOException {
        writer.write(
                System.currentTimeMillis()
                        + ","
                        + tick
                        + ","
                        + csv(event)
                        + ","
                        + csv(server)
                        + ","
                        + csv(profile)
                        + ","
                        + seed
                        + ","
                        + csv(target)
                        + ","
                        + distance
                        + ","
                        + yawStep
                        + ","
                        + pitchStep
                        + ","
                        + yawError
                        + ","
                        + pitchError
                        + ","
                        + policy
                        + ","
                        + aiming
                        + ","
                        + ready
                        + ","
                        + attacked
                        + "\n");
        // Flush on attacks and session markers, and once per second of client ticks.
        if (attacked || !event.equals("combat") || tick % 20 == 0) writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}