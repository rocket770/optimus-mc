package dev.optimus.core.navigation;

import dev.optimus.core.geometry.Vec3Point;
import java.util.*;

/** Bounded immutable display data; never authorizes movement or changes a route. */
public final class NavigationView {
    public final List<Vec3Point> route, trajectory;
    public final Vec3Point current, goal;

    public NavigationView(
            List<Vec3Point> route, List<Vec3Point> trajectory, Vec3Point current, Vec3Point goal) {
        this.route = copy(route, 64);
        this.trajectory = copy(trajectory, 96);
        this.current = current;
        this.goal = goal;
    }

    private static List<Vec3Point> copy(List<Vec3Point> source, int limit) {
        List<Vec3Point> result = new ArrayList<>();
        if (source != null)
            for (Vec3Point p : source) {
                if (p != null) result.add(p);
                if (result.size() == limit) break;
            }
        return Collections.unmodifiableList(result);
    }
}