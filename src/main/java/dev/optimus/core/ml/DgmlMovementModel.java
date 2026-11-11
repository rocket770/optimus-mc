package dev.optimus.core.ml;

import dev.optimus.core.navigation.NearbySteering;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * Small versioned, allocation-light 9-way linear policy; independent of Minecraft/Forge. The policy
 * NEVER owns permission, aim, targeting, attack, or collision decisions.
 */
public final class DgmlMovementModel implements MovementModel {
    public static final String MAGIC = "DGML1";
    public static final int FEATURE_COUNT = MovementFeatures.COUNT;
    public static final int ACTION_COUNT = NearbySteering.Input.values().length;
    private final double[] means, scales, bias;
    private final double[][] weights;
    private final double[][] hiddenWeights;
    private final double[] hiddenBias;

    private DgmlMovementModel(double[] means, double[] scales, double[] bias, double[][] weights) {
        this(means, scales, bias, weights, null, null);
    }

    private DgmlMovementModel(
            double[] means,
            double[] scales,
            double[] bias,
            double[][] weights,
            double[][] hiddenWeights,
            double[] hiddenBias) {
        this.means = means;
        this.scales = scales;
        this.bias = bias;
        this.weights = weights;
        this.hiddenWeights = hiddenWeights;
        this.hiddenBias = hiddenBias;
    }

    private static double[] parse(String line, String prefix, int expected) throws IOException {
        if (line == null) throw new IOException("Missing " + prefix);
        String[] fields = line.trim().split("\\s+");
        if (fields.length != expected + 1 || !prefix.equals(fields[0]))
            throw new IOException("Invalid " + prefix + " row");
        double[] result = new double[expected];
        for (int i = 0; i < expected; i++) {
            try {
                result[i] = Double.parseDouble(fields[i + 1]);
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid numeric policy weight", ex);
            }
            if (!Double.isFinite(result[i]) || Math.abs(result[i]) > 1e6)
                throw new IOException("Non-finite/oversized policy value");
        }
        return result;
    }

    public static DgmlMovementModel load(File file) throws IOException {
        if (!file.isFile() || file.length() > 256 * 1024 || file.length() < 100)
            throw new IOException("Missing/invalid ML model: " + file);
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return read(reader);
        }
    }
    /** Load a bundled, read-only policy without making network requests. */
    public static DgmlMovementModel load(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Bundled ML model not found");
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return read(reader);
        }
    }

    private static DgmlMovementModel read(BufferedReader reader) throws IOException {
        String header = reader.readLine();
        boolean mlp = ("DGML2 " + FEATURE_COUNT + " 24 " + ACTION_COUNT).equals(header);
        if (!mlp && !(MAGIC + " " + FEATURE_COUNT + " " + ACTION_COUNT).equals(header))
            throw new IOException("ML model version/shape mismatch");
        double[] means = parse(reader.readLine(), "MEAN", FEATURE_COUNT);
        double[] scales = parse(reader.readLine(), "STD", FEATURE_COUNT);
        for (double s : scales)
            if (s < 1e-5 || s > 1e5) throw new IOException("Invalid model scale");
        if (mlp) {
            final int n = 24;
            double[] hb = parse(reader.readLine(), "B1", n);
            double[][] hw = new double[n][];
            for (int i = 0; i < n; i++) hw[i] = parse(reader.readLine(), "L" + i, FEATURE_COUNT);
            double[] outBias = parse(reader.readLine(), "B2", ACTION_COUNT);
            double[][] outWeights = new double[ACTION_COUNT][];
            for (int i = 0; i < ACTION_COUNT; i++)
                outWeights[i] = parse(reader.readLine(), "W" + i, n);
            if (reader.readLine() != null)
                throw new IOException("Unexpected trailing model content");
            return new DgmlMovementModel(means, scales, outBias, outWeights, hw, hb);
        }
        double[] bias = parse(reader.readLine(), "BIAS", ACTION_COUNT);
        double[][] weights = new double[ACTION_COUNT][];
        for (int i = 0; i < ACTION_COUNT; i++)
            weights[i] = parse(reader.readLine(), "W" + i, FEATURE_COUNT);
        if (reader.readLine() != null) throw new IOException("Unexpected trailing model content");
        return new DgmlMovementModel(means, scales, bias, weights);
    }
    /**
     * Returns null on low confidence/unsafe/invalid inputs; caller retains its original rule
     * policy.
     */
    public NearbySteering.Input suggest(
            double[] features, double threshold, Predicate<NearbySteering.Input> safe) {
        if (features == null
                || features.length != FEATURE_COUNT
                || !Double.isFinite(threshold)
                || threshold < 0.0
                || threshold > 1.0
                || safe == null) return null;
        double[] normalized = new double[FEATURE_COUNT];
        for (int f = 0; f < FEATURE_COUNT; f++) {
            if (!Double.isFinite(features[f]) || Math.abs(features[f]) > 1e6) return null;
            normalized[f] = Math.max(-10, Math.min(10, (features[f] - means[f]) / scales[f]));
            // Built-in sim model abstains on observations well outside its training range.
            if (hiddenWeights != null && Math.abs(normalized[f]) > 7.0) return null;
        }
        double[] input = normalized;
        if (hiddenWeights != null) {
            input = new double[hiddenBias.length];
            for (int h = 0; h < hiddenBias.length; h++) {
                double z = hiddenBias[h];
                for (int f = 0; f < FEATURE_COUNT; f++) z += hiddenWeights[h][f] * normalized[f];
                if (!Double.isFinite(z)) return null;
                input[h] = Math.max(0, z); // fixed 24-wide ReLU hidden layer
            }
        }
        double[] scores = new double[ACTION_COUNT];
        double max = -Double.MAX_VALUE;
        int best = 0;
        for (int a = 0; a < ACTION_COUNT; a++) {
            double z = bias[a];
            for (int f = 0; f < input.length; f++) z += weights[a][f] * input[f];
            if (!Double.isFinite(z)) return null;
            scores[a] = z;
            if (z > max) {
                max = z;
                best = a;
            }
        }
        double denominator = 0;
        for (double z : scores) denominator += Math.exp(z - max);
        double confidence = 1.0 / denominator;
        NearbySteering.Input proposed = NearbySteering.Input.values()[best];
        return confidence >= threshold && safe.test(proposed) ? proposed : null;
    }
}