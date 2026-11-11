package dev.optimus.core.geometry;

/** Axis-aligned, inclusive 3D fighting pit region. Both corners must be configured. */
public final class PitRegion {
    private Vec3Point a;
    private Vec3Point b;

    public void setCornerA(Vec3Point point) {
        a = point;
    }

    public void setCornerB(Vec3Point point) {
        b = point;
    }

    public Vec3Point cornerA() {
        return a;
    }

    public Vec3Point cornerB() {
        return b;
    }

    public boolean isDefined() {
        return a != null && b != null && a.x != b.x && a.y != b.y && a.z != b.z;
    }

    public void clear() {
        a = b = null;
    }

    public boolean contains(Vec3Point point) {
        if (!isDefined() || point == null) return false;
        return between(point.x, a.x, b.x)
                && between(point.y, a.y, b.y)
                && between(point.z, a.z, b.z);
    }

    public boolean containsBody(Vec3Point feet, double halfWidth, double height) {
        return feet != null
                && contains(new Vec3Point(feet.x - halfWidth, feet.y, feet.z - halfWidth))
                && contains(new Vec3Point(feet.x + halfWidth, feet.y + height, feet.z + halfWidth));
    }

    private static boolean between(double value, double aa, double bb) {
        return value >= Math.min(aa, bb) && value <= Math.max(aa, bb);
    }

    public Vec3Point center() {
        if (!isDefined()) throw new IllegalStateException("Pit is not defined");
        return new Vec3Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0, (a.z + b.z) / 2.0);
    }
}