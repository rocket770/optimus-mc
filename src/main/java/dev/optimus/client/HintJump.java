package dev.optimus.client;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.SprintGap;
import java.util.*;
import net.minecraft.util.MathHelper;

/**
 * Native-input projection for an explicitly supplied advanced corridor, from actual live velocity.
 */
final class HintJump {
    static final class Frame {
        final Vec3Point point, delta;
        final boolean grounded, jump;
        final Control control;

        Frame(Vec3Point point, Vec3Point delta, boolean grounded, boolean jump) {
            this(point, delta, grounded, jump, null);
        }

        Frame(Vec3Point point, Vec3Point delta, boolean grounded, boolean jump, Control control) {
            this.point = point;
            this.delta = delta;
            this.grounded = grounded;
            this.jump = jump;
            this.control = control;
        }
    }

    static final class Control {
        final float yaw;
        final boolean forward, jump, sprint, sneak, exactYaw;
        final NearbySteering.Input input;

        Control(float yaw, boolean forward, boolean jump) {
            this(
                    yaw,
                    forward ? NearbySteering.Input.FORWARD : NearbySteering.Input.NONE,
                    forward,
                    false,
                    jump);
        }

        Control(
                float yaw,
                NearbySteering.Input input,
                boolean sprint,
                boolean sneak,
                boolean jump) {
            this(yaw, input, sprint, sneak, jump, false);
        }

        Control(
                float yaw,
                NearbySteering.Input input,
                boolean sprint,
                boolean sneak,
                boolean jump,
                boolean exactYaw) {
            this.exactYaw = exactYaw;
            this.yaw = yaw;
            this.forward = input.forward > 0;
            this.input = input;
            this.sprint = sprint;
            this.sneak = sneak;
            this.jump = jump;
        }
    }

    static Vec3Point restSetup(RouteHints.Jump edge) {
        if (edge.kind == 4) return edge.start;
        float angle = edge.yaw * .017453292f,
                sin = MathHelper.sin(angle),
                cos = MathHelper.cos(angle),
                friction = .6f * .91f;
        float acceleration = .13f * (.16277136f / (friction * friction * friction)) * .98f;
        double x = 0, z = 0, hx = 0, hz = 0;
        for (int i = 0; i < 6; i++) {
            hx -= (double) (sin * acceleration);
            hz += (double) (cos * acceleration);
            x += hx;
            z += hz;
            hx *= friction;
            hz *= friction;
        }
        return new Vec3Point(edge.start.x - x, edge.start.y, edge.start.z - z);
    }

    static List<Frame> project(
            CollisionGeometry geometry,
            PitRegion area,
            RouteHints.Jump edge,
            Vec3Point start,
            float yaw,
            double hx,
            double vy,
            double hz) {
        if (!geometry.standable(area, start)
                || !SprintGap.launchSupported(geometry, area, start)
                || Math.hypot(hx, hz) > .4
                || Math.abs(vy) > .1) return Collections.emptyList();
        if (edge.kind != 4 && edge.kind != 5) return Collections.emptyList();
        if (edge.kind == 4
                && (Math.abs(edge.from.y - edge.to.y - 1) > .01 || Math.hypot(hx, hz) > .01))
            return Collections.emptyList();
        List<Frame> frames = new ArrayList<>();
        Vec3Point p = start;
        boolean grounded = true;
        int cooldown = 0, jumps = 0;
        float angle = yaw * .017453292f, sin = MathHelper.sin(angle), cos = MathHelper.cos(angle);
        for (int tick = 0; tick < 40; tick++) {
            if (cooldown > 0) cooldown--;
            if (Math.abs(hx) < .005) hx = 0;
            if (Math.abs(hz) < .005) hz = 0;
            if (Math.abs(vy) < .005) vy = 0;
            boolean jump = edge.kind == 4 || tick == 7;
            if (!jump) cooldown = 0;
            if (jump && grounded && cooldown == 0) {
                vy = (double) .42f;
                hx -= (double) (sin * .2f);
                hz += (double) (cos * .2f);
                cooldown = 10;
                jumps++;
            }
            float friction = grounded ? .6f * .91f : .91f;
            float acceleration =
                    (grounded ? .13f * (.16277136f / (friction * friction * friction)) : .026f)
                            * .98f;
            hx -= (double) (sin * acceleration);
            hz += (double) (cos * acceleration);
            Vec3Point next = geometry.jumpMove(p, new Vec3Point(hx, vy, hz), area);
            if (next == null
                    || Math.abs(next.x - p.x - hx) > 1e-6
                    || Math.abs(next.z - p.z - hz) > 1e-6
                    || vy > 0 && next.y < p.y + vy - 1e-6) return Collections.emptyList();
            grounded = vy < 0 && next.y > p.y + vy + 1e-6;
            frames.add(new Frame(next, new Vec3Point(hx, vy, hz), grounded, jump));
            if (edge.kind == 5 && tick < 7 && (!grounded || Math.abs(next.y - start.y) > .01))
                return Collections.emptyList();
            if (grounded && jumps == (edge.kind == 4 ? 2 : 1))
                return Math.abs(next.y - edge.to.y) < .01
                                && geometry.standable(area, next)
                                && geometry.autoStepClear(next, edge.to, area)
                        ? frames
                        : Collections.emptyList();
            if (edge.kind == 4 && grounded && jumps == 1 && Math.abs(next.y - start.y) > .01)
                return Collections.emptyList();
            if (grounded) vy = 0;
            vy = (vy - .08) * (double) .98f;
            hx *= friction;
            hz *= friction;
            p = next;
        }
        return Collections.emptyList();
    }
}