package dev.optimus.client;

import java.util.ArrayList;
import java.util.List;

/** Named packet timing and native movement inputs shared by replay comparisons. */
final class GuardSimulationCase {
    final String name;
    final int ping, phase, jitter;
    final double gap, kick;
    final boolean sword, moving, single;

    GuardSimulationCase(
            String name,
            int ping,
            int phase,
            int jitter,
            double gap,
            double kick,
            boolean sword,
            boolean moving,
            boolean single) {
        if (phase < 0 || phase >= 50 || phase % 25 != 0)
            throw new IllegalArgumentException("Phase must align with the replay's 25-ms clock");
        this.name = name;
        this.ping = ping;
        this.phase = phase;
        this.jitter = jitter;
        this.gap = gap;
        this.kick = kick;
        this.sword = sword;
        this.moving = moving;
        this.single = single;
    }

    static List<GuardSimulationCase> edgeCases() {
        List<GuardSimulationCase> cases = new ArrayList<>();
        for (String input : new String[] {"keys", "observed"})
            for (String edge : new String[] {"step_down", "step_up"})
                for (int phase : new int[] {0, 25})
                    for (int jitter : new int[] {0, 10, 25, 40})
                        cases.add(
                                new GuardSimulationCase(
                                        "movement_"
                                                + edge
                                                + "_"
                                                + input
                                                + "_phase"
                                                + phase
                                                + "_jitter"
                                                + jitter,
                                        250,
                                        phase,
                                        jitter,
                                        2,
                                        0,
                                        true,
                                        false,
                                        false));
        return cases;
    }

    static List<GuardSimulationCase> releaseCases() {
        List<GuardSimulationCase> cases = new ArrayList<>();
        for (String input : new String[] {"keys", "observed"})
            for (int ping : new int[] {0, 100, 250, 400})
                for (int phase : new int[] {0, 25})
                    for (int jitter : new int[] {0, 25})
                        cases.add(
                                new GuardSimulationCase(
                                        "movement_release_"
                                                + input
                                                + "_"
                                                + ping
                                                + "_"
                                                + phase
                                                + "_"
                                                + jitter,
                                        ping,
                                        phase,
                                        jitter,
                                        2,
                                        0,
                                        true,
                                        false,
                                        false));
        return cases;
    }

    static List<GuardSimulationCase> engagementCases() {
        List<GuardSimulationCase> cases = new ArrayList<>();
        for (int ping : new int[] {200, 250, 300})
            for (double gap : new double[] {2.6, 2.8, 3.0})
                for (double kick : new double[] {.28, .4})
                    cases.add(
                            new GuardSimulationCase(
                                    "assist_chase_" + ping + "_" + gap + "_" + kick,
                                    ping,
                                    0,
                                    0,
                                    gap,
                                    kick,
                                    true,
                                    false,
                                    true));
        return cases;
    }

    static List<GuardSimulationCase> pressureCases() {
        List<GuardSimulationCase> cases = new ArrayList<>();
        for (String source : new String[] {"assist", "defense"})
            for (int ping : new int[] {100, 250, 400})
                for (int phase : new int[] {0, 25})
                    for (int jitter : new int[] {0, 25})
                        for (double gap : new double[] {2, 2.8})
                            cases.add(
                                    new GuardSimulationCase(
                                            source
                                                    + "_pressure_"
                                                    + ping
                                                    + "_"
                                                    + phase
                                                    + "_"
                                                    + jitter
                                                    + "_"
                                                    + gap,
                                            ping,
                                            phase,
                                            jitter,
                                            gap,
                                            0,
                                            true,
                                            false,
                                            true));
        return cases;
    }

    static List<GuardSimulationCase> travelCases() {
        List<GuardSimulationCase> cases = new ArrayList<>();
        for (String mode : new String[] {"stationary", "running", "damaged", "target"})
            for (int ping : new int[] {100, 250, 400})
                for (int phase : new int[] {0, 25})
                    for (String input :
                            mode.equals("target")
                                    ? new String[] {"observed"}
                                    : new String[] {"keys", "observed"})
                        cases.add(
                                new GuardSimulationCase(
                                        "movement_far_"
                                                + mode
                                                + "_catchup_"
                                                + input
                                                + "_"
                                                + ping
                                                + "_"
                                                + phase,
                                        ping,
                                        phase,
                                        0,
                                        2,
                                        0,
                                        true,
                                        false,
                                        false));
        return cases;
    }

    static List<GuardSimulationCase> waitingCases() {
        List<GuardSimulationCase> cases = new ArrayList<>();
        for (String terrain : new String[] {"patrol", "island", "flight_return"})
            for (int phase : new int[] {0, 25})
                cases.add(
                        new GuardSimulationCase(
                                "movement_waiting_" + terrain + "_" + phase,
                                250,
                                phase,
                                25,
                                2,
                                0,
                                true,
                                false,
                                false));
        return cases;
    }
}