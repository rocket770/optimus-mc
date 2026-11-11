package dev.optimus.client.guard;

import com.sun.jna.*;
import com.sun.jna.win32.StdCallLibrary;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.guard.GuardFollow;
import dev.optimus.core.guard.GuardInputPrediction;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.NearbySteering;
import java.util.*;

/** Only guard-specific input and 250-ms delayed-observation tests. */
public final class GuardWindowsKeysSelfTest {
    private interface CursorUser extends StdCallLibrary {
        Pointer CreateIconIndirect(Pointer info);

        Pointer LoadCursorW(Pointer instance, Pointer resource);

        int DestroyCursor(Pointer cursor);
    }

    private interface CursorGdi extends StdCallLibrary {
        Pointer CreateBitmap(int width, int height, int planes, int bits, byte[] pixels);

        int DeleteObject(Pointer bitmap);
    }

    private static void nativeCursorChecks() {
        CursorUser user = (CursorUser) Native.loadLibrary("user32", CursorUser.class);
        CursorGdi gdi = (CursorGdi) Native.loadLibrary("gdi32", CursorGdi.class);
        Pointer color = null, mask = null, blank = null;
        try {
            color = gdi.CreateBitmap(32, 32, 1, 32, new byte[32 * 32 * 4]);
            byte[] transparentMask = new byte[32 * 32 / 8];
            Arrays.fill(transparentMask, (byte) 255);
            mask = gdi.CreateBitmap(32, 32, 1, 1, transparentMask);
            expect(color != null && mask != null, "native test cursor bitmaps allocate");
            Memory info = new Memory(Native.POINTER_SIZE == 8 ? 32 : 20);
            info.clear();
            int bitmapOffset = Native.POINTER_SIZE == 8 ? 16 : 12;
            info.setPointer(bitmapOffset, mask);
            info.setPointer(bitmapOffset + Native.POINTER_SIZE, color);
            blank = user.CreateIconIndirect(info);
            expect(blank != null, "off-screen transparent test cursor creates");
            GuardCursorCapture capture = new GuardCursorCapture();
            expect(
                    capture.invisible(blank),
                    "LWJGL-style blank cursor qualifies even when fullscreen clip equals desktop");
            Pointer arrow = user.LoadCursorW(null, new Pointer(32512));
            expect(
                    arrow != null && !capture.invisible(arrow),
                    "visible menu arrow never qualifies as a captured blank cursor");
            expect(
                    !capture.invisible(null),
                    "failed or missing cursor-image queries do not authorize input");
        } finally {
            if (blank != null) user.DestroyCursor(blank);
            if (mask != null) gdi.DeleteObject(mask);
            if (color != null) gdi.DeleteObject(color);
        }
    }

    private static int checks;

