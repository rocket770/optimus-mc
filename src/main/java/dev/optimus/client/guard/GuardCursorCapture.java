package dev.optimus.client.guard;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Arrays;

/** Detect LWJGL's transparent grabbed cursor without reading the screen. */
final class GuardCursorCapture {
    private interface User32 extends StdCallLibrary {
        int DrawIconEx(
                Pointer dc,
                int x,
                int y,
                Pointer cursor,
                int width,
                int height,
                int step,
                Pointer brush,
                int flags);
    }

    private interface Gdi32 extends StdCallLibrary {
        Pointer CreateCompatibleDC(Pointer dc);

        Pointer CreateDIBSection(
                Pointer dc,
                Pointer info,
                int usage,
                PointerByReference bits,
                Pointer section,
                int offset);

        Pointer SelectObject(Pointer dc, Pointer object);

        int DeleteObject(Pointer object);

        int DeleteDC(Pointer dc);

        int GdiFlush();
    }

    private final User32 user = (User32) Native.loadLibrary("user32", User32.class);
    private final Gdi32 gdi = (Gdi32) Native.loadLibrary("gdi32", Gdi32.class);

    boolean invisible(Pointer cursor) {
        if (cursor == null || Pointer.nativeValue(cursor) == 0) return false;
        final int size = 32;
        Pointer dc = gdi.CreateCompatibleDC(null), bitmap = null, previous = null;
        if (dc == null) return false;
        try {
            Memory info = new Memory(40);
            info.clear();
            info.setInt(0, 40);
            info.setInt(4, size);
            info.setInt(8, -size);
            info.setShort(12, (short) 1);
            info.setShort(14, (short) 32);
            PointerByReference pixels = new PointerByReference();
            bitmap = gdi.CreateDIBSection(dc, info, 0, pixels, null, 0);
            if (bitmap == null || pixels.getValue() == null) return false;
            previous = gdi.SelectObject(dc, bitmap);
            if (previous == null || Pointer.nativeValue(previous) == -1L) {
                previous = null;
                return false;
            }
            int[] background = new int[size * size];
            for (int color : new int[] {0x00315a87, 0x00cea578}) {
                Arrays.fill(background, color);
                pixels.getValue().write(0, background, 0, background.length);
                if (user.DrawIconEx(dc, 0, 0, cursor, size, size, 0, null, 3) == 0
                        || gdi.GdiFlush() == 0) return false;
                for (int pixel : pixels.getValue().getIntArray(0, background.length))
                    if ((pixel & 0x00ffffff) != color) return false;
            }
            return true;
        } finally {
            if (previous != null) gdi.SelectObject(dc, previous);
            if (bitmap != null) gdi.DeleteObject(bitmap);
            gdi.DeleteDC(dc);
        }
    }
}