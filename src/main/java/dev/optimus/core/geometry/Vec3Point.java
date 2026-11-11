package dev.optimus.core.geometry;

import java.util.Locale;

/** Simple immutable route point; not tied to Minecraft classes. */
public final class Vec3Point {
    public final double x;
    public final double y;
    public final double z;

    public Vec3Point(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Non-finite coordinate");
        }
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double horizontalDistanceSq(Vec3Point other) {
        double dx = x - other.x, dz = z - other.z;
        return dx * dx + dz * dz;
    }

    public String serialize() {
        return String.format(Locale.ROOT, "%.3f:%.3f:%.3f", x, y, z);
    }

    public static Vec3Point parse(String raw) {
        String[] parts = raw.trim().split(":", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Point must be x:y:z");
        return new Vec3Point(
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]));
    }
}