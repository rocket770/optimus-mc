package dev.optimus.learning;

import dev.optimus.core.config.PropertiesStore;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/** Immutable settings snapshots. Persist a replacement before changing a running session. */
public final class LearningConfiguration {
    public enum Mode {
        OFF,
        SHADOW,
        LIVE
    }

    public final Mode mode;
    public final double confidence;
    public final boolean hybrid;
    public final boolean recording;
    private final File directory;
    private final Properties values;

    private LearningConfiguration(File directory, Properties values) throws IOException {
        this.directory = directory;
        this.values = values;
        try {
            mode =
                    Mode.valueOf(
                            values.getProperty("mode", "shadow").trim().toUpperCase(Locale.ROOT));
            confidence = Double.parseDouble(values.getProperty("confidence", "0.60"));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid ML mode or confidence", error);
        }
        if (!Double.isFinite(confidence) || confidence < .35 || confidence > .99)
            throw new IOException("ML confidence must be between 0.35 and 0.99");
        String fusion = values.getProperty("fusion", "hybrid");
        if (!fusion.equals("hybrid") && !fusion.equals("neural"))
            throw new IOException("ML fusion must be hybrid or neural");
        hybrid = fusion.equals("hybrid");
        String record = values.getProperty("record", "false");
        if (!record.equals("true") && !record.equals("false"))
            throw new IOException("ML record must be true or false");
        recording = Boolean.parseBoolean(record);
    }

    public static LearningConfiguration load(File directory) throws IOException {
        return new LearningConfiguration(
                directory, PropertiesStore.read(new File(directory, "optimus-ml.properties")));
    }

    public static LearningConfiguration defaults(File directory) {
        try {
            return new LearningConfiguration(directory, new Properties());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public LearningConfiguration save(String key, String value) throws IOException {
        Properties next = new Properties();
        next.putAll(values);
        next.setProperty(key, value);
        LearningConfiguration replacement = new LearningConfiguration(directory, next);
        PropertiesStore.write(new File(directory, "optimus-ml.properties"), next);
        return replacement;
    }
}