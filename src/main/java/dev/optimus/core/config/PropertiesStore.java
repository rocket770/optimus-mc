package dev.optimus.core.config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;

/** Local properties I/O with explicit file precedence and atomic replacement. */
public final class PropertiesStore {
    private PropertiesStore() {}

    public static Properties read(File source) throws IOException {
        Properties values = new Properties();
        if (!source.exists()) return values;
        if (!source.isFile() || source.length() > 2 * 1024 * 1024)
            throw new IOException("Settings must be a properties file under 2 MB");
        try (PushbackReader reader =
                new PushbackReader(
                        new InputStreamReader(
                                Files.newInputStream(source.toPath()), StandardCharsets.UTF_8),
                        1)) {
            int first = reader.read();
            if (first != -1 && first != '\ufeff') reader.unread(first);
            values.load(reader);
        } catch (IllegalArgumentException error) {
            throw new IOException("Malformed properties", error);
        }
        return values;
    }

    public static void write(File destination, Properties values) throws IOException {
        Path file = destination.toPath().toAbsolutePath();
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "optimus-settings-", ".tmp");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            values.store(output, "Optimus settings");
            byte[] bytes = output.toByteArray();
            int end = bytes.length;
            while (end > 0 && (bytes[end - 1] == '\n' || bytes[end - 1] == '\r')) end--;
            Files.write(temporary, Arrays.copyOf(bytes, end));
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}