package dev.optimus.client.guard;

import dev.optimus.core.navigation.NearbySteering;
import java.util.Locale;

/** Reads only gameplay movement/buttons from a different, mouse-captured Minecraft window. */
final class GuardWindowsKeys {
    interface Device {
        boolean gameplayFocused();

        boolean down(int key);

        long windowId();

        int processId();

        default String reason() {
            return "main game is not focused or mouse-captured";
        }
    }

    static final class Input {
        final NearbySteering.Input movement;
        final boolean jump, sneak, sprint, attack;

        Input(
                NearbySteering.Input movement,
                boolean jump,
                boolean sneak,
                boolean sprint,
                boolean attack) {
            this.movement = movement;
            this.jump = jump;
            this.sneak = sneak;
            this.sprint = sprint;
            this.attack = attack;
        }
    }

    private long boundWindow, bindingUntil;
    private int boundProcess;
    private String boundIdentity = "";
    private Device device;
    private boolean attempted;
    private String state = "not sampled";
    private long samples, movingSamples, lastMovement;

    GuardWindowsKeys() {}

    GuardWindowsKeys(Device device) {
        this.device = device;
        attempted = true;
    }

    static boolean minecraftWindow(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("minecraft") || lower.contains("lunar") || lower.contains("badlion");
    }

    static boolean capturedCursor(boolean known, int flags, boolean blank) {
        return known && ((flags & 1) == 0 || blank);
    }

    String status() {
        return state
                + "; samples="
                + samples
                + ", moving="
                + movingSamples
                + (lastMovement == 0
                        ? ""
                        : "; last movement "
                                + Math.max(0, (System.nanoTime() - lastMovement) / 1000000000L)
                                + "s ago");
    }

    void resetTelemetry() {
        samples = movingSamples = lastMovement = 0;
        if (!attempted || device != null) state = "not sampled";
    }

    String requestBinding(String identity) {
        boundWindow = 0;
        boundProcess = 0;
        boundIdentity = identity;
        bindingUntil = System.nanoTime() + 30000000000L;
        state = "waiting for F7 in the selected main window";
        return "Focus your controller's Minecraft window and press F7 within 30 seconds.";
    }

    Input read(String identity) {
        if (!attempted) {
            attempted = true;
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                try {
                    device = new WindowsGuardDevice();
                } catch (LinkageError | RuntimeException error) {
                    state = "Windows input unavailable: " + error.getClass().getSimpleName();
                }
            } else state = "Windows input unavailable on this OS";
        }
        if (device == null) return null;
        try {
            if (!device.gameplayFocused()) {
                state = device.reason();
                return null;
            }
            if (!identity.equals(boundIdentity)) {
                state = "window not bound; use /o guard keys bind";
                return null;
            }
            if (bindingUntil != 0) {
                if (System.nanoTime() > bindingUntil) {
                    bindingUntil = 0;
                    state = "window binding expired; use /o guard keys bind";
                    return null;
                }
                if (device.down(0x76)) {
                    boundWindow = device.windowId();
                    boundProcess = device.processId();
                    bindingUntil = 0;
                    state = "window bound";
                }
                return null;
            }
            if (!matchesWindow()) {
                state = "focused window is not the bound controller window";
                return null;
            }
            int forward = (device.down(0x57) ? 1 : 0) - (device.down(0x53) ? 1 : 0);
            int side = (device.down(0x41) ? 1 : 0) - (device.down(0x44) ? 1 : 0);
            NearbySteering.Input movement = NearbySteering.Input.NONE;
            for (NearbySteering.Input candidate : NearbySteering.Input.values())
                if (candidate.forward == forward && candidate.side == side) movement = candidate;
            Input input =
                    new Input(
                            movement,
                            device.down(0x20),
                            device.down(0x10),
                            device.down(0x11),
                            device.down(0x01));
            if (!device.gameplayFocused() || !matchesWindow()) {
                state = "focus changed during key sampling";
                return null;
            }
            samples++;
            state = "ready";
            if (movement != NearbySteering.Input.NONE) {
                movingSamples++;
                lastMovement = System.nanoTime();
            }
            return input;
        } catch (LinkageError | RuntimeException error) {
            state = "Windows input error: " + error.getClass().getSimpleName();
            return null;
        }
    }

    private boolean matchesWindow() {
        return boundWindow != 0
                && boundWindow == device.windowId()
                && boundProcess == device.processId();
    }
}