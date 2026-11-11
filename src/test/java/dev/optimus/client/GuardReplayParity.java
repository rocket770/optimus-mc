package dev.optimus.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Compares native decisions with captured pre-extraction behavior, including known limitations. */
final class GuardReplayParity {
    private GuardReplayParity() {}

    static void verify(List<String> trace, List<String> failures) throws Exception {
        JsonObject reference;
        try (InputStream stream =
                GuardReplayParity.class.getResourceAsStream(
                        "/dev/optimus/client/guard-reference.json.gz")) {
            if (stream == null) throw new AssertionError("Guard reference is missing");
            try (InputStreamReader reader =
                    new InputStreamReader(new GZIPInputStream(stream), StandardCharsets.UTF_8)) {
                reference = new JsonParser().parse(reader).getAsJsonObject();
            }
        }
        List<String> states = new ArrayList<>();
        for (String row : trace) if (row.split(",", 4)[2].equals("parity")) states.add(row);
        JsonArray expected = reference.getAsJsonArray("states");
        if (states.size() != expected.size())
            throw new AssertionError("Guard reference state count changed");
        for (int i = 0; i < states.size(); i++)
            if (!states.get(i).equals(expected.get(i).getAsString()))
                throw new AssertionError(
                        "Guard behavior differs at reference state " + i + ": " + states.get(i));
        List<String> knownFailures = new ArrayList<>();
        for (com.google.gson.JsonElement item : reference.getAsJsonArray("thresholdFailures"))
            knownFailures.add(item.getAsString());
        if (!knownFailures.equals(failures))
            throw new AssertionError("Guard's existing edge-threshold results changed");
        System.out.println(
                "PASS: "
                        + reference.get("scenarios").getAsInt()
                        + " guard scenarios and "
                        + states.size()
                        + " native states match the saved reference exactly");
        System.out.println(
                "Reference retains "
                        + knownFailures.size()
                        + " existing jitter/edge threshold failures; this check verifies parity.");
    }
}