package dev.optimus.learning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.optimus.core.config.PropertiesStore;
import dev.optimus.learning.recording.CombatRecording;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Exercises the real recorder through a child JVM's shutdown hooks. */
public final class LearningShutdownSelfTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            recordAndExit(Paths.get(args[0]));
            return;
        }
        Path root = Files.createTempDirectory(Paths.get("build"), "recording-shutdown-");
        Path output = root.resolve("process.log");
        Process process =
                new ProcessBuilder(
                                new File(System.getProperty("java.home"), "bin/java").getPath(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                LearningShutdownSelfTest.class.getName(),
                                root.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Recording shutdown exceeded its deadline");
        }
        if (process.exitValue() != 0)
            throw new AssertionError(
                    new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        List<Path> parts;
        try (Stream<Path> paths = Files.list(root.resolve("optimus-ml-data/fights"))) {
            parts = paths.collect(Collectors.toList());
        }
        int frames = 0, lifeEnds = 0, sessionEnds = 0;
        for (Path part : parts) {
            for (String line : Files.readAllLines(part, StandardCharsets.UTF_8)) {
                JsonObject row = new JsonParser().parse(line).getAsJsonObject();
                String type = row.get("type").getAsString();
                if (type.equals("frame")) frames++;
                if (type.equals("life_end")) lifeEnds++;
                if (type.equals("session_end")) {
                    sessionEnds++;
                    if (row.get("dropped").getAsLong() != 0)
                        throw new AssertionError("Shutdown lost queued observations");
                }
            }
        }
        if (frames != 500 || lifeEnds != 1 || sessionEnds != 1)
            throw new AssertionError("Incomplete recording after JVM shutdown");
        System.out.println(
                "PASS: JVM shutdown flushed 500 observations and sealed the life/session");
    }

    private static void recordAndExit(Path root) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mode", "off");
        properties.setProperty("record", "true");
        PropertiesStore.write(root.resolve("optimus-ml.properties").toFile(), properties);
        LearningSession session = new LearningSession(root.toFile(), "shutdown-test");
        Runtime.getRuntime().addShutdownHook(new Thread(session::shutdown));
        session.start();
        CombatRecording recording = session.recording();
        Object opponent = new Object();
        for (int tick = 1; tick <= 500; tick++)
            recording.observe(tick, opponent, "FixtureRival", new JsonObject());
        System.exit(0);
    }
}