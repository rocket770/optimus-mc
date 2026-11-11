package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered waypoints, explicitly recorded by the user on the private server. */
public final class RouteFollower {
    private final List<Vec3Point> points = new ArrayList<Vec3Point>();
    private int index;

    public void add(Vec3Point point) {
        points.add(point);
    }

    public void clear() {
        points.clear();
        index = 0;
    }

    public void restart() {
        index = 0;
    }

    public int size() {
        return points.size();
    }

    public int index() {
        return index;
    }

    public List<Vec3Point> all() {
        return Collections.unmodifiableList(points);
    }

    public boolean complete() {
        return index >= points.size();
    }

    public Vec3Point current() {
        return complete() ? null : points.get(index);
    }

    public Vec3Point advanceIfReached(Vec3Point player, double closeRadius) {
        double r2 = closeRadius * closeRadius;
        while (!complete()
                && current().horizontalDistanceSq(player) <= r2
                && Math.abs(current().y - player.y) <= 2.0) {
            index++;
        }
        return current();
    }
}