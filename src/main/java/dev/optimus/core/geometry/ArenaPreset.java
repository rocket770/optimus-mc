package dev.optimus.core.geometry;

/** Coordinates supplied by the user; installing geometry never grants lobby verification. */
public final class ArenaPreset {
    private ArenaPreset() {}

    public static final Vec3Point SPAWN = new Vec3Point(0.5, 69.5, -50.5);
    public static final Vec3Point PIT_A = new Vec3Point(-25, 62.5, -127);
    public static final Vec3Point PIT_B = new Vec3Point(25, 78, -77);
    public static final Vec3Point DROP_RIM = new Vec3Point(-2, 78, -78);
    public static final double FLOOR = 63;
    // Existing inferred envelope still contains the expanded pit and spawn.
    public static final Vec3Point NAV_A = new Vec3Point(-28, 62, -130);
    public static final Vec3Point NAV_B = new Vec3Point(28, 82, -46);
    public static final Vec3Point LANDING_HINT = new Vec3Point(-1.5, 63, -79.5);

    public static boolean same(Vec3Point a, Vec3Point b) {
        return a != null && b != null && a.x == b.x && a.y == b.y && a.z == b.z;
    }

    public static boolean upgradeLegacyPit(PitRegion pit) {
        if (pit == null
                || !same(pit.cornerA(), new Vec3Point(-24, 62.5, -126))
                || !same(pit.cornerB(), new Vec3Point(24, 78, -78))) return false;
        pit.setCornerA(PIT_A);
        pit.setCornerB(PIT_B);
        return true;
    }
}