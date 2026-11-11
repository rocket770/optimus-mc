package dev.optimus.core.ml;

import dev.optimus.core.navigation.NearbySteering;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class DgmlMovementModelSelfTest {
    private static void require(boolean condition, String error) {
        if (!condition) throw new AssertionError(error);
    }

    public static void main(String[] args) throws Exception {
        File model = File.createTempFile("dg-ml-test-", ".txt");
        model.deleteOnExit();
        StringBuilder text = new StringBuilder("DGML1 16 9\n");
        for (String s : new String[] {"MEAN", "STD"}) {
            text.append(s);
            for (int i = 0; i < 16; i++) text.append(s.equals("STD") ? " 1" : " 0");
            text.append('\n');
        }
        text.append("BIAS");
        for (int i = 0; i < 9; i++) text.append(i == 4 ? " 15" : " 0"); // class 4: FORWARD_LEFT
        text.append('\n');
        for (int a = 0; a < 9; a++) {
            text.append("W").append(a);
            for (int f = 0; f < 16; f++) text.append(" 0");
            text.append('\n');
        }
        Files.write(model.toPath(), text.toString().getBytes(StandardCharsets.US_ASCII));
        DgmlMovementModel policy = DgmlMovementModel.load(model);
        double[] features = new double[16];
        require(
                policy.suggest(features, .6, action -> true) == NearbySteering.Input.FORWARD_LEFT,
                "prediction");
        require(
                policy.suggest(features, .6, action -> false) == null,
                "unsafe prediction must abstain");
        features[0] = Double.NaN;
        require(policy.suggest(features, .6, action -> true) == null, "NaN feature must abstain");
        require(
                MovementFeatures.action(true, false, true, false)
                        == NearbySteering.Input.FORWARD_LEFT,
                "left action encoding");
        require(
                MovementFeatures.action(false, true, false, true)
                        == NearbySteering.Input.BACK_RIGHT,
                "right action encoding");
        require(
                MovementFeatures.features(
                                        3, 10, .1, 0, 0, 0, 0, 0, 0, 20, 20, 0, 0, true, false,
                                        true, false)
                                .length
                        == 16,
                "feature count");
        // The packaged synthetic-combat DGML2 model is parsed through the same code
        // that the Forge runtime uses to load resources from the built JAR.
        File bundled = new File("src/main/resources/assets/optimus/ml/movement.dgml");
        require(bundled.isFile(), "bundled default model missing");
        DgmlMovementModel pretrained = DgmlMovementModel.load(bundled);
        // The resource loading overload must agree with the file-based API.
        try (java.io.FileInputStream input = new java.io.FileInputStream(bundled)) {
            DgmlMovementModel fromResource = DgmlMovementModel.load(input);
            require(fromResource != null, "stream loading failed");
        }
        double[] combatState =
                MovementFeatures.features(
                        2.45, 2, .01, .03, 0, .02, 0, .04, 0, 20, 19, 0, 4, true, false, true,
                        false);
        // In-distribution or OOD abstention are both valid; an invalid action never is.
        NearbySteering.Input suggested = pretrained.suggest(combatState, 0.35, a -> true);
        require(
                suggested == null
                        || Arrays.asList(NearbySteering.Input.values()).contains(suggested),
                "model invalid action");
        require(pretrained.suggest(combatState, .35, a -> false) == null, "DGML2 safety veto");
        double[] unknown = combatState.clone();
        unknown[14] = 0;
        require(
                pretrained.suggest(unknown, .35, a -> true) == null,
                "out-of-distribution should abstain");
        Files.write(model.toPath(), "DGML1 16 9\nBOGUS\n".getBytes(StandardCharsets.US_ASCII));
        try {
            DgmlMovementModel.load(model);
            throw new AssertionError("Corrupt policy accepted");
        } catch (IOException expected) {
            /* fail closed */
        }
        System.out.println("DgmlMovementModelSelfTest passed");
    }
}