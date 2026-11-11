package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;

import java.util.*;
import dev.optimus.core.navigation.NearbySteering.Input;

/** Immediate local decisions and safe wall/corner escape with no path search. */
public final class NearbySteeringSelfTest {
    private static int checks,cells;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static PitRegion area() { PitRegion p=new PitRegion();p.setCornerA(new Vec3Point(-5,0,-5));p.setCornerB(new Vec3Point(12,8,12));return p; }
    private static CollisionGeometry map(int mode) {
        return new CollisionGeometry((x,y,z)-> {
            cells++;
            if(mode==3 && x>=1) return CollisionGeometry.Cell.UNLOADED;
            if(mode==4 && x>=1 && y==1) return new CollisionGeometry.Cell(true,true,Collections.emptyList());
            List<CollisionGeometry.Box> boxes=new ArrayList<>();
            if(y==0 && !(mode==5 && x>=1)) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
            if(y>=1 && y<=3 && ((mode==1 && x==2 && z==0) || (mode==2 && ((x==1 && z>=0) || (z==1 && x>=0)))))
                boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
            return new CollisionGeometry.Cell(true,false,boxes);
        });
    }
    private static float yaw(Vec3Point from,Vec3Point to) { return (float)Math.toDegrees(Math.atan2(from.x-to.x,to.z-from.z)); }
    public static void main(String[] args) throws Exception {
        PitRegion pit=area();Vec3Point self=new Vec3Point(.5,1,.5),goal=new Vec3Point(4.5,1,.5);
        NearbySteering steering=new NearbySteering();CollisionGeometry clear=map(0);
        cells=0;Input forward=steering.choose(clear.cached(),pit,self,goal,-90,0);
        expect(forward==Input.FORWARD,"near target receives forward input immediately");
        expect(cells<160,"straight close chase samples only a small local footprint");
        Vec3Point nearWall=new Vec3Point(1.69,1,.5);CollisionGeometry wall=map(1);
        expect(!wall.pursuitClear(nearWall,goal,pit),"whole target corridor is obstructed in this regression case");
        Input detour=steering.choose(wall.cached(),pit,nearWall,goal,-90,1);
        expect(detour!=Input.NONE && detour!=Input.FORWARD,"blocked straight chase immediately selects a safe side input");
        expect(wall.walkEnd(nearWall,detour.point(nearWall,-90,.48),pit)!=null,"selected side input has independently checked body/floor clearance");
        for(int tick=2;tick<6;tick++) expect(steering.choose(wall.cached(),pit,nearWall,goal,-90,tick)==detour,
            "same blocked corner does not flip side every frame");
        Vec3Point moving=nearWall;steering.reset();double initial=moving.horizontalDistanceSq(goal);
        for(int tick=0;tick<50 && moving.horizontalDistanceSq(goal)>1.7*1.7;tick++) {
            float angle=yaw(moving,goal);Input input=steering.choose(wall.cached(),pit,moving,goal,angle,tick);
            expect(input!=Input.NONE,"checked local control continues around a full-height wall");
            Vec3Point next=wall.walkEnd(moving,input.point(moving,angle,.22),pit);
            expect(next!=null && pit.containsBody(next,.3,1.8),"every simulated local key step remains on clear supported pit terrain: tick="+tick+" input="+input+" position="+moving.serialize());
            moving=next;
        }
        expect(moving.horizontalDistanceSq(goal)<initial-.5,"local chase makes progress around the wall without A*");
        CollisionGeometry corner=map(2);Vec3Point trapped=new Vec3Point(.65,1,.65),beyond=new Vec3Point(3.5,1,3.5);
        steering.reset();steering.recover(10);
        for(int tick=10;tick<13;tick++) {
            Input input=steering.choose(corner.cached(),pit,trapped,beyond,-45,tick);
            expect(input.forward<0,"corner recovery backs out rather than using a nearby-target arrived result");
            expect(corner.walkEnd(trapped,input.point(trapped,-45,.22),pit)!=null,"back-out is a checked ordinary walking input");
            trapped=corner.walkEnd(trapped,input.point(trapped,-45,.22),pit);
        }
        Input side=steering.choose(corner.cached(),pit,trapped,beyond,-45,13);
        for(int tick=14;side.side==0 && tick<19;tick++) {
            expect(side.forward<0,"a tight corner can need a little more checked back-out before a side input fits");
            trapped=corner.walkEnd(trapped,side.point(trapped,-45,.22),pit);
            expect(trapped!=null,"extra back-out remains checked");
            side=steering.choose(corner.cached(),pit,trapped,beyond,-45,tick);
        }
        expect(side.side!=0,"back-out transitions to a side step before retrying forward");
        expect(corner.walkEnd(trapped,side.point(trapped,-45,.48),pit)!=null,"post-back-out side step is clear");
        steering.recover(30);Input alternate=steering.choose(clear.cached(),pit,self,goal,-90,33);
        expect(alternate.side!=side.side,"repeated recovery changes the escape side");
        steering.reset();expect(!steering.recovering(34),"stop/target reset clears local recovery");
        for(int mode:new int[]{3,4,5}) {
            steering.reset();CollisionGeometry unsafe=map(mode);
            Input input=steering.choose(unsafe.cached(),pit,self,goal,-90,0);
            expect(input!=Input.FORWARD && input!=Input.FORWARD_LEFT && input!=Input.FORWARD_RIGHT,
                "unloaded, hazardous or unsupported forward footprint never authorizes forward chase");
            if(input!=Input.NONE) expect(unsafe.walkEnd(self,input.point(self,-90,.48),pit)!=null,"fallback input also passes geometry checks");
        }
        expect(steering.choose(clear.cached(),pit,new Vec3Point(11.9,1,.5),goal,-90,1)==Input.NONE,
            "partly out-of-bounds current body is rejected");
        expect(steering.choose(clear.cached(),pit,self,new Vec3Point(12.1,1,.5),-90,1)==Input.NONE,
            "partly out-of-bounds target cannot authorize local input");
        expect(steering.choose(clear.cached(),pit,self,goal,Float.NaN,1)==Input.NONE,"nonfinite heading cannot authorize input");
        CollisionGeometry captured=CollisionReplaySelfTest.capture();PitRegion actual=new PitRegion();actual.setCornerA(new Vec3Point(-25,62.5,-127));actual.setCornerB(new Vec3Point(25,78,-77));
        Vec3Point observed=new Vec3Point(5.107,63,-99.315),target=new Vec3Point(6.5,63,-99.5);
        steering.reset();steering.recover(0);Input observedInput=steering.choose(captured.cached(),actual,observed,target,yaw(observed,target),1);
        expect(observedInput!=Input.NONE,"captured repeated-stall position has a usable local escape");
        expect(captured.walkEnd(observed,observedInput.point(observed,yaw(observed,target),.48),actual)!=null,
            "actual captured corner escape has clear loaded body space and supporting floor");
        System.out.println("PASS: "+checks+" bounded close pursuit, wall/corner escape and local safety checks");
    }
}