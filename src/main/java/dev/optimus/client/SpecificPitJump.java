package dev.optimus.client;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;
import java.util.*;
import net.minecraft.util.MathHelper;

/**
 * One native-checked wall lineup and two-hop corner crossing; ordinary jump limits stay unchanged.
 */
final class SpecificPitJump {
    static final int KIND = 6, LENGTH = 32;
    static final Vec3Point FROM = new Vec3Point(2.5, 68, -118.5),
            TO = new Vec3Point(-1.5, 68.5, -122.5);
    static final Vec3Point SETUP = new Vec3Point(5.48141870, 68, -118 - (double) (.6f / 2));

    static HintJump.Control control(int tick) {
        float yaw;
        NearbySteering.Input input = NearbySteering.Input.FORWARD;
        if (tick < 4) yaw = 90;
        else if (tick == 4) yaw = 98.286308f;
        else if (tick < 12) {
            yaw = 133.750031f - 45;
            input = NearbySteering.Input.FORWARD_RIGHT;
        } else if (tick < 16) {
            yaw = 133.750031f - 45 + 7.369339f;
            input = NearbySteering.Input.FORWARD_RIGHT;
        } else if (tick == 16) yaw = 141.931473f;
        else {
            yaw = 142.487839f - 45;
            input = NearbySteering.Input.FORWARD_RIGHT;
        }
        return new HintJump.Control(yaw, input, true, false, tick == 4 || tick == 16, true);
    }

    private static boolean expectedGround(int before) {
        return before <= 4 || before == 16 || before >= 26;
    }

    static List<HintJump.Frame> project(
            CollisionGeometry geometry,
            PitRegion area,
            Vec3Point start,
            double hx,
            double vy,
            double hz,
            boolean grounded,
            int offset) {
        if (offset < 0
                || offset >= LENGTH
                || !area.containsBody(start, .3, 1.8)
                || !geometry.free(CollisionGeometry.body(start))
                || grounded != expectedGround(offset)
                || !Double.isFinite(hx + vy + hz)
                || Math.hypot(hx, hz) > .75) return Collections.emptyList();
        if (offset == 0
                && (start.horizontalDistanceSq(SETUP) > .002 * .002
                        || Math.abs(start.y - 68) > .00001
                        || Math.hypot(hx, hz) >= .005
                        || Math.abs(vy) > .1
                        || !geometry.autoStepClear(FROM, start, area)))
            return Collections.emptyList();
        List<HintJump.Frame> result = new ArrayList<>();
        Vec3Point p = start;
        for (int tick = offset; tick < LENGTH; tick++) {
            HintJump.Control c = control(tick);
            if (Math.abs(hx) < .005) hx = 0;
            if (Math.abs(hz) < .005) hz = 0;
            if (Math.abs(vy) < .005) vy = 0;
            float angle = c.yaw * .017453292f,
                    sin = MathHelper.sin(angle),
                    cos = MathHelper.cos(angle);
            if (c.jump) {
                if (!grounded) return Collections.emptyList();
                vy = (double) .42f;
                hx -= (double) (sin * .2f);
                hz += (double) (cos * .2f);
            }
            float friction = grounded ? .6f * .91f : .91f;
            float acceleration =
                    grounded ? .13f * (.16277136f / (friction * friction * friction)) : .026f;
            float side = c.input.side * .98f, forward = c.input.forward * .98f;
            float length = MathHelper.sqrt_float(side * side + forward * forward);
            if (length < 1) length = 1;
            float scale = acceleration / length;
            side *= scale;
            forward *= scale;
            hx += (double) (side * cos - forward * sin);
            hz += (double) (forward * cos + side * sin);
            Vec3Point delta = new Vec3Point(hx, vy, hz), next = geometry.jumpMove(p, delta, area);
            if (next == null
                    || Math.abs(next.x - p.x - hx) > 1e-7
                    || Math.abs(next.z - p.z - hz) > 1e-7
                    || vy > 0 && next.y < p.y + vy - 1e-7) return Collections.emptyList();
            grounded = vy < 0 && next.y > p.y + vy + 1e-7;
            if (grounded != expectedGround(tick + 1)
                    || grounded && Math.abs(next.y - (tick < 16 ? 68 : 68.5)) > .00001)
                return Collections.emptyList();
            result.add(new HintJump.Frame(next, delta, grounded, c.jump, c));
            if (grounded) vy = 0;
            vy = (vy - .08) * (double) .98f;
            hx *= friction;
            hz *= friction;
            p = next;
        }
        return grounded && geometry.standable(area, p) && geometry.autoStepClear(p, TO, area)
                ? result
                : Collections.emptyList();
    }

    static boolean available(CollisionGeometry geometry, PitRegion area) {
        return geometry.standable(area, FROM)
                && geometry.standable(area, TO)
                && !project(geometry, area, SETUP, 0, -.0784, 0, true, 0).isEmpty();
    }
    /** Sneak into the wall while controlling the small X component; no position/velocity writes. */
    static HintJump.Control align(Vec3Point current, double velocityX) {
        float acceleration =
                .1f * (.16277136f / ((.6f * .91f) * (.6f * .91f) * (.6f * .91f))) * (.3f * .98f);
        double wanted = (SETUP.x - current.x) * (1 - (double) (.6f * .91f)) - velocityX;
        float yaw =
                (float)
                        Math.toDegrees(
                                Math.asin(-Math.max(-.8, Math.min(.8, wanted / acceleration))));
        return new HintJump.Control(yaw, NearbySteering.Input.FORWARD, false, true, false, true);
    }
}