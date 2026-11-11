package dev.optimus.learning;

import com.google.gson.*;
import dev.optimus.core.ml.DgmlMovementModel;
import dev.optimus.core.navigation.NearbySteering;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Load the Python-trained/exported candidate through the actual runtime reader. */
public final class LiveTrainingModelSelfTest {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        DgmlMovementModel model = DgmlMovementModel.load(new File(directory, "candidate.dgml"));
        JsonObject fixture;
        try (Reader reader =
                new InputStreamReader(
                        new FileInputStream(new File(directory, "java-checks.json")),
                        StandardCharsets.UTF_8)) {
            fixture = new JsonParser().parse(reader).getAsJsonObject();
        }
        int checks = 0;
        for (JsonElement value : fixture.getAsJsonArray("cases")) {
            JsonObject row = value.getAsJsonObject();
            JsonArray raw = row.getAsJsonArray("features");
            double[] features = new double[16];
            for (int i = 0; i < 16; i++) features[i] = raw.get(i).getAsDouble();
            NearbySteering.Input actual = model.suggest(features, 0, p -> true);
            if (actual == null || actual.ordinal() != row.get("action").getAsInt())
                throw new AssertionError("Python and Java candidate predictions differ");
            checks++;
            if (model.suggest(features, 0, p -> false) != null)
                throw new AssertionError("Candidate bypassed movement safety");
            checks++;
        }
        double[] far = new double[16];
        java.util.Arrays.fill(far, 1000);
        if (model.suggest(far, 0, p -> true) != null)
            throw new AssertionError("Candidate bypassed runtime distribution checks");
        System.out.println(
                "PASS: "
                        + (checks + 1)
                        + " Python-trained candidate, Java inference, safety and distribution checks");
    }
}