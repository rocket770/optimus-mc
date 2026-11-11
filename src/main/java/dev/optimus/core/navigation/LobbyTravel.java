package dev.optimus.core.navigation;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;

/**
 * World-space envelope only: actual travel still requires verified Duels-lobby evidence and loaded
 * safe terrain.
 */
public final class LobbyTravel {
    private LobbyTravel() {}

    public static PitRegion area() {
        PitRegion area = new PitRegion();
        area.setCornerA(new Vec3Point(-30000000, 0, -30000000));
        area.setCornerB(new Vec3Point(30000000, 256, 30000000));
        return area;
    }
}