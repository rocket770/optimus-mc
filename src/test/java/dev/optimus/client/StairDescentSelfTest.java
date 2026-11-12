package dev.optimus.client;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.lang.reflect.Field;
import java.util.Collections;
import net.minecraft.client.Minecraft;
import sun.misc.Unsafe;

/** Checks stair continuation and invalidation against loaded collision shapes. */
public final class StairDescentSelfTest {
    private static int checks;

    private static void expect(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        Minecraft mc = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        NativeGuardFixture.Player player =
                (NativeGuardFixture.Player)
                        unsafe.allocateInstance(NativeGuardFixture.Player.class);
        mc.thePlayer = player;
        PitRegion area = new PitRegion();
        area.setCornerA(new Vec3Point(-2, 0, -2));
        area.setCornerB(new Vec3Point(12, 12, 2));
        int[] obstruction = {0};
        CollisionGeometry geometry =
                new CollisionGeometry(
                        (x, y, z) -> {
                            if (x == 4 && obstruction[0] == 2)
                                return CollisionGeometry.Cell.UNLOADED;
                            if (x == 4 && obstruction[0] == 1) return CollisionGeometry.Cell.AIR;
                            double top = 8 - Math.min(7, Math.max(0, x)) * .5;
                            if (obstruction[0] == 3 && x == 3) top += 3;
                            if (obstruction[0] == 4 && x >= 1) top = 4.5;
                            if (y < 0 || y >= top) return CollisionGeometry.Cell.AIR;
                            return new CollisionGeometry.Cell(
                                    true,
                                    false,
                                    Collections.singletonList(
                                            new CollisionGeometry.Box(
                                                    x, y, z, x + 1, Math.min(y + 1, top), z + 1)));
                        });
        StairDescent descent = new StairDescent(mc);
        Vec3Point start = new Vec3Point(.5, 8, .5), goal = new Vec3Point(7.5, 4.5, .5);
        for (int blocked : new int[] {0, 1, 2, 3, 4}) {
            obstruction[0] = 0;
            descent.reset();
            NativeGuardFixture.position(player, start, -90);
            LobbyNavigator.Step step = descent.step(geometry, area, goal, false);
            expect(
                    step != null && step.sprint && !step.jump,
                    "a loaded stair corridor starts without jumping");
            player.posX = 2.2;
            player.posY = 7.9;
            player.onGround = false;
            step = descent.step(geometry, area, goal, false);
            expect(
                    step != null && step.sprint && !step.jump,
                    "airborne descent keeps checked forward momentum");
            obstruction[0] = blocked;
            step = descent.step(geometry, area, goal, false);
            expect(
                    (step != null) == (blocked == 0),
                    "holes, unloaded cells, walls and unsupported cliffs invalidate descent");
        }
        obstruction[0] = 0;
        descent.reset();
        NativeGuardFixture.position(player, start, -90);
        descent.step(geometry, area, goal, false);
        player.posZ = 1.3;
        expect(
                descent.step(geometry, area, goal, false) == null,
                "leaving the checked corridor stops descent");
        descent.reset();
        NativeGuardFixture.position(player, start, -90);
        player.onGround = false;
        expect(
                descent.step(geometry, area, goal, false) == null,
                "an unrelated fall cannot start a stair shortcut");
        System.out.println("PASS: " + checks + " checked stair descent and invalidation checks");
    }
}