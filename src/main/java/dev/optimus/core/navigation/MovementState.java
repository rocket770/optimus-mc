package dev.optimus.core.navigation;

/** Immutable ordinary-key state at the end of a controller tick. */
public final class MovementState {
    public final NearbySteering.Input direction;
    public final boolean jump, sneak, sprint;

    public static MovementState of(
            boolean forward,
            boolean back,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint) {
        int drive = forward ? 1 : back ? -1 : 0, side = left ? 1 : right ? -1 : 0;
        NearbySteering.Input direction = NearbySteering.Input.NONE;
        for (NearbySteering.Input input : NearbySteering.Input.values())
            if (input.forward == drive && input.side == side) {
                direction = input;
                break;
            }
        return new MovementState(direction, jump, sneak, sprint);
    }

    public MovementState(
            NearbySteering.Input direction, boolean jump, boolean sneak, boolean sprint) {
        this.direction = direction;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
    }
}