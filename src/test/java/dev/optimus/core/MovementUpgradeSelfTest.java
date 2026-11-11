package dev.optimus.core;

import dev.optimus.core.combat.CombatFocus;
import dev.optimus.core.combat.CombatSpacing;
import dev.optimus.core.control.TargetWhitelist;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.JumpPreparation;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.SprintJump;
import dev.optimus.core.navigation.TravelSprint;
import dev.optimus.core.navigation.WalkSteering;
import dev.optimus.core.safety.ControlPolicy;
import dev.optimus.core.safety.RunSafety;

import java.util.*;
import dev.optimus.core.navigation.GridPathfinder.Node;

/** Stair phases, stable steering, validated sprint trajectories and live target selection updates. */
public final class MovementUpgradeSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static PitRegion area(double low,double high) {
        PitRegion p=new PitRegion();p.setCornerA(new Vec3Point(-30,low,-130));p.setCornerB(new Vec3Point(30,high,30));return p;
    }
    private static CollisionGeometry flat(int obstacle,int gap,int ceiling) {
        return new CollisionGeometry((x,y,z)-> {
            if(y==62 && x!=gap || x==obstacle && y==63 || y==ceiling) return new CollisionGeometry.Cell(true,false,
                Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1)));
            return CollisionGeometry.Cell.AIR;
        });
    }
    public static void main(String[] args) throws Exception {
        JumpPreparation preparation=new JumpPreparation();
        Vec3Point prepStart=new Vec3Point(.5,1,.5),prepLanding=new Vec3Point(1.5,2,.5);
        for(int tick=0;tick<=24;tick++) expect(preparation.observe(prepStart,prepLanding,90,tick),"short fixed-heading preparation has a bounded alignment window");
        expect(!preparation.observe(prepStart,prepLanding,90,25),"no heading or position progress ends a stuck lineup");
        expect(!preparation.allowed(prepStart,prepLanding,26),"failed edge stays excluded across replanning");
        expect(preparation.allowed(prepStart,new Vec3Point(.5,2,1.5),26),"an alternative landing remains available immediately");
        expect(preparation.allowed(new Vec3Point(3,1,.5),prepLanding,26),"a materially different launch position can retry the landing");
        expect(preparation.allowed(prepStart,prepLanding,125),"landing rejection expires without a permanent blacklist");
        preparation.reset();
        for(int tick=0;tick<=60;tick++) expect(preparation.observe(new Vec3Point(.5+(tick%2)*.2,1,.5),prepLanding,0,tick),"moving runway preparation retains progress within its total limit");
        expect(!preparation.observe(prepStart,prepLanding,0,61),"oscillating runway progress cannot reset the total preparation timeout");
        preparation.reset();
        for(int tick=0;tick<30;tick++) expect(preparation.observe(prepStart,prepLanding,90-tick*3,tick),"normal bounded turning counts as progress without requiring walking");
        preparation.launched();
        expect(preparation.observe(prepStart,new Vec3Point(2.5,2,.5),90,100),"a committed launch clears preparation history for the next chain jump");
        WalkSteering steering=new WalkSteering();float yaw=0,previous=0;
        for(int tick=0;tick<40;tick++) {
            float before=90-yaw,step=steering.turn(before);
            expect(Math.abs(step)<=12 && Math.abs(step)<=Math.abs(before)+.0001,"navigation turn remains capped and cannot overshoot");
            if(Math.abs(before)>.8) expect(step*before>=0,"navigation turn never moves away from desired angle");
            if(tick<4) expect(Math.abs(step-previous)<=3.001,"turn accelerates gradually");
            previous=step;yaw+=step;
        }
        expect(Math.abs(90-yaw)<.8,"damped turn settles without left/right oscillation");
        expect(steering.turn(.2f)==0 && steering.turn(-.2f)==0,"small opposite errors remain in navigation deadband");
        expect(WalkSteering.input(-90,0)==NearbySteering.Input.LEFT && WalkSteering.input(90,0)==NearbySteering.Input.RIGHT,"sideways routes use A/D with the camera facing forward");
        expect(WalkSteering.input(180,0)==NearbySteering.Input.BACK,"a route behind the camera uses S immediately");
        expect(WalkSteering.input(-45,0)==NearbySteering.Input.FORWARD_LEFT,"diagonal travel uses W+A without a camera turn");
        for(int travel=-180;travel<=180;travel+=15) expect(Math.abs(WalkSteering.alignment(travel,17))<=22.5,"precise nonsprint jumps only need the nearest WASD-axis correction");
        expect(WalkSteering.passed(new Vec3Point(.5,63,0),new Vec3Point(1.5,63,0),new Vec3Point(1.9,63,.1)),"sprint overshoot advances the waypoint instead of aiming backward");
        expect(!WalkSteering.passed(new Vec3Point(.5,63,0),new Vec3Point(1.5,63,0),new Vec3Point(1.9,63,1)),"passing on the wrong side of an obstacle cannot consume a waypoint");
        CombatSpacing spacing=new CombatSpacing();
        expect(spacing.direction(1.86,false)==CombatSpacing.Direction.FORWARD,"close chase resumes sooner");
        for(int i=0;i<20;i++) expect(spacing.direction(i%2==0?1.84:1.86,false)==CombatSpacing.Direction.FORWARD,
            "small target-distance updates cannot alternate forward/hold every tick");
        expect(spacing.direction(1.7,false)==CombatSpacing.Direction.HOLD,"chase settles at a bounded engagement distance");
        expect(spacing.direction(1.84,false)==CombatSpacing.Direction.HOLD,"settled stance waits for the next approach threshold");
        expect(spacing.direction(1.14,false)==CombatSpacing.Direction.BACK,"overlapping range initiates normal retreat");
        for(int i=0;i<10;i++) expect(spacing.direction(i%2==0?1.14:1.16,false)==CombatSpacing.Direction.BACK,
            "retreat remains steady across its entry threshold");
        expect(spacing.direction(1.35,false)==CombatSpacing.Direction.HOLD,"retreat ends after making a little room");
        expect(spacing.direction(1.1,true)==CombatSpacing.Direction.HOLD,"damage response does not retreat");
        expect(spacing.direction(1.61,true)==CombatSpacing.Direction.FORWARD,"damage response keeps immediate close pursuit");
        expect(!spacing.sprint(2.39,true) && spacing.sprint(2.4,true),"sprint starts early enough to carry into contact");
        for(int i=0;i<20;i++) expect(spacing.sprint(i%2==0?2.39:2.41,true),"sprint remains steady around its entry threshold");
        for(double gap:new double[]{3.5,3,2.79,2.5,2.25,2.05,1.91})
            expect(spacing.sprint(gap,true),"closing on a stationary target no longer drops sprint before contact");
        expect(!spacing.sprint(1.9,true) && !spacing.sprint(2.39,true),"close engagement ends sprint with a bounded restart distance");
        expect(spacing.sprint(4,true) && !spacing.sprint(4,false),"blocked movement immediately releases sprint despite distance band");
        spacing.direction(4,false);spacing.sprint(4,true);spacing.reset();
        expect(spacing.direction(1.84,false)==CombatSpacing.Direction.HOLD && !spacing.sprint(2.39,true),"target/run reset cannot inherit approach or sprint intent");
        expect(spacing.direction(Double.NaN,false)==CombatSpacing.Direction.HOLD && !spacing.sprint(Double.NaN,true),"invalid target distance never permits movement");
        spacing.reset();
        expect(spacing.sprint(2.3,true,true),"opponent pulling away starts catch-up sprint before the ordinary distance threshold");
        expect(spacing.sprint(2.25,true,false),"catch-up sprint persists through one tick of target interpolation jitter");
        expect(spacing.sprint(2,true,false) && !spacing.sprint(1.9,true,false),"catch-up continues to close engagement distance");
        expect(!spacing.sprint(2.3,true,false),"stationary close target cannot retrigger catch-up sprint");
        spacing.sprint(2.3,true,true);
        expect(!spacing.sprint(2.3,false,true) && !spacing.sprint(2.3,true,false),"blocked catch-up releases input and clears its intent");
        CollisionGeometry captured=CollisionReplaySelfTest.capture();PitRegion pit=area(62.5,78);
        // Actual stone-brick lower stair tread from the captured pit, off the block centre.
        Vec3Point lower=new Vec3Point(-13.8,63.5,-116.9),upper=new Vec3Point(-13.5,64,-116.5);
        expect(captured.standable(pit,lower),"actual lower stair phase has collision support");
        expect(captured.autoStepClear(lower,upper,pit),"actual lower half to upper half is auto-stepable without a jump");
        GroundRoute route=new GroundRoute(captured.cached(),pit,pit,null,63,true,true);
        Node aligned=route.start(lower);
        expect(aligned!=null,"standing on a half stair resolves a usable grid start despite different centre height");
        expect(captured.autoStepClear(lower,route.point(aligned),pit),"stair alignment is reached by actual half-step surfaces");
        expect(captured.autoStepClear(lower,new Vec3Point(-13.5,65,-115.5),pit),"look-ahead can follow multiple actual stair treads without unnecessary jumps");
        expect(captured.pursuitClear(lower,new Vec3Point(-13.5,65,-115.5),pit),"direct combat chase follows actual multiple stair treads without switching to route alignment");
        CollisionGeometry syntheticStairs=new CollisionGeometry((x,y,z)-> {
            if(y==62) return new CollisionGeometry.Cell(true,false,Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,63,z+1)));
            if(x==1 && y==63) return new CollisionGeometry.Cell(true,false,Arrays.asList(
                new CollisionGeometry.Box(1,63,z,2,63.5,z+1),new CollisionGeometry.Box(1.5,63.5,z,2,64,z+1)));
            return CollisionGeometry.Cell.AIR;
        });
        expect(syntheticStairs.autoStepClear(new Vec3Point(.5,63,-100.5),new Vec3Point(1.5,64,-100.5),pit),"whole-block stair rise is decomposed into two half steps");
        expect(!flat(1,-100,-100).autoStepClear(new Vec3Point(.5,63,-100.5),new Vec3Point(1.5,64,-100.5),pit),"full one-block wall still requires a jump rather than pretending it is a stair");
        CollisionGeometry stairRun=new CollisionGeometry((x,y,z)-> {
            if(y==62) return new CollisionGeometry.Cell(true,false,Collections.singletonList(new CollisionGeometry.Box(x,62,z,x+1,63,z+1)));
            if(x>=1 && x<=4 && y==62+x) return new CollisionGeometry.Cell(true,false,Arrays.asList(
                new CollisionGeometry.Box(x,62+x,z,x+1,62.5+x,z+1),new CollisionGeometry.Box(x+.5,62.5+x,z,x+1,63+x,z+1)));
            if(x>=5 && y==66) return new CollisionGeometry.Cell(true,false,Collections.singletonList(new CollisionGeometry.Box(x,66,z,x+1,67,z+1)));
            return CollisionGeometry.Cell.AIR;
        }).cached();
        for(double x:new double[]{.5,.85,1.15,1.6,2.1}) {
            Vec3Point stair=stairRun.walkEnd(new Vec3Point(.5,63,-100.5),new Vec3Point(x,63,-100.5),pit);
            Vec3Point next=stairRun.walkEnd(stair,new Vec3Point(x+.7,stair.y,-100.5),pit);
            expect(next!=null && TravelSprint.allowed(stairRun,pit,stair,next,new Vec3Point(7.5,67,-100.5),false),
                "continuous native half-step stair run permits sprint across changing waypoint/run-out heights");
        }
        expect(TravelSprint.allowed(captured,pit,lower,new Vec3Point(-13.5,65,-115.5),new Vec3Point(-13.5,66,-113.5),false),
            "captured stair treads permit a rising supported sprint run-out");
        for(CollisionGeometry blockedRun:new CollisionGeometry[]{flat(1,-100,-100),flat(-100,1,-100),flat(-100,-100,65)})
            expect(!TravelSprint.allowed(blockedRun,pit,new Vec3Point(.5,63,-100.5),new Vec3Point(1.5,64,-100.5),new Vec3Point(7.5,67,-100.5),false),
                "a wall, gap or low ceiling still cannot authorize stair sprinting");
        CollisionGeometry open=flat(-100,-100,-100);Vec3Point start=new Vec3Point(.5,63,-100.5),goal=new Vec3Point(8.5,63,-100.5);
        List<Vec3Point> corners=Arrays.asList(start,new Vec3Point(1.5,63,-100.5),new Vec3Point(1.5,63,-101.5),
            new Vec3Point(2.5,63,-101.5),new Vec3Point(2.5,63,-102.5),new Vec3Point(3.5,63,-102.5));
        int cursor=WalkSteering.lookAhead(1,corners.size(),start,corners::get,p->open.autoStepClear(start,p,pit));
        expect(cursor==5,"safe diagonal shortcut reaches the farther clear point and advances the route cursor");
        Vec3Point afterShortcut=new Vec3Point(2.2,63,-102.2);
        expect(!WalkSteering.passed(start,corners.get(1),afterShortcut),"regression: old cursor would fail passed-waypoint test and aim backward");
        expect(corners.get(cursor).x>afterShortcut.x && corners.get(cursor).z<afterShortcut.z,"new cursor keeps aiming ahead after shortcut");
        expect(captured.autoStepClear(start,corners.get(cursor),pit),"shortcut is also clear in actual captured pit terrain");
        int blocked=WalkSteering.lookAhead(1,corners.size(),start,corners::get,p->flat(2,-100,-100).autoStepClear(start,p,pit));
        expect(blocked==2,"look-ahead stops before a wall instead of skipping an obstructed corner");
        int gapStop=WalkSteering.lookAhead(1,corners.size(),start,corners::get,p->flat(-100,2,-100).autoStepClear(start,p,pit));
        expect(gapStop==2,"look-ahead cannot skip a required gap takeoff");
        int[] evaluated={0};
        expect(WalkSteering.lookAhead(1,100,start,i->start,p->{evaluated[0]++;return true;})==12 && evaluated[0]==11,
            "longer diagonal look-ahead remains bounded to eleven subsequent points");
        List<Vec3Point> stale=Arrays.asList(start,new Vec3Point(2.5,63,-100.5),new Vec3Point(.5,63,-105.5));
        CollisionGeometry wall=flat(1,-100,-100).cached();
        expect(WalkSteering.shortcut(0,stale.size(),start,stale::get,p->wall.autoStepClear(start,p,pit))==2,
            "hint rejoin can reach a later point on this side of a blocked old corner");
        List<Vec3Point> distant=Arrays.asList(start,new Vec3Point(20.5,63,-100.5),new Vec3Point(.5,63,-105.5));
        expect(WalkSteering.shortcut(0,distant.size(),start,distant::get,p->open.autoStepClear(start,p,pit))==2,
            "an out-of-range old detour does not prevent a reachable later rejoin");
        List<Vec3Point> gap=Arrays.asList(start,new Vec3Point(2.5,63,-100.5),new Vec3Point(3.5,63,-100.5));
        CollisionGeometry voidStrip=flat(-100,1,-100).cached();
        expect(WalkSteering.shortcut(0,gap.size(),start,gap::get,p->voidStrip.autoStepClear(start,p,pit))==0,
            "hint smoothing cannot walk across a void or consume a required takeoff");
        CollisionGeometry unloaded=new CollisionGeometry((x,y,z)->x==1?CollisionGeometry.Cell.UNLOADED:
            y==62?new CollisionGeometry.Cell(true,false,Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1))):CollisionGeometry.Cell.AIR);
        expect(WalkSteering.shortcut(0,gap.size(),start,gap::get,p->unloaded.autoStepClear(start,p,pit))==0,
            "a shortcut needs the full loaded corridor, not only clear supported endpoints");
        evaluated[0]=0;
        expect(WalkSteering.shortcut(0,100,start,i->start,p->{evaluated[0]++;return false;})==0 && evaluated[0]==24,
            "hint rejoin collision work remains bounded on a long blocked route");
        SprintJump.Plan jump=SprintJump.plan(open.cached(),pit,start,goal,.28,.1274,.02548,.546);
        expect(jump!=null,"flat supported run permits normal sprint jump");
        expect(jump.maximumDistance>3 && jump.maximumDistance<6,"sprint model includes boost, ground acceleration, ground/air friction and bounded landing");
        double sprintMotion=.1274*.546/(1-.546);
        expect(sprintMotion>.15 && sprintMotion<.16,"ordinary native sprint stored motion is below the former .20 gate");
        SprintJump.Plan normalJump=SprintJump.plan(open.cached(),pit,start,goal,sprintMotion,.1274,.02548,.546);
        expect(normalJump!=null,"ordinary sprint enables a validated travel jump without a speed potion");
        expect(SprintJump.plan(captured.cached(),pit,start,goal,sprintMotion,.1274,.02548,.546)!=null,"normal sprint jump is valid in captured open pit terrain");
        SprintJump.Plan chaseJump=SprintJump.plan(open.cached(),pit,start,goal,sprintMotion,.1274,.02548,.546,true);
        expect(chaseJump!=null && chaseJump.maximumDistance+3.25<=8,"long chase jump lands before close combat range");
        expect(SprintJump.plan(open,pit,start,new Vec3Point(6.5,63,-100.5),sprintMotion,.1274,.02548,.546,true)==null,
            "six-block close chase keeps running rather than entering a committed jump");
        expect(SprintJump.plan(open,pit,start,goal,.08,.1274,.02548,.546)==null,"starting or stalled sprint cannot launch a jump");
        expect(SprintJump.plan(open,pit,start,goal,.30,.24945,.02548,.546)==null,"fast ground running skips a jump that would slow it down");
        expect(SprintJump.plan(flat(-100,3,-100),pit,start,goal,sprintMotion,.1274,.02548,.546)==null,"normal sprint still cannot cross an unvalidated gap");
        expect(SprintJump.plan(flat(-100,-100,65),pit,start,goal,sprintMotion,.1274,.02548,.546)==null,"normal sprint still respects jump headroom");
        List<Vec3Point> samples=SprintJump.samples(start,1,0,.28,.1274,.02548,.546);
        expect(samples.get(1).x-start.x>.60 && samples.get(1).x-start.x<.61,"first sprint-jump tick includes native .2 boost and ground input");
        expect(samples.get(samples.size()-1).y==start.y,"native vertical model returns to supported floor");
        expect(!SprintJump.samples(start,1,0,Double.NaN,.1274,.02548,.546).iterator().hasNext(),"invalid motion cannot authorize jump");
        expect(SprintJump.plan(flat(-100,3,-100),pit,start,goal,.28,.1274,.02548,.546)==null,"sprint jumping cannot bridge an unvalidated gap");
        expect(SprintJump.plan(flat(3,-100,-100),pit,start,goal,.28,.1274,.02548,.546)==null,"obstacle in sprint arc denies jump");
        expect(SprintJump.plan(flat(-100,-100,65),pit,start,goal,.28,.1274,.02548,.546)==null,"ceiling in full jump arc denies jump");
        expect(SprintJump.plan(open,pit,start,new Vec3Point(3.5,63,-100.5),.28,.1274,.02548,.546)==null,"short chase keeps landing/run-out room instead of sprint jumping");
        expect(SprintJump.plan(captured.cached(),pit,start,new Vec3Point(7.5,63,-100.5),.28,.1274,.02548,.546)!=null,"captured open pit corridor supports sprint-jump trajectory");
        expect(!SprintJump.continuing(open,pit,jump,new Vec3Point(2,64,-99),new Vec3Point(2.3,64,-99)),"sideways knockback outside checked sprint corridor releases input");
        CollisionGeometry cliff=new CollisionGeometry((x,y,z)-> {
            if(y==62 || x==0 && y==68) return new CollisionGeometry.Cell(true,false,Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1)));
            return CollisionGeometry.Cell.AIR;
        });
        Vec3Point lip=new Vec3Point(.5,69,-100.5),landing=new Vec3Point(1.5,63,-100.5);
        GroundRoute falls=new GroundRoute(cliff,pit,pit,null,63,true,true);
        expect(falls.edgeClear(lip,landing),"pit fall without damage needs no named rim or three-block cap");
        PitRegion outside=area(60,90),combat=area(62.5,78);combat.setCornerA(new Vec3Point(-30,62.5,-130));combat.setCornerB(new Vec3Point(-10,78,30));
        expect(!new GroundRoute(cliff,outside,combat,null,63,true,true).edgeClear(lip,landing),"no-fall-damage allowance cannot extend into lobby terrain outside combat pit");
        expect(new GroundRoute(cliff,pit,pit,null,63,true,true).extraNeighbors(new Node(0,138,-101)).iterator().hasNext(),"planner exposes deeper supported pit drop candidates");
        CombatFocus<String> focus=new CombatFocus<>();TargetWhitelist whitelist=new TargetWhitelist();whitelist.add("OldTarget");
        List<String> players=Arrays.asList("OldTarget","NewTarget");
        expect("OldTarget".equals(focus.select(players,whitelist::contains,n->n.equals("OldTarget")?16:4)),"old listed target selected before live edit");
        focus.observeDamage(20,0,1);focus.observeDamage(19,10,2);
        RunSafety run=new RunSafety();Object world=new Object();run.startChecking(0);run.activate("duelslobby9","dynamiclobbyX",world);
        whitelist.add("NewTarget");focus.targetsChanged();
        expect(run.mayControl() && "NewTarget".equals(focus.select(players,whitelist::contains,n->n.equals("OldTarget")?16:4)),"live addition selects new closer target without restarting active run");
        expect(focus.reacting(3),"live target update preserves current damage reaction window");
        whitelist.remove("NewTarget");focus.targetsChanged();
        expect("OldTarget".equals(focus.select(players,whitelist::contains,n->n.equals("OldTarget")?16:4)),"live removal immediately drops removed target");
        whitelist.clear();focus.targetsChanged();
        expect(focus.select(players,whitelist::contains,n->1)==null && run.mayControl(),"cleared live list leaves active run available for pit roaming");
        expect(!ControlPolicy.allowNavigation(true,false,true,true,true,false),"movement improvements cannot authorize unverified games");
        System.out.println("PASS: "+checks+" steering, native stairs, sprint trajectory, pit drop and live target-update checks");
    }
}