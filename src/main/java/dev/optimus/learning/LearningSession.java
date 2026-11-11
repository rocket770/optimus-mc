package dev.optimus.learning;

import dev.optimus.core.ml.MovementAdvisor;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.learning.recording.CombatRecording;
import dev.optimus.learning.recording.FightRecorder;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Predicate;

/** Composition and lifecycle for optional learning. Never starts or stops gameplay. */
public final class LearningSession implements MovementAdvisor, AutoCloseable {
    private final File directory;
    private final String version;
    private final FightRecorder recorder;
    private LearningConfiguration configuration;
    private LearningRuntime runtime;
    private String issue = "";
    private boolean running;

    public LearningSession(File directory, String version) {
        this.directory = directory;
        this.version = version;
        recorder = new FightRecorder(new File(directory, "optimus-ml-data/fights"));
        try {
            configuration = LearningConfiguration.load(directory);
        } catch (IOException error) {
            configuration = LearningConfiguration.defaults(directory);
            issue = "invalid settings; using shadow defaults";
        }
        loadModels();
    }

    private void loadModels() {
        ModelRepository models = new ModelRepository(directory);
        runtime = new LearningRuntime(configuration, models.movement, models.expert);
        if (!models.issue.isEmpty()) issue = models.issue;
    }

    @Override
    public NearbySteering.Input choose(
            long tick,
            double[] features,
            NearbySteering.Input baseline,
            Predicate<NearbySteering.Input> safe) {
        return runtime.choose(tick, features, baseline, safe);
    }

    public CombatRecording recording() {
        return recorder;
    }

    public void start() {
        recorder.close();
        runtime.resetCounters();
        running = true;
        if (configuration.recording)
            recorder.start(configuration.mode.name().toLowerCase(Locale.ROOT), version);
    }

    @Override
    public void close() {
        running = false;
        recorder.close();
    }

    /** JVM shutdown closes only local recording resources; it never reads Minecraft state. */
    public void shutdown() {
        recorder.shutdown();
    }

    public String status() {
        return runtime.status() + (issue.isEmpty() ? "" : "; " + issue);
    }

    public String mlCommand(String value) {
        value = value.toLowerCase(Locale.ROOT);
        if (value.equals("status")) return status();
        if (!value.equals("off")
                && !value.equals("shadow")
                && !value.equals("live")
                && !value.equals("reload")) return "Usage: /o ml off|shadow|live|reload|status";
        try {
            if (value.equals("reload")) {
                LearningConfiguration replacement = LearningConfiguration.load(directory);
                configuration = replacement;
                issue = "";
                loadModels();
                synchronizeRecording();
                return "ML reloaded. " + status();
            }
            configuration = configuration.save("mode", value);
            runtime.configure(configuration);
            return "ML: " + value + ".";
        } catch (IOException error) {
            return "ML settings unchanged. Check config.";
        }
    }

    private void synchronizeRecording() {
        if (!configuration.recording) recorder.close();
        else if (running && !recorder.active())
            recorder.start(configuration.mode.name().toLowerCase(Locale.ROOT), version);
    }

    public String loggingCommand(String value) {
        value = value.toLowerCase(Locale.ROOT);
        if (!value.equals("status") && !value.equals("on") && !value.equals("off"))
            return "Usage: /o logging on|off|status";
        if (!value.equals("status")) {
            try {
                configuration = configuration.save("record", Boolean.toString(value.equals("on")));
            } catch (IOException error) {
                return "Logging settings unchanged. Check config.";
            }
            synchronizeRecording();
        }
        if (!configuration.recording) return "Logging off.";
        if (!running) return "Logging on (next start).";
        return recorder.active() ? "Logging on." : "Logging failed. Check logs.";
    }
}