    private static void expect(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] arguments) throws Exception {
        final boolean[] keys = new boolean[256], focus = {false}, switchDuringSample = {false};
        final int[] reads = {0}, process = {21};
        final long[] window = {11};
        GuardWindowsKeys reader =
                new GuardWindowsKeys(
                        new GuardWindowsKeys.Device() {
                            public long windowId() {
                                return window[0];
                            }

                            public int processId() {
                                return process[0];
                            }

                            @Override
                            public boolean gameplayFocused() {
                                return focus[0];
                            }

                            @Override
                            public boolean down(int key) {
                                expect(
                                        Arrays.asList(
                                                        0x57, 0x53, 0x41, 0x44, 0x20, 0x10, 0x11,
                                                        0x01, 0x76)
                                                .contains(key),
                                        "only explicit gameplay keys are sampled");
                                reads[0]++;
                                if (switchDuringSample[0]) window[0] = 12;
                                return keys[key];
                            }
                        });
        keys[0x57] = true;
        expect(
                reader.read("main-identity") == null && reads[0] == 0,
                "unfocused or cursor-released game never reads physical keys");
        focus[0] = true;
        GuardWindowsKeys.Input automatic = reader.read("main-identity");
        expect(
                automatic != null && automatic.movement == NearbySteering.Input.FORWARD,
                "automatic focused-game input starts without a manual binding");
        reader.requestBinding("main-identity");
        keys[0x76] = true;
        expect(reader.read("main-identity") == null, "F7 binds without replaying held movement");
        keys[0x76] = false;
        expect(
                reader.read("main-identity").movement == NearbySteering.Input.FORWARD,
                "W sampled directly without a mod on the main");
        keys[0x57] = false;
        expect(
                reader.read("main-identity").movement == NearbySteering.Input.NONE,
                "key-up applied immediately");
        keys[0x44] = true;
        keys[0x20] = true;
        keys[0x10] = true;
        keys[0x11] = true;
        GuardWindowsKeys.Input input = reader.read("main-identity");
        expect(
                input.movement == NearbySteering.Input.RIGHT
                        && input.jump
                        && input.sneak
                        && input.sprint,
                "strafe, jump, sneak and sprint sampled together");
        expect(
                GuardWindowsKeys.minecraftWindow("Lunar Client (1.8.9)")
                        && GuardWindowsKeys.minecraftWindow("Minecraft 1.8.9"),
                "main client does not need Forge");
        expect(
                !GuardWindowsKeys.minecraftWindow("Notepad")
                        && !GuardWindowsKeys.minecraftWindow("Windows PowerShell"),
                "ordinary foreground apps do not qualify");
        expect(GuardWindowsKeys.minecraftWindow("Lunar 1.8.9"), "short Lunar title qualifies");
        expect(
                reader.status().contains("moving=3"),
                "status retains evidence of sampled movement across focus changes");
        focus[0] = false;
        reader.read("main-identity");
        expect(
                reader.status().contains("not focused") && reader.status().contains("moving=3"),
                "fallback reason and previous direct input remain visible");
        expect(
                GuardWindowsKeys.capturedCursor(true, 1, true),
                "a blank captured cursor qualifies in windowed and fullscreen games");
        expect(
                !GuardWindowsKeys.capturedCursor(true, 1, false),
                "a visible menu cursor cannot qualify even if fullscreen clips it to a monitor");
        expect(
                !GuardWindowsKeys.capturedCursor(false, 0, true),
                "failed cursor queries cannot authorize input");
        focus[0] = true;
        int sampled = reads[0];
        window[0] = 12;
        expect(
                reader.read("main-identity") == null && reads[0] == sampled,
                "a second Minecraft window cannot supply the controller's input");
        window[0] = 11;
        process[0] = 22;
        expect(
                reader.read("main-identity") == null && reads[0] == sampled,
                "a different process cannot reuse a bound window handle");
        process[0] = 21;
        expect(
                reader.read("other-identity") == null && reads[0] == sampled,
                "window binding does not transfer to another guarded account");
        expect(
                reader.read("main-identity") != null,
                "returning to the bound window resumes sampling");
        switchDuringSample[0] = true;
        expect(
                reader.read("main-identity") == null,
                "a focus switch during the key snapshot discards the whole sample");
        switchDuringSample[0] = false;
        window[0] = 11;
        reader.automatic();
        window[0] = 12;
        expect(
                reader.read("main-identity") != null,
                "automatic mode clears an optional window binding");
        window[0] = 11;
        GuardInputPrediction prediction = new GuardInputPrediction();
        List<Vec3Point> actual = new ArrayList<>();
        actual.add(new Vec3Point(0, 63, 0));
        Vec3Point motion = new Vec3Point(0, 0, 0);
        double maximumError = 0;
        for (int tick = 0; tick < 160; tick++) {
            NearbySteering.Input held =
                    tick < 45
                            ? NearbySteering.Input.FORWARD
                            : tick < 75
                                    ? NearbySteering.Input.NONE
                                    : tick < 120
                                            ? NearbySteering.Input.BACK
                                            : NearbySteering.Input.NONE;
            Vec3Point observed =
                    actual.get(Math.max(0, tick - 5)); // 250 ms at the normal client tick rate.
            Vec3Point estimate = prediction.update(observed, held, 0, false, false, tick, 5);
            maximumError =
                    Math.max(
                            maximumError,
                            Math.sqrt(estimate.horizontalDistanceSq(actual.get(tick))));
            Vec3Point sameTick = prediction.update(observed, held, 0, false, false, tick, 5);
            expect(
                    sameTick.horizontalDistanceSq(estimate) < 1e-12,
                    "multiple control phases cannot integrate movement twice");
            Vec3Point delta =
                    GroundMotion.delta(motion.x, motion.z, 0, held.forward, held.side, .1, 1);
            Vec3Point current = actual.get(tick);
            actual.add(new Vec3Point(current.x + delta.x, 63, current.z + delta.z));
            motion = new Vec3Point(delta.x * .546, 0, delta.z * .546);
        }
        expect(
                maximumError < 1e-7,
                "250-ms server observations do not pull input prediction back to an old position");
        Vec3Point teleported =
                prediction.update(
                        new Vec3Point(15, 63, 0),
                        NearbySteering.Input.NONE,
                        0,
                        false,
                        false,
                        160,
                        5);
        expect(
                teleported.horizontalDistanceSq(new Vec3Point(15, 63, 0)) < 1e-9,
                "large observed relocation resets prediction");
        prediction.reset();
        for (int tick = 0; tick < 80; tick++) {
            Vec3Point estimate =
                    prediction.update(
                            new Vec3Point(0, 63, 0),
                            NearbySteering.Input.FORWARD,
                            0,
                            false,
                            false,
                            tick,
                            5);
            if (tick > 12)
                expect(
                        estimate.horizontalDistanceSq(new Vec3Point(0, 63, 0)) < 1e-9
                                && prediction.stalled(),
                        "held keys against a stationary observed player cannot drive a predicted ghost away");
        }
        expect(
                prediction
                                .nextMotion(NearbySteering.Input.FORWARD, 0, false, false)
                                .horizontalDistanceSq(new Vec3Point(0, 0, 0))
                        < 1e-9,
                "blocked observation cancels predictive forward feed");
        Vec3Point resumed =
                prediction.update(
                        new Vec3Point(0, 63, 0), NearbySteering.Input.BACK, 0, false, false, 80, 5);
        expect(
                resumed.horizontalDistanceSq(new Vec3Point(0, 63, 0)) < 1e-9
                        && prediction.nextMotion(NearbySteering.Input.BACK, 0, false, false).z
                                < -.09,
                "reversing out of a blocked step cannot integrate the old forward key or retain ghost momentum");
        prediction.reset();
        actual.clear();
        actual.add(new Vec3Point(0, 63, 0));
        motion = new Vec3Point(0, 0, 0);
        for (int tick = 0; tick < 75; tick++) {
            int source = Math.max(0, tick - 5);
            float actualYaw = tick < 30 ? 0 : 90, observedYaw = source < 30 ? 0 : 90;
            Vec3Point estimate =
                    prediction.update(
                            actual.get(source),
                            NearbySteering.Input.FORWARD,
                            observedYaw,
                            false,
                            false,
                            tick,
                            5);
            if (tick >= 35)
                expect(
                        estimate.horizontalDistanceSq(actual.get(tick)) < .02 * .02,
                        "newly observable turn replays the delayed input path without inventing an instantaneous momentum rotation");
            Vec3Point delta = GroundMotion.delta(motion.x, motion.z, actualYaw, 1, 0, .1, 1),
                    at = actual.get(tick);
            actual.add(new Vec3Point(at.x + delta.x, 63, at.z + delta.z));
            motion = new Vec3Point(delta.x * .546, 0, delta.z * .546);
        }
        expect(
                Math.abs(GuardFollow.OFFSET - .44) < 1e-9,
                "guard keeps the requested slightly wider rear spacing");
        // Resolve the native API on Windows without reading any live key state.
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            java.lang.reflect.Constructor<?> constructor =
                    Class.forName("dev.optimus.client.guard.WindowsGuardDevice")
                            .getDeclaredConstructor();
            constructor.setAccessible(true);
            GuardWindowsKeys.Device nativeDevice =
                    (GuardWindowsKeys.Device) constructor.newInstance();
            nativeDevice.gameplayFocused();
            expect(
                    nativeDevice.reason() != null,
                    "native foreground/cursor APIs execute without reading real keys");
            nativeCursorChecks();
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " Windows gameplay-key, same-tick input and 250-ms prediction checks");
    }
}