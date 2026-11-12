package dev.optimus.core;

import dev.optimus.core.combat.CombatFocus;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.SprintGap;

import java.util.*;
import dev.optimus.core.navigation.GridPathfinder.Node;

/** Real empty-block gap widths, native arcs, landing run-out and defensive knockback continuity. */
public final class SprintGapSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static PitRegion area() { PitRegion p=new PitRegion();p.setCornerA(new Vec3Point(-8,0,-8));p.setCornerB(new Vec3Point(12,10,12));return p; }
    private static CollisionGeometry roof(int width,int dx,int dz,int problem) {
        return new CollisionGeometry((x,y,z)-> {
            int along=x*dx+z*dz,cross=dx!=0?z:x;
            if(problem==1 && along==2) return CollisionGeometry.Cell.UNLOADED;
            if(problem==2 && along==2 && y==2) return new CollisionGeometry.Cell(true,true,Collections.emptyList());
            List<CollisionGeometry.Box> boxes=new ArrayList<>();
            if(y==0 && cross==0 && (along<=0 || (problem!=3 && along==width+1))) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
            if(problem==4 && y==3) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
            return new CollisionGeometry.Cell(true,false,boxes);
        });
    }
    public static void main(String[] args) throws Exception {
        PitRegion pit=area();Vec3Point current=new Vec3Point(.5,1,.5);
        for(int width:new int[]{2,3}) for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            int dx=direction[0],dz=direction[1];CollisionGeometry geometry=roof(width,dx,dz,0).cached();
            Vec3Point target=new Vec3Point(current.x+dx*(width+1),1,current.z+dz*(width+1));
            SprintGap.Candidate candidate=SprintGap.find(geometry,pit,current,target);
            expect(candidate!=null && candidate.emptyBlocks==width,"two/three empty blocks produce a checked candidate: width="+width+" dx="+dx+" dz="+dz);
            expect(geometry.autoStepClear(current,candidate.runUp,pit),"run-up aligns through supported walking rather than a teleport");
            expect(geometry.autoStepClear(candidate.runUp,candidate.takeoff,pit),"run-up reaches its takeoff on real support");
            for(double speed:new double[]{.12,.153}) {
                SprintGap.Plan plan=SprintGap.plan(geometry,pit,candidate,candidate.takeoff,speed,.1274,.026,.546);
                expect(plan!=null,"ordinary stored sprint speeds pass checked gap envelopes: width="+width+" speed="+speed);
                List<Vec3Point> arc=SprintGap.samples(candidate.takeoff,1,dx,dz,speed,.1274,.026,.546);
                expect(arc.size()>8 && arc.size()<20,"flight uses bounded native gravity/drag ticks");
                for(int i=0;i<arc.size();i++) {
                    expect(pit.containsBody(arc.get(i),.3,1.8) && geometry.free(CollisionGeometry.body(arc.get(i))),"entire arc has loaded clear body space inside the pit");
                    if(i>0) expect(geometry.free(CollisionGeometry.body(arc.get(i-1)).union(CollisionGeometry.body(arc.get(i)))),"arc edges independently pass swept-body clearance");
                    if(i+1<arc.size()) expect(SprintGap.continuing(geometry,pit,plan,arc.get(i),arc.get(i+1)),"live continuation accepts the validated native arc");
                }
                Vec3Point end=arc.get(arc.size()-1);
                expect(geometry.standable(pit,end),"native arc lands on the actual narrow landing platform");
                expect(geometry.supported(new Vec3Point(end.x+dx*.15,end.y,end.z+dz*.15),.001),"landing has room for its ground brake");
                Vec3Point mid=arc.get(3);
                expect(!SprintGap.continuing(geometry,pit,plan,new Vec3Point(mid.x-dz,mid.y,mid.z+dx),arc.get(4)),"knockback outside the lateral envelope does not authorize continued jump input");
            }
            GroundRoute route=new GroundRoute(geometry,pit,pit,null,1,true);
            Node start=new Node(0,2,0),goal=new Node(dx*(width+1),2,dz*(width+1));
            expect(route.traversable(start,goal),"full planner can use a validated sprint-gap edge");
            List<Node> path=new GridPathfinder().find(start,route,new GridPathfinder.Goal() {
                public boolean reached(Node n) { return n.equals(goal); }
                public double estimate(Node n) { return Math.abs(n.x-goal.x)+Math.abs(n.z-goal.z); }
            },1000);
            expect(path.size()==2,"short full search finds a complete platform-to-platform gap route");
            expect(!new GroundRoute(geometry,pit,pit,null,1,false).traversable(start,goal),"unmodeled jump effects block the sprint-gap edge");
        }
        Vec3Point target=new Vec3Point(4.5,1,.5);
        for(int width:new int[]{1,2}) for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            int dx=direction[0],dz=direction[1];
            CollisionGeometry raised=new CollisionGeometry((x,y,z)-> {
                int along=x*dx+z*dz,cross=dx!=0?z:x;
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(cross==0 && ((y==0 && along<=0) || (y==1 && along==width+1)))
                    boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                return new CollisionGeometry.Cell(true,false,boxes);
            }).cached();
            Vec3Point destination=new Vec3Point(current.x+dx*(width+1),2,current.z+dz*(width+1));
            SprintGap.Candidate raisedCandidate=SprintGap.find(raised,pit,current,destination);
            expect(raisedCandidate!=null && raisedCandidate.landing.y==2,"raised narrow gap landing is found: width="+width+" direction="+dx+":"+dz);
            double speed=width==1?.128:.153;
            SprintGap.Plan raisedPlan=SprintGap.plan(raised,pit,raisedCandidate,raisedCandidate.takeoff,speed,.1274,.026,.546);
            expect(raisedPlan!=null,"raised gap validates native actual speed and heading envelope");
            List<Vec3Point> raisedArc=SprintGap.samples(raisedCandidate.takeoff,2,dx,dz,speed,.1274,.026,.546);
            for(int i=1;i<raisedArc.size();i++) expect(SprintGap.continuing(raised,pit,raisedPlan,raisedArc.get(i-1),raisedArc.get(i)),
                "raised gap continues through actual ascent/descent and landing");
            GroundRoute raisedRoute=new GroundRoute(raised,pit,pit,null,1,true);
            GridPathfinder.Node a=raisedRoute.start(current),b=new GridPathfinder.Node(a.x+dx*(width+1),4,a.z+dz*(width+1));
            expect(raisedRoute.traversable(a,b),"raised gap edge is exposed to the full planner");
            expect(!new GroundRoute(raised,pit,pit,null,1,false).traversable(a,b),"unsupported jump physics blocks a raised gap");
        }
        for(int problem=1;problem<=4;problem++) expect(SprintGap.find(roof(3,1,0,problem).cached(),pit,current,target)==null,
            "unloaded/hazard/missing-landing/low-ceiling terrain cannot produce a sprint gap");
        CollisionGeometry good=roof(3,1,0,0).cached();SprintGap.Candidate candidate=SprintGap.find(good,pit,current,target);
        expect(SprintGap.plan(good,pit,candidate,candidate.takeoff,.06,.1274,.026,.546)==null,"insufficient sprint momentum cannot force a gap launch");
        expect(SprintGap.plan(good,pit,candidate,candidate.takeoff,.4,.1274,.026,.546)==null,"excess speed that overshoots the platform cannot force a launch");
        PitRegion low=area();low.setCornerB(new Vec3Point(12,3,12));
        expect(SprintGap.find(good,low,current,target)==null,"jump arc cannot leave the whole-body pit ceiling");
        CombatFocus<Object> focus=new CombatFocus<>();focus.observeDamage(20,0,1);focus.observeDamage(19,10,2);
        for(int tick=12;tick<32;tick++) expect(!focus.reacting(tick) && focus.airborneResponse(tick,false),
            "ordinary knockback keeps defensive aim/attack mode after the former ten-tick reaction expiry");
        expect(!focus.airborneResponse(12,true),"landing ends the airborne-only extension immediately");
        expect(!focus.airborneResponse(32,false),"unobserved damage cannot sustain air defense indefinitely");
        focus.observeDamage(18,10,35);expect(focus.airborneResponse(60,false),"continued combo damage renews the defensive window");
        focus.reset();focus.attacked(70);expect(!focus.airborneResponse(71,false),"outgoing attacks cannot invent incoming knockback");
        focus.reset();expect(!focus.airborneResponse(71,false),"stop clears defensive recovery");
        CollisionGeometry captured=CollisionReplaySelfTest.capture("duels-pit-2026-10-15.tsv.gz");
        PitRegion actual=new PitRegion();actual.setCornerA(new Vec3Point(-25,62.5,-127));actual.setCornerB(new Vec3Point(25,78,-77));
        Vec3Point eastGoal=new Vec3Point(9.5,66,-80.5);
        for(Vec3Point start:Arrays.asList(new Vec3Point(16.5,64,-82),new Vec3Point(15,64,-82),new Vec3Point(17.5,64,-82.5))) {
            SprintGap.Candidate shortcut=SprintGap.find(captured.cached(),actual,start,eastGoal);
            expect(shortcut==null || shortcut.landing.y>=start.y-.26,
                "uphill pursuit must not select the long jump to the floor below the opponent: "+start.serialize());
            expect(!new GroundRoute(captured.cached(),actual,actual,null,63,true).edgeClear(start,eastGoal),
                "two-block rise across the reported long gap is not a jump edge");
        }
        for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) for(double rise:new double[]{-1,0,.5,1,1.5,2}) {
            int dx=direction[0],dz=direction[1];
            CollisionGeometry tooWide=new CollisionGeometry((x,y,z)-> {
                int along=x*dx+z*dz,cross=dx!=0?z:x;
                double top=along<=0?1:1+rise;
                if(cross==0 && (along<=0 || along>=6) && y==(int)Math.floor(top-.01))
                    return new CollisionGeometry.Cell(true,false,Collections.singletonList(new CollisionGeometry.Box(x,y,z,x+1,top,z+1)));
                return CollisionGeometry.Cell.AIR;
            }).cached();
            Vec3Point far=new Vec3Point(current.x+dx*6,1+rise,current.z+dz*6);
            expect(SprintGap.find(tooWide,pit,current,far)==null,"five empty blocks cannot produce a normal sprint-gap candidate");
            expect(!new GroundRoute(tooWide,pit,pit,null,1,true).edgeClear(current,far),
                "routing cannot authorize a five-empty-block gap at any tested landing height");
            if(rise>1) expect(SprintGap.samples(current,far.y,dx,dz,.153,.1274,.02548,.546).isEmpty(),
                "a raised landing above normal jump height has no vanilla sprint arc");
        }
        for(int sx:new int[]{-1,1}) for(int sz:new int[]{-1,1}) {
            CollisionGeometry diagonalTooFar=new CollisionGeometry((x,y,z)-> {
                if(y==0 && (x*sx<=0 && z*sz<=0 || x==4*sx && z==4*sz))
                    return new CollisionGeometry.Cell(true,false,Collections.singletonList(new CollisionGeometry.Box(x,0,z,x+1,1,z+1)));
                return CollisionGeometry.Cell.AIR;
            }).cached();
            Vec3Point far=new Vec3Point(.5+sx*4,1,.5+sz*4);
            expect(SprintGap.diagonalLanding(diagonalTooFar,pit,current,far)==null,
                "a diagonal with five-plus blocks of centre distance needs a feasible native arc and cannot bypass the range limit");
            expect(!new GroundRoute(diagonalTooFar,pit,pit,null,1,true).edgeClear(current,far),
                "an impossible diagonal cannot be exposed to the pathfinder");
        }
        SprintGap.Candidate late=new SprintGap.Candidate(new Vec3Point(-1.5,69,-83.15),new Vec3Point(-1.5,69,-81.25),
            new Vec3Point(-1.5,68.5,-87.5),4,0,-1);
        Vec3Point from=new Vec3Point(-1.53,69,-83.134),delta=new Vec3Point(0,-.0784,-.2785);
        Vec3Point edge=captured.jumpMove(from,delta,actual);
        expect(edge!=null && edge.y==from.y && !SprintGap.launchSupported(captured,actual,edge),"captured late edge retains native onGround after Y clips against the previous supported foot");
        expect(SprintGap.plan(captured,actual,late,edge,.2785*.546,.1274,.026,.546,true)==null,"old arbitrary takeoff-radius check reproduces a needless second run-up");
        expect(SprintGap.planAfterGroundedStep(captured,actual,late,from,edge,delta,.2785*.546,.1274,.026,.546)!=null,
            "actual clipped grounded step validates the full speed/heading jump envelope without an arbitrary anchor radius");
        expect(SprintGap.planAfterGroundedStep(captured,actual,late,from,new Vec3Point(edge.x+.01,edge.y,edge.z),delta,.153,.1274,.026,.546)==null,
            "teleport or unpredicted displacement cannot inherit a checked edge step");
        expect(SprintGap.planAfterGroundedStep(captured,actual,late,from,edge,new Vec3Point(0,.01,-.2785),.153,.1274,.026,.546)==null,
            "airborne ascent cannot manufacture a last-grounded launch");
        expect(SprintGap.planAfterGroundedStep(captured,actual,late,edge,edge,delta,.153,.1274,.026,.546)==null,
            "unsupported prior foot cannot prove native grounded clipping");
        expect(SprintGap.planAfterGroundedStep(new CollisionGeometry((x,y,z)->CollisionGeometry.Cell.UNLOADED),actual,late,from,edge,delta,.153,.1274,.026,.546)==null,
            "changed or unloaded terrain invalidates the entire retained step proof");
        System.out.println("PASS: "+checks+" two/three-block sprint-gap arcs, planning, safety and airborne counter-response checks");
    }
}