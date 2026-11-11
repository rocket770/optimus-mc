package dev.optimus.core.ml;

import dev.optimus.core.navigation.NearbySteering;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class HybridMovementPolicySelfTest {
    static void check(boolean result, String note) {
        if (!result) throw new AssertionError(note);
    }

    static File model(int action) throws Exception {
        File file = File.createTempFile("hybrid-", ".dgml");
        file.deleteOnExit();
        StringBuilder s = new StringBuilder("DGML1 16 9\n");
        for (String x : new String[] {"MEAN", "STD"}) {
            s.append(x);
            for (int i = 0; i < 16; i++) s.append(x.equals("STD") ? " 1" : " 0");
            s.append('\n');
        }
        s.append("BIAS");
        for (int i = 0; i < 9; i++) s.append(i == action ? " 20" : " 0");
        s.append('\n');
        for (int a = 0; a < 9; a++) {
            s.append("W").append(a);
            for (int f = 0; f < 16; f++) s.append(" 0");
            s.append('\n');
        }
        Files.write(file.toPath(), s.toString().getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    static double[] state(double distance, double ownHurt, double targetHurt) {
        return MovementFeatures.features(
                distance,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                20,
                20,
                (int) (ownHurt * 10),
                (int) (targetHurt * 10),
                true,
                false,
                true,
                false);
    }

    public static void main(String[] args) throws Exception {
        DgmlMovementModel forward = DgmlMovementModel.load(model(1));
        DgmlMovementModel left = DgmlMovementModel.load(model(2));
        DgmlMovementModel back = DgmlMovementModel.load(model(6));
        HybridMovementPolicy mix = new HybridMovementPolicy(forward, null, .6, true);
        check(
                mix.choose(state(1.70, 0, 0.8), NearbySteering.Input.FORWARD, a -> true).action
                        == NearbySteering.Input.NONE,
                "combo window should brake closing drive");
        check(
                mix.choose(
                                        state(1.70, 0, 0.8),
                                        NearbySteering.Input.FORWARD,
                                        a -> a != NearbySteering.Input.NONE)
                                .action
                        == NearbySteering.Input.FORWARD,
                "safety veto must retain original");
        check(
                mix.choose(state(2.90, 0, 0.7), NearbySteering.Input.NONE, a -> true).action
                        == NearbySteering.Input.FORWARD,
                "combo rehit pursuit");
        check(
                mix.choose(state(4.2, 0, 0), NearbySteering.Input.NONE, a -> true).action
                        == NearbySteering.Input.FORWARD,
                "learned movement outside tactical window");
        check(
                mix.choose(state(4.2, 0, 0), NearbySteering.Input.NONE, a -> false).action
                        == NearbySteering.Input.NONE,
                "unsafe learned action must use original");
        HybridMovementPolicy both = new HybridMovementPolicy(forward, back, .6, true);
        check(
                both.choose(state(4.2, 0, 0), NearbySteering.Input.LEFT, a -> true).action
                        == NearbySteering.Input.LEFT,
                "opposing expert directions must abstain");
        both = new HybridMovementPolicy(forward, forward, .6, false);
        check(
                both.choose(state(4.2, 0, 0), NearbySteering.Input.NONE, a -> true).source
                        == HybridMovementPolicy.Source.FUSED,
                "agreeing experts should fuse");
        both = new HybridMovementPolicy(forward, left, .6, true);
        check(
                both.choose(state(4.2, 0, 0), NearbySteering.Input.NONE, a -> true).action
                        == NearbySteering.Input.NONE,
                "forward vs sideways conflict must abstain");
        double[] invalid = state(2, 0, .8);
        invalid[3] = Double.NaN;
        check(
                mix.choose(invalid, NearbySteering.Input.BACK, a -> true).action
                        == NearbySteering.Input.BACK,
                "invalid observations must use original");
        check(
                HybridMovementPolicy.comboWindow(state(3, 0, .8)) == NearbySteering.Input.FORWARD,
                "combo advisor rehit spacing");
        check(
                HybridMovementPolicy.comboWindow(state(3, .8, 0)) == null,
                "no coaching during opponent-favored combo");
        System.out.println("HybridMovementPolicySelfTest passed");
    }
}