package dev.optimus.core;

import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NavigationView;

import java.util.*;

public final class NavigationViewSelfTest {
    public static void main(String[] args) {
        List<Vec3Point> route=new ArrayList<>(),arc=new ArrayList<>();
        for(int i=0;i<200;i++) { route.add(new Vec3Point(i,1,0));arc.add(new Vec3Point(i,2,1)); }
        NavigationView view=new NavigationView(route,arc,route.get(2),route.get(199));
        if(view.route.size()!=64 || view.trajectory.size()!=96) throw new AssertionError("Display work must stay bounded");
        route.clear();arc.clear();
        if(view.route.size()!=64 || view.trajectory.size()!=96 || view.current.x!=2 || view.goal.x!=199)
            throw new AssertionError("Snapshots must not alias a mutable route");
        try { view.route.clear();throw new AssertionError("Snapshot route must be immutable"); }
        catch(UnsupportedOperationException expected) { }
        NavigationView empty=new NavigationView(null,Arrays.asList((Vec3Point)null),null,null);
        if(!empty.route.isEmpty() || !empty.trajectory.isEmpty()) throw new AssertionError("Absent projections remain empty");
        System.out.println("PASS: 4 bounded, immutable navigation visualization snapshot checks");
    }
}