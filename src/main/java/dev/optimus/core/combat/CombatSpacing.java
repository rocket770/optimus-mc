package dev.optimus.core.combat;

/** Small distance bands prevent repeated key/sprint toggles at engagement thresholds. */
public final class CombatSpacing {
    public enum Direction {
        HOLD,
        FORWARD,
        BACK
    }

    private Direction direction = Direction.HOLD;
    private boolean sprint;
    private boolean catchUp;
    private boolean separating, observedSeparation;
    private double filteredSeparation;

    public void reset() {
        direction = Direction.HOLD;
        sprint = catchUp = separating = observedSeparation = false;
        filteredSeparation = 0;
    }

    /** Do not disable/re-enable strafing and taps on one noisy radial observation. */
    public boolean separating(double radialSpeed) {
        if (!Double.isFinite(radialSpeed)) {
            separating = observedSeparation = false;
            return false;
        }
        double sample = Math.max(-.8, Math.min(.8, radialSpeed));
        filteredSeparation = observedSeparation ? filteredSeparation * .6 + sample * .4 : sample;
        observedSeparation = true;
        if (filteredSeparation > .1) separating = true;
        else if (filteredSeparation < .02) separating = false;
        return separating;
    }

    /** Original comparison bands; live balanced uses the momentum-aware overload. */
    public Direction direction(double distance, boolean reacting) {
        if (!Double.isFinite(distance) || distance < 0) {
            reset();
            return direction;
        }
        if (reacting) direction = distance > 1.6 ? Direction.FORWARD : Direction.HOLD;
        else if (distance < 1.15 || direction == Direction.BACK && distance < 1.35)
            direction = Direction.BACK;
        else if (distance > 1.85 || direction == Direction.FORWARD && distance > 1.7)
            direction = Direction.FORWARD;
        else direction = Direction.HOLD;
        return direction;
    }

    /** Balanced engagement: distinct acquire/release bands retain useful key pressure. */
    public Direction direction(
            double distance,
            double separation,
            double towardSpeed,
            double bodyDistance,
            boolean reachable) {
        return direction(distance, separation, towardSpeed, bodyDistance, reachable, false);
    }

    public Direction direction(
            double distance,
            double separation,
            double towardSpeed,
            double bodyDistance,
            boolean reachable,
            boolean trading) {
        if (!Double.isFinite(distance)
                || distance < 0
                || !Double.isFinite(separation)
                || !Double.isFinite(towardSpeed)
                || !Double.isFinite(bodyDistance)) {
            reset();
            return direction;
        }
        // Backing up on a predicted two-tick gap made our own acceleration
        // alternate W/HOLD/S. Reserve S for actual compression, with hysteresis.
        if (distance < 1.3 || direction == Direction.BACK && distance < 1.6)
            return direction = Direction.BACK;
        if (!reachable || bodyDistance > 2.6) return direction = Direction.FORWARD;
        double release = trading ? 1.95 : 2.25 + Math.min(.15, Math.max(0, -separation) * .4);
        double acquire = trading ? 2.4 : separation > .1 ? 2.55 : 2.75;
        // Counter actual knockback/a rushing opponent without changing the
        // release threshold; noisy velocity cannot toggle the held input.
        if (trading && (towardSpeed < -.1 || separation + towardSpeed < -.08)) acquire = 2.05;
        if (direction == Direction.FORWARD && distance > release || distance > acquire)
            direction = Direction.FORWARD;
        else direction = Direction.HOLD;
        return direction;
    }

    public boolean sprint(double distance, boolean eligible) {
        return sprint(distance, eligible, false);
    }

    public boolean sprint(double distance, boolean eligible, boolean pullingAway, boolean trading) {
        if (trading && eligible && Double.isFinite(distance) && distance > 1.9) catchUp = true;
        return sprint(distance, eligible, pullingAway);
    }

    public boolean sprint(double distance, boolean eligible, boolean pullingAway) {
        if (!eligible || !Double.isFinite(distance) || distance < 0) {
            sprint = catchUp = false;
            return false;
        }
        if (pullingAway && distance >= 2.2) catchUp = true;
        if (distance <= 1.9) catchUp = false;
        // Carry sprint into contact instead of dropping it before the first hit.
        // Forward/ground/collision eligibility still releases sprint immediately.
        sprint = catchUp || sprint ? distance > 1.9 : distance >= 2.4;
        return sprint;
    }
}