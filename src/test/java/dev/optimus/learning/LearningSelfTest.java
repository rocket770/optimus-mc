package dev.optimus.learning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.optimus.core.ml.MovementAdvisor;
import dev.optimus.core.ml.MovementModel;
import dev.optimus.core.navigation.NearbySteering;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Model substitution, persistent modes, atomic failure and real asynchronous dataset checks. */
public final class LearningSelfTest {
    private static int checks;

    private static void expect(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] arguments) throws Exception {
        Path root = Files.createTempDirectory("optimus-learning-");
        LearningConfiguration config = LearningConfiguration.defaults(root.toFile());
        MovementModel forward = (features, confidence, safe) -> NearbySteering.Input.FORWARD;
        LearningRuntime runtime = new LearningRuntime(config, forward, null);
        double[] features = new double[16];
        expect(
                runtime.choose(1, features, NearbySteering.Input.LEFT, input -> true)
                        == NearbySteering.Input.LEFT,
                "shadow inference preserves the rule controller");
        expect(
                runtime.status().contains("suggested=1; applied=0/1"),
                "shadow has observable suggestions without applied actions");
        config = config.save("mode", "live");
        runtime.configure(config);
        expect(
                runtime.choose(2, features, NearbySteering.Input.LEFT, input -> true)
                        == NearbySteering.Input.FORWARD,
                "live mode can use a substituted model through the advisor port");
        expect(
                runtime.choose(3, features, NearbySteering.Input.LEFT, input -> false)
                        == NearbySteering.Input.LEFT,
                "the runtime independently rejects a model that ignores its geometry predicate");
        runtime.configure(config.save("mode", "off"));
        expect(
                runtime.choose(4, features, NearbySteering.Input.BACK, input -> true)
                        == NearbySteering.Input.BACK,
                "off mode is an exact rule-controller bypass");
        LearningRuntime failing =
                new LearningRuntime(
                        config,
                        (values, threshold, safe) -> {
                            throw new IllegalStateException("fixture");
                        },
                        null);
        expect(
                failing.choose(5, features, NearbySteering.Input.LEFT, input -> true)
                        == NearbySteering.Input.LEFT,
                "failed inference cannot interrupt combat");
        expect(failing.status().contains("errors=1"), "inference failures are visible");
        Path properties = root.resolve("optimus-ml.properties");
        Files.write(
                properties,
                "mode=live\nconfidence=0.77\ncustom.option=keep".getBytes(StandardCharsets.UTF_8));
        LearningSession session = new LearningSession(root.toFile(), "test");
        MovementAdvisor stableAdvisor = session;
        try {
            expect(
                    session.status().contains("model=true"),
                    "the restored bundled checkpoint loads");
            expect(
                    session.loggingCommand("on").equals("Logging on (next start)."),
                    "logging cannot start gameplay");
            expect(
                    new String(Files.readAllBytes(properties), StandardCharsets.ISO_8859_1)
                            .contains("custom.option=keep"),
                    "settings updates preserve unrelated properties");
            session.start();
            expect(session.recording().active(), "logging starts with an explicit session");
            Object opponent = new Object();
            JsonObject frame = new JsonObject();
            frame.addProperty("sample", "anonymous");
            session.recording().observe(10, opponent, "PrivateOpponent", frame);
            expect(
                    session.mlCommand("shadow").equals("ML: shadow.")
                            && session.recording().active(),
                    "changing ML mode preserves the active recording");
            expect(
                    session.mlCommand("reload").startsWith("ML reloaded.")
                            && session == stableAdvisor,
                    "reload preserves the injected advisor reference");
            session.recording().confirmedOutcome(11, false, "PrivateOpponent");
            session.recording().end(11, "target_lost");
            session.recording().physicalDeath(12, "self_death_pit_return", null);
            session.recording().physicalDeath(13, "self_dead_observed", null);
            expect(
                    session.loggingCommand("off").equals("Logging off."),
                    "off drains and closes the writer");
            Path capture;
            try (java.util.stream.Stream<Path> paths =
                    Files.list(root.resolve("optimus-ml-data/fights"))) {
                capture = paths.findFirst().get();
            }
            List<String> lines = Files.readAllLines(capture, StandardCharsets.UTF_8);
            String contents = String.join("\n", lines);
            expect(
                    !contents.contains("PrivateOpponent"),
                    "identities used for matching never enter recordings");
            int deaths = 0;
            for (String line : lines) {
                JsonObject row = new JsonParser().parse(line).getAsJsonObject();
                if (row.get("type").getAsString().equals("life_end")
                        && row.get("death").getAsBoolean()) deaths++;
            }
            expect(
                    contents.contains("\"matched_target\":true"),
                    "recent anonymous opponent episodes receive matched kill outcomes");
            expect(deaths == 1, "target loss cannot hide or duplicate a pit-return death");
            expect(
                    lines.get(lines.size() - 1).contains("session_end"),
                    "writer closes with a complete session boundary");
            Path backup = root.resolve("saved.properties");
            Files.move(properties, backup);
            Files.createDirectory(properties);
            Files.write(properties.resolve("blocker"), new byte[] {1});
            expect(
                    session.mlCommand("live").contains("unchanged")
                            && session.status().startsWith("ML: shadow"),
                    "failed atomic persistence leaves the effective mode unchanged");
            Files.delete(properties.resolve("blocker"));
            Files.delete(properties);
            Files.move(backup, properties);
            expect(
                    session.loggingCommand("bogus").startsWith("Usage:"),
                    "invalid commands cannot toggle logging");
        } finally {
            session.close();
        }
        Path saved = Files.createTempDirectory("optimus-saved-ml-");
        Files.write(
                saved.resolve("optimus-ml.properties"),
                "\ufeffmode=live\nrecord=true\ncustom.value=keep".getBytes(StandardCharsets.UTF_8));
        LearningConfiguration loaded = LearningConfiguration.load(saved.toFile());
        expect(
                loaded.mode == LearningConfiguration.Mode.LIVE && loaded.recording,
                "saved mode and recording are read with a UTF-8 BOM");
        loaded.save("mode", "shadow");
        expect(
                dev.optimus.core.config.PropertiesStore.read(
                                saved.resolve("optimus-ml.properties").toFile())
                        .getProperty("custom.value")
                        .equals("keep"),
                "mode changes preserve unrelated settings");
        Files.delete(saved.resolve("optimus-ml.properties"));
        Files.createDirectory(saved.resolve("optimus-ml.properties"));
        boolean invalidPath = false;
        try {
            LearningConfiguration.load(saved.toFile());
        } catch (IOException expected) {
            invalidPath = true;
        }
        expect(invalidPath, "an invalid current settings path is rejected");
        for (String command : new String[] {"ml live", "logging on", "record off"}) {
            String body = "!o " + command;
            expect(
                    dev.optimus.core.control.RemoteCommand.parse(body, "Bot") != null,
                    "controller can use " + command);
            boolean denied = false;
            try {
                dev.optimus.core.control.RemoteCommand.parse(body, "Bot", true);
            } catch (IllegalArgumentException expected) {
                denied = true;
            }
            expect(denied, "operator cannot use " + command);
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " learning mode, abstraction, persistence and recording checks");
    }
}