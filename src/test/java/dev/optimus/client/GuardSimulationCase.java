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
}