package dev.optimus.core.navigation;

import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.Vec3Point;

/** Bound pre-launch alignment attempts; a failed landing is temporarily omitted from routes. */
public final class JumpPreparation {
    private Vec3Point landing, origin, progress, rejectedLanding, rejectedOrigin;
    private long started, progressAt, rejectedUntil;
    private double bestError;

    public void reset() {
        landing = origin = progress = rejectedLanding = rejectedOrigin = null;
        rejectedUntil = 0;
    }

    public void launched() {
        landing = origin = progress = null;
    }

    public void reject(Vec3Point from, Vec3Point to, long tick) {
        rejectedLanding = to;
        rejectedOrigin = from;
        rejectedUntil = tick + 100;
        launched();
    }

    public boolean allowed(Vec3Point from, Vec3Point to, long tick) {
        return rejectedLanding == null
                || tick >= rejectedUntil
                || from.horizontalDistanceSq(rejectedOrigin) > 4
                || to.horizontalDistanceSq(rejectedLanding) > .04
                || Math.abs(to.y - rejectedLanding.y) > .25;
    }

    public boolean observe(Vec3Point from, Vec3Point to, double yawError, long tick) {
        if (!allowed(from, to, tick)) return false;
        double error = Math.abs(CombatTurn.wrap((float) yawError));
        if (landing == null
                || landing.horizontalDistanceSq(to) > .04
                || Math.abs(landing.y - to.y) > .25) {
            landing = to;
            origin = progress = from;
            started = progressAt = tick;
            bestError = error;
            return true;
        }
        if (from.horizontalDistanceSq(progress) > .08 * .08 || error < bestError - .5) {
            progress = from;
            progressAt = tick;
            bestError = error;
        }
        if (tick - started <= 60 && tick - progressAt <= 24) return true;
        reject(origin, landing, tick);
        return false;
    }
}