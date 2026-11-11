package dev.optimus.core.safety;

/** All profiles share these fail-closed lobby and pit predicates. */
public final class ControlPolicy {
    private ControlPolicy() {}

    public static boolean allowSpeech(boolean verifiedDuelsLobby, boolean playerPresent) {
        return verifiedDuelsLobby && playerPresent;
    }

    public static boolean allowPitEntry(
            boolean bodyInsidePit, boolean safeGround, boolean committedJump) {
        return bodyInsidePit && safeGround && !committedJump;
    }

    public static boolean allowControl(
            boolean armed,
            boolean verifiedLobby,
            boolean boundariesBound,
            boolean playerInPit,
            boolean alive,
            boolean guiOpen) {
        return armed && verifiedLobby && boundariesBound && playerInPit && alive && !guiOpen;
    }

    public static boolean allowAttack(
            boolean armed,
            boolean verifiedLobby,
            boolean playerInPit,
            boolean targetInPit,
            boolean targetOnWhitelist,
            boolean withinReach,
            boolean lineOfSight,
            boolean guiOpen) {
        return armed
                && verifiedLobby
                && playerInPit
                && targetInPit
                && targetOnWhitelist
                && withinReach
                && lineOfSight
                && !guiOpen;
    }

    public static boolean allowNavigation(
            boolean armed,
            boolean verifiedDuelsLobby,
            boolean boundariesBound,
            boolean insideTravelArea,
            boolean alive,
            boolean guiOpen) {
        return armed
                && verifiedDuelsLobby
                && boundariesBound
                && insideTravelArea
                && alive
                && !guiOpen;
    }
}