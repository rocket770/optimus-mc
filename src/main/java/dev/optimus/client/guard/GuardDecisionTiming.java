package dev.optimus.client.guard;

/** Bounded guard decision diagnostics without input or gameplay ownership. */
final class GuardDecisionTiming {
    private long count, total, maximum, slow, lastLog = Long.MIN_VALUE;

    void reset() {
        count = total = maximum = slow = 0;
        lastLog = Long.MIN_VALUE;
    }

    void record(long started, long tick, String input, boolean routing) {
        long elapsed = System.nanoTime() - started;
        count++;
        total += elapsed;
        maximum = Math.max(maximum, elapsed);
        if (elapsed > 50000000L) {
            slow++;
            if (lastLog == Long.MIN_VALUE || tick - lastLog >= 100) {
                lastLog = tick;
                org.apache.logging.log4j.LogManager.getLogger("Optimus")
                        .info(
                                "OPTIMUS_GUARD_SLOW ms="
                                        + elapsed / 1000000
                                        + " input="
                                        + input
                                        + " route="
                                        + routing);
            }
        }
    }

    String summary() {
        return "decision avg/max="
                + (count == 0 ? 0 : total / count / 1000000)
                + "/"
                + maximum / 1000000
                + "ms; slow decisions="
                + slow;
    }
}