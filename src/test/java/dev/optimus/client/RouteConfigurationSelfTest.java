package dev.optimus.client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Current route files are validated without rewriting the user's files. */
public final class RouteConfigurationSelfTest {
    private static void expect(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        String draft;
        try (InputStream source =
                RouteHints.class.getResourceAsStream("/assets/optimus/default-route-hints.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = source.read(buffer)) != -1) bytes.write(buffer, 0, n);
            draft = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        Path root = Files.createTempDirectory("optimus-legacy-routes-");
        Path primary = root.resolve("optimus-route-hints.json");
        Files.write(primary, draft.getBytes(StandardCharsets.UTF_8));
        expect(
                RouteHints.load(root.toFile()).source.contains("optimus-route-hints.json"),
                "Optimus routes load from the configured filename");
        Files.write(primary, "{}".getBytes(StandardCharsets.UTF_8));
        boolean rejected = false;
        try {
            RouteHints.load(root.toFile());
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        expect(rejected, "a malformed route is reported");
        System.out.println("PASS: route filename, format and validation checks");
    }
}