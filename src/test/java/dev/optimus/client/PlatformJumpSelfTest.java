package dev.optimus.client;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.LocalApproach;
import dev.optimus.core.navigation.PlatformJump;
import dev.optimus.core.navigation.SprintGap;

import java.util.*;
import net.minecraft.util.AxisAlignedBB;

/** Native clipping cross-checks, actual diagonal platform arcs and slab-aware complete routes. */
public final class PlatformJumpSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static PitRegion area() { PitRegion p=new PitRegion();p.setCornerA(new Vec3Point(-9,0,-9));p.setCornerB(new Vec3Point(12,10,12));return p; }
    private static CollisionGeometry platforms(int ox,int oz,double startY,double landingY,int problem) {
        return new CollisionGeometry((x,y,z)-> {
            if(problem==1 && x==1 && z==1) return CollisionGeometry.Cell.UNLOADED;
            List<CollisionGeometry.Box> boxes=new ArrayList<>();
            if(x*Math.signum(ox)<=0 && z*Math.signum(oz)<=0 && y==0) boxes.add(new CollisionGeometry.Box(x,0,z,x+1,startY,z+1));
            int floor=(int)Math.floor(landingY-.01);
            if(x==ox && z==oz && y==floor) boxes.add(new CollisionGeometry.Box(x,Math.max(floor,landingY-.5),z,x+1,landingY,z+1));
            if(problem==2 && y==2) boxes.add(new CollisionGeometry.Box(x,2,z,x+1,3,z+1));
            return new CollisionGeometry.Cell(true,problem==3 && x==ox && z==oz,boxes);
        }).cached();
    }
    private static List<GridPathfinder.Node> route(GroundRoute ground,Vec3Point from,Vec3Point to) {
        GridPathfinder.Node start=ground.start(from);
        return new GridPathfinder().find(start,ground,new GridPathfinder.Goal() {
            public boolean reached(GridPathfinder.Node n) { Vec3Point p=ground.point(n);return p.horizontalDistanceSq(to)<.01 && Math.abs(p.y-to.y)<.01; }
            public double estimate(GridPathfinder.Node n) { Vec3Point p=ground.point(n);return Math.sqrt(p.horizontalDistanceSq(to))+Math.abs(p.y-to.y)*.2; }
        },2000);
    }
    public static void main(String[] args) {
        PitRegion area=area();Vec3Point from=new Vec3Point(.5,1,.5);
        // Compare the predictor with Minecraft's exact Y/X/Z offset methods.
        List<CollisionGeometry.Box> obstacle=Collections.singletonList(new CollisionGeometry.Box(1,1,1,2,2,2));
        CollisionGeometry corner=new CollisionGeometry((x,y,z)->new CollisionGeometry.Cell(true,false,x==1&&y==1&&z==1?obstacle:Collections.emptyList()));
        for(double y:new double[]{1,1.42,1.75,2.01}) for(double x:new double[]{.5,.69,.8}) {
            Vec3Point p=new Vec3Point(x,y,x),delta=new Vec3Point(.24,.1,.24);
            AxisAlignedBB body=new AxisAlignedBB(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3),solid=new AxisAlignedBB(1,1,1,2,2,2);
            double dy=solid.calculateYOffset(body,delta.y);body=body.offset(0,dy,0);
            double dx=solid.calculateXOffset(body,delta.x);body=body.offset(dx,0,0);
            double dz=solid.calculateZOffset(body,delta.z);
            Vec3Point predicted=corner.jumpMove(p,delta,area);
            if(predicted!=null) expect(Math.abs(predicted.x-(p.x+dx))<1e-8 && Math.abs(predicted.y-(p.y+dy))<1e-8
                && Math.abs(predicted.z-(p.z+dz))<1e-8,"prediction matches native offset order at a diagonal block corner");
        }
        for(int[] offset:new int[][]{{1,1},{-1,1},{1,-1},{-1,-1},{2,1},{1,2},{2,2}}) for(double startY:new double[]{.5,1})
            for(double rise:new double[]{0,.5,1}) {
                int x=offset[0],z=offset[1];double top=startY+rise;
                CollisionGeometry geometry=platforms(x,z,startY,top,0);Vec3Point start=new Vec3Point(.5,startY,.5),landing=new Vec3Point(x+.5,top,z+.5);
                double length=Math.sqrt(start.horizontalDistanceSq(landing)),dx=x/length,dz=z/length;
                PlatformJump.Plan plan=PlatformJump.plan(geometry,area,start,landing,dx*.118,dz*.118,.098,.0196,.546);
                List<Vec3Point> arc;
                if(plan!=null) arc=PlatformJump.samples(geometry,area,plan,dx*.118,dz*.118,.098,.0196,.546,0);
                else {
                    SprintGap.Candidate sprint=SprintGap.diagonalLanding(geometry,area,start,landing);
                    expect(sprint!=null && SprintGap.plan(geometry,area,sprint,sprint.takeoff,.153,.1274,.026,.546)!=null,
                        "longer raised diagonal slab gap gets a checked sprint arc: "+x+":"+z+" start="+startY+" rise="+rise);
                    arc=SprintGap.samples(sprint.takeoff,landing.y,sprint.dx,sprint.dz,.153,.1274,.026,.546);
                }
                expect(!arc.isEmpty(),"checked diagonal platform/slab arc: "+x+":"+z+" start="+startY+" rise="+rise);
                for(Vec3Point p:arc) expect(area.containsBody(p,.3,1.8) && geometry.free(CollisionGeometry.body(p)),"native arc remains in loaded clear body space");
                Vec3Point end=arc.get(arc.size()-1);
                expect(Math.abs(end.y-top)<.01 && geometry.standable(area,end),"actual slab top is the supported landing height");
                GroundRoute ground=new GroundRoute(geometry,area,area,null,0,true);
                List<GridPathfinder.Node> path=route(ground,start,landing);
                expect(path.size()==2,"full planner exposes a complete diagonal parkour edge");
                List<GridPathfinder.Node> local=LocalApproach.route(ground,start,landing);
                expect(local.size()>=2,"bounded local fallback also exposes parkour instead of only walking");
            }
        for(int issue=1;issue<=3;issue++) {
            CollisionGeometry blocked=platforms(1,1,1,2,issue);
            expect(PlatformJump.plan(blocked,area,from,new Vec3Point(1.5,2,1.5),.083,.083,.098,.0196,.546)==null,
                "unloaded space, ceiling or hazardous landing rejects a diagonal jump");
        }
        CollisionGeometry gap=platforms(2,1,1,1,0);
        expect(route(new GroundRoute(gap,area,area,null,1,false),from,new Vec3Point(2.5,1,1.5)).isEmpty(),"unsupported jump physics cannot authorize a disconnected diagonal gap");
        // Native 1.8.9 reaches just under 1.25 blocks. A rounded 1.25
        // ceiling would admit a quarter-block ledge that the player cannot top.
        for(double rise:new double[]{1.25,1.5,2}) {
            CollisionGeometry tooHigh=platforms(1,0,1,1+rise,0);
            Vec3Point highLanding=new Vec3Point(1.5,1+rise,.5);
            expect(!new GroundRoute(tooHigh,area,area,null,1,true).edgeClear(from,highLanding),
                "adjacent climb above native jump height cannot use the simple-hop fallback: rise="+rise);
        }
        CollisionGeometry above=new CollisionGeometry((x,y,z)-> {
            List<CollisionGeometry.Box> boxes=new ArrayList<>();
            if(y==0 && x>=0 && x<=4 && z>=0 && z<=2) boxes.add(new CollisionGeometry.Box(x,0,z,x+1,1,z+1));
            if(y==3 && z==0 && x>=0 && x<=2) boxes.add(new CollisionGeometry.Box(x,3,z,x+1,4,z+1));
            if(z==1 && ((x==2 && y==1) || (x==3 && y==2))) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
            return new CollisionGeometry.Cell(true,false,boxes);
        }).cached();
        GroundRoute upward=new GroundRoute(above,area,area,null,1,true);
        List<GridPathfinder.Node> climb=route(upward,from,new Vec3Point(.5,4,.5));
        expect(climb.size()>=4,"player directly above can be reached by detouring out from under the roof and chaining climbs");
        boolean diagonalRise=false;
        for(int i=1;i<climb.size();i++) {
            Vec3Point a=upward.point(climb.get(i-1)),b=upward.point(climb.get(i));
            expect(upward.edgeClear(a,b),"each above-player detour/climb edge remains validated");
            diagonalRise|=b.y>a.y+.6 && Math.abs(b.x-a.x)>.1 && Math.abs(b.z-a.z)>.1;
        }
        expect(diagonalRise,"above-player route exposes a diagonal climb instead of walking into the wall below the goal");
        System.out.println("PASS: "+checks+" native collision clipping, diagonal platform/slab arcs and complete parkour route checks");
    }
}