package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.util.Collections;

/** Native-width contact remains separate from the support required at planned grid centres. */
public final class GroundContactSelfTest {
    private static int checks;

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static CollisionGeometry floor(double height) {
        return new CollisionGeometry(
                (x, y, z) -> {
                    if (x < 0 || z < 0 || y < 0 || y >= height) return CollisionGeometry.Cell.AIR;
                    return new CollisionGeometry.Cell(
                            true,
                            false,
                            Collections.singletonList(
                                    new CollisionGeometry.Box(
                                            x, y, z, x + 1, Math.min(y + 1, height), z + 1)));
                });
    }

    public static void main(String[] args) {
        PitRegion area = new PitRegion();
        area.setCornerA(new Vec3Point(-5, -1, -5));
        area.setCornerB(new Vec3Point(5, 10, 5));
        CollisionGeometry ground = floor(1);
        expect(ground.touchingGround(area, new Vec3Point(.5, 1, .5)), "flat floor contact");
        expect(
                ground.touchingGround(area, new Vec3Point(-.2, 1, .5)),
                "outer foot edge has support");
        expect(
                ground.touchingGround(area, new Vec3Point(-.2, 1, -.2)),
                "outer foot corner has support");
        expect(
                !ground.standable(area, new Vec3Point(-.2, 1, -.2)),
                "partial contact does not authorize a grid waypoint");
        expect(!ground.touchingGround(area, new Vec3Point(-.31, 1, .5)), "unsupported foot box");
        expect(
                !ground.touchingGround(area, new Vec3Point(.5, 1.03, .5)),
                "falling above a floor is not contact");
        expect(
                !ground.touchingGround(area, new Vec3Point(.5, .98, .5)),
                "body intersecting the floor");
        expect(!ground.touchingGround(area, new Vec3Point(.5, 2.2, .5)), "jump apex");
        expect(!ground.touchingGround(area, new Vec3Point(4.9, 1, .5)), "body outside bounds");
        expect(
                ground.touchingGround(area, new Vec3Point(.5, 1.000000001, .5)),
                "native floating-point tolerance");
        expect(
                !ground.touchingGround(area, new Vec3Point(.5, 1.0001, .5)),
                "a real gap remains airborne");
        for (double height : new double[] {.5, 1.5, 1.0625, 1.375})
            expect(
                    floor(height).touchingGround(area, new Vec3Point(.5, height, .5)),
                    "slab, stair, carpet and snow tops");
        CollisionGeometry unloaded =
                new CollisionGeometry((x, y, z) -> CollisionGeometry.Cell.UNLOADED);
        expect(!unloaded.touchingGround(area, new Vec3Point(.5, 1, .5)), "unloaded support");
        CollisionGeometry hazardous =
                new CollisionGeometry(
                        (x, y, z) ->
                                y == 0
                                        ? new CollisionGeometry.Cell(
                                                true,
                                                true,
                                                Collections.singletonList(
                                                        new CollisionGeometry.Box(
                                                                x, y, z, x + 1, y + 1, z + 1)))
                                        : CollisionGeometry.Cell.AIR);
        expect(!hazardous.touchingGround(area, new Vec3Point(.5, 1, .5)), "hazardous support");
        System.out.println(
                "PASS: " + checks + " full-foot contact, height and unsupported-terrain checks");
    }
}