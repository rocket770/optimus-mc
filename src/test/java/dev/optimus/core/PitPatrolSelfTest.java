package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.PitPatrol;

import java.util.*;
import dev.optimus.core.navigation.GridPathfinder.Node;

/** Patrol routes use the actual captured pit and remain safe when there is nowhere to roam. */
public final class PitPatrolSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static GroundRoute terrain(CollisionGeometry geometry,PitRegion pit) {
        return new GroundRoute(geometry.cached(),pit,pit,new Vec3Point(-2,78,-78),63,true,true);
    }
    private static List<Node> validate(CollisionGeometry geometry,PitRegion pit,Vec3Point current,long seed) {
        GroundRoute route=terrain(geometry,pit);
        List<Node> path=PitPatrol.route(route,current,new Random(seed));
        expect(path.size()>1 && path.size()<=9,"actual pit has a short supported roaming route");
        expect(current.horizontalDistanceSq(route.point(path.get(path.size()-1)))>.81,"roaming destination makes progress away from current position");
        GroundRoute independent=terrain(geometry,pit);
        Set<Node> visited=new HashSet<>();
        for(int i=0;i<path.size();i++) {
            Node n=path.get(i);Vec3Point p=route.point(n);
            expect(visited.add(n),"patrol does not repeat a grid node");
            expect(pit.containsBody(p,.3,1.8) && geometry.standable(pit,p),"each patrol point has loaded support/clear body space wholly inside pit");
            if(i>0) {
                Node previous=path.get(i-1);
                expect(Math.abs(previous.x-n.x)+Math.abs(previous.z-n.z)==1,"selected roam route uses adjacent terrain rather than blind gap jumps");
                expect(independent.edgeClear(route.point(previous),p),"each patrol edge independently passes the live movement rules");
            }
        }
        return path;
    }
    public static void main(String[] args) throws Exception {
        CollisionGeometry geometry=CollisionReplaySelfTest.capture();PitRegion pit=new PitRegion();
        pit.setCornerA(new Vec3Point(-25,62.5,-127));pit.setCornerB(new Vec3Point(25,78,-77));
        Vec3Point center=new Vec3Point(.5,63,-100.5);
        List<Node> a=validate(geometry,pit,center,42);
        List<Node> b=validate(geometry,pit,center,43);
        expect(!a.equals(b),"successive seeded patrol choices vary rather than following a fixed circle");
        validate(geometry,pit,new Vec3Point(-.293,69,-78.888),44);
        expect(PitPatrol.route(terrain(geometry,pit),new Vec3Point(.5,70,28.5),new Random(42)).isEmpty(),"lobby spawn outside pit cannot authorize patrol");
        CollisionGeometry unloaded=new CollisionGeometry((x,y,z)->CollisionGeometry.Cell.UNLOADED);
        expect(PitPatrol.route(terrain(unloaded,pit),center,new Random(42)).isEmpty(),"unloaded terrain never produces patrol movement");
        CollisionGeometry isolated=new CollisionGeometry((x,y,z)->x==0 && y==62 && z==-101
            ?new CollisionGeometry.Cell(true,false,Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1)))
            :CollisionGeometry.Cell.AIR);
        expect(isolated.standable(pit,center),"isolated test floor supports current player");
        expect(PitPatrol.route(terrain(isolated,pit),center,new Random(42)).isEmpty(),"isolated safe floor waits instead of moving into void");
        System.out.println("PASS: "+checks+" captured-pit patrol and unreachable-terrain checks");
    }
}