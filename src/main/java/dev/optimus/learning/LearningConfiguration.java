package dev.optimus.learning;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
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
        Properties values = new Properties();
        File primary = new File(directory, "optimus-ml.properties");
        File source = primary.isFile() ? primary : new File(directory, "duelguard-ml.properties");
        if (source.isFile()) {
            try (InputStream input = Files.newInputStream(source.toPath())) {
                values.load(input);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid ML properties", error);
            }
        }
        return new LearningConfiguration(directory, values);
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
        Path folder = directory.toPath().toAbsolutePath();
        Files.createDirectories(folder);
        Path temporary = Files.createTempFile(folder, "optimus-ml-", ".tmp");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            next.store(bytes, "Optimus ML");
            byte[] contents = bytes.toByteArray();
            int end = contents.length;
            while (end > 0 && (contents[end - 1] == '\n' || contents[end - 1] == '\r')) end--;
            Files.write(temporary, Arrays.copyOf(contents, end));
            Path destination = folder.resolve("optimus-ml.properties");
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return replacement;
    }
}