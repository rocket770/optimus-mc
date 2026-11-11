package dev.optimus.client.guard;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Native foreground window, cursor and gameplay key queries. */
final class WindowsGuardDevice implements GuardWindowsKeys.Device {
    private interface User32 extends StdCallLibrary {
        Pointer GetForegroundWindow();

        int GetWindowThreadProcessId(Pointer window, IntByReference process);

        int GetWindowTextW(Pointer window, char[] title, int length);

        int GetCursorInfo(Pointer info);

        short GetAsyncKeyState(int key);
    }

    private interface Kernel32 extends StdCallLibrary {
        int GetCurrentProcessId();
    }

    final User32 api = (User32) Native.loadLibrary("user32", User32.class);
    final int ownProcess =
            ((Kernel32) Native.loadLibrary("kernel32", Kernel32.class)).GetCurrentProcessId();
    final Memory cursor = new Memory(16 + Native.POINTER_SIZE);
    private GuardCursorCapture cursorImages;
    private long cursorHandle = Long.MIN_VALUE, cursorCheckedAt;
    private boolean cursorBlank;
    private long windowId;
    private int processId;
    String reason = "waiting for main game";

    public long windowId() {
        return windowId;
    }

    public int processId() {
        return processId;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public boolean gameplayFocused() {
        Pointer window = api.GetForegroundWindow();
        if (window == null) {
            reason = "no foreground window";
            return false;
        }
        IntByReference process = new IntByReference();
        api.GetWindowThreadProcessId(window, process);
        if (process.getValue() == ownProcess) {
            reason = "bot window is focused";
            return false;
        }
        windowId = Pointer.nativeValue(window);
        processId = process.getValue();
        char[] text = new char[256];
        int length = api.GetWindowTextW(window, text, text.length);
        String title = new String(text, 0, Math.max(0, length));
        if (!GuardWindowsKeys.minecraftWindow(title)) {
            reason = "foreground window is not Minecraft";
            return false;
        }
        cursor.setInt(0, (int) cursor.size());
        boolean cursorKnown = api.GetCursorInfo(cursor) != 0;
        Pointer handle = cursorKnown ? cursor.getPointer(8) : null;
        boolean hidden = cursorKnown && (cursor.getInt(4) & 1) == 0;
        boolean blank = cursorKnown && (handle == null || Pointer.nativeValue(handle) == 0);
        if (!hidden && cursorKnown && !blank) {
            long value = Pointer.nativeValue(handle), now = System.nanoTime();
            if (value != cursorHandle || now - cursorCheckedAt > 1000000000L) {
                if (cursorImages == null) cursorImages = new GuardCursorCapture();
                cursorBlank = cursorImages.invisible(handle);
                cursorHandle = value;
                cursorCheckedAt = now;
            }
            blank = cursorBlank;
        }
        boolean captured =
                GuardWindowsKeys.capturedCursor(
                        cursorKnown, cursorKnown ? cursor.getInt(4) : 0, blank);
        reason = captured ? "ready" : "main game cursor is released";
        return captured;
    }

    @Override
    public boolean down(int key) {
        return (api.GetAsyncKeyState(key) & 0x8000) != 0;
    }
}