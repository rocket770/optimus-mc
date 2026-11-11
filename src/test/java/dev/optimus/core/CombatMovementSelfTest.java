package dev.optimus.core;

import dev.optimus.core.combat.CombatTap;
import dev.optimus.core.combat.ComboEscape;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.LocalApproach;
import dev.optimus.core.navigation.MotionProgress;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.TravelSprint;

import java.util.*;
import dev.optimus.core.navigation.GridPathfinder.Node;

/** Safe near-contact taps, sprint through small waypoints, corner recovery and block approaches. */
public final class CombatMovementSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static PitRegion area() { PitRegion p=new PitRegion();p.setCornerA(new Vec3Point(-10,0,-10));p.setCornerB(new Vec3Point(15,10,15));return p; }
    private static CollisionGeometry map(boolean obstacle,boolean ceiling,boolean unloaded,boolean gap) {
        return new CollisionGeometry((x,y,z)-> {
            if(unloaded && x>=2) return CollisionGeometry.Cell.UNLOADED;
            List<CollisionGeometry.Box> boxes=new ArrayList<>();
            if(y==0 && !(gap && x==2)) boxes.add(new CollisionGeometry.Box(x,0,z,x+1,1,z+1));
            if(obstacle && x==2 && z==0 && y==1) boxes.add(new CollisionGeometry.Box(x,1,z,x+1,2,z+1));
            if(ceiling && y==3) boxes.add(new CollisionGeometry.Box(x,3,z,x+1,4,z+1));
            return new CollisionGeometry.Cell(true,false,boxes);
        });
    }
    private static void validate(GroundRoute terrain,List<Node> route,Vec3Point current) {
        expect(!route.isEmpty(),"a complete local route is available");
        expect(terrain.edgeClear(current,terrain.point(route.get(0))),"actual fractional position connects safely to route");
        for(int i=0;i<route.size();i++) {
            expect(terrain.standable(route.get(i)),"every local node has a loaded supported body space");
            if(i>0) expect(terrain.traversable(route.get(i-1),route.get(i)),"each local edge independently passes full terrain checks");
        }
    }
    public static void main(String[] args) throws Exception {
        Set<Integer> escapeDelays=new HashSet<>(),escapeLengths=new HashSet<>(),escapeSides=new HashSet<>();
        for(int seed=0;seed<40;seed++) {
            ComboEscape escape=new ComboEscape();escape.seed(seed*0x9e3779b97f4a7c15L);
            escape.observe(10,true,0,true,2.3);
            expect(!escape.responding(11),"one incoming hit keeps ordinary counterplay instead of automatically fleeing");
            escape.observe(20,true,0,true,2.3);
            int first=-1,length=0;boolean backed=false;int lastSide=0;
            for(int tick=20;tick<36;tick++) {
                NearbySteering.Input input=escape.input(tick,2.3,i->true);
                if(input==NearbySteering.Input.NONE) continue;
                if(first<0) first=tick;
                length++;backed|=input.forward<0;
                expect(input.forward<=0 && input.side!=0,"combo escape breaks the straight forward chase with ordinary back/side keys");
                expect(lastSide==0 || lastSide==input.side,"escape holds its chosen side rather than jittering every frame");
                lastSide=input.side;
            }
            expect(first>=21 && first<=23 && length>=6 && length<=10 && backed,"repeated hits produce a bounded delayed backstep/sidestep response");
            escapeDelays.add(first-20);escapeLengths.add(length);escapeSides.add(lastSide);
            escape.observe(36,true,0,true,2.3);escape.observe(37,true,0,true,2.3);
            expect(!escape.responding(38),"fresh hits cannot turn the short escape into permanent retreat");
        }
        expect(escapeDelays.size()==3 && escapeLengths.size()==5 && escapeSides.size()==2,"defensive timings and directions vary within small bounds across seeds");
        ComboEscape trade=new ComboEscape();trade.seed(4);trade.observe(0,true,0,true,2.3);
        trade.attacked(8);trade.observe(9,false,10,true,2.3);trade.observe(10,true,9,true,2.3);
        expect(!trade.responding(13),"recent observed return damage requires more incoming hits before disrupting a trade");
        trade.observe(18,true,8,true,2.3);
        expect(trade.responding(21),"even trading does not suppress escapes under sustained incoming hits");
        NearbySteering.Input fallback=trade.input(21,2.3,i->i==NearbySteering.Input.RIGHT);
        expect(fallback==NearbySteering.Input.RIGHT,"blocked escape side uses a checked alternative");
        expect(trade.input(22,2.3,i->true).side==-1,"terrain-forced side persists when both sides become available");
        expect(trade.input(22,3.2,i->true).forward==0,"an already separated opponent does not cause unnecessary backward movement");
        expect(trade.input(22,2.3,i->false)==NearbySteering.Input.NONE,"no unvalidated escape is emitted when all directions are unsafe");
        trade.observe(23,false,7,false,2.3);expect(!trade.responding(23),"lost visibility/eligibility cancels defensive controls immediately");
        trade.reset();trade.observe(30,true,0,true,2.3);trade.observe(60,true,0,true,2.3);
        expect(!trade.responding(63),"unrelated hits far apart cannot accumulate into a combo");
        trade.observe(65,true,0,true,2.3);trade.observe(66,false,0,true,5);
        expect(!trade.responding(68),"breaking contact ends the defensive interval");
        trade.reset();expect(!trade.responding(70),"reset clears delayed defensive input on menus, respawn and target changes");
        Set<Integer> delays=new HashSet<>(),durations=new HashSet<>();int w=0,s=0;
        for(int seed=0;seed<40;seed++) {
            double tapDistance=seed%3==0?1.9:2.2;
            CombatTap tap=new CombatTap();tap.seed(seed*0x9e3779b97f4a7c15L);tap.observe(0,10,true,2.2,false,false);tap.attacked(11,true);
            tap.observe(10,12,true,tapDistance,false,false);
            int first=-1,length=0;CombatTap.Input type=CombatTap.Input.NONE;
            for(int tick=12;tick<19;tick++) {
                CombatTap.Input input=tap.input(tick,true,2.2,false,false);
                if(input!=CombatTap.Input.NONE) { if(first<0) { first=tick;type=input; }length++; }
            }
            expect(first>=12 && first<=14,"observed near hit schedules only a short response delay");
            expect(length>=1 && length<=2,"tap cannot become a long combat pause");
            delays.add(first-12);durations.add(length);if(type==CombatTap.Input.S_BACK)s++;else w++;
            tap.attacked(20,true);tap.observe(9,20,true,2.2,false,false);tap.observe(10,21,true,2.2,false,false);
            expect(tap.input(21,true,2.2,false,false)==CombatTap.Input.NONE,"rapid damage responses cannot chain repeated movement releases");
        }
        expect(w>0 && s>0 && s<w,"both W and occasional S taps occur");
        expect(delays.size()==3 && durations.size()==2,"response delay and duration vary within their bounds");
        for(int scenario=0;scenario<5;scenario++) {
            CombatTap tap=new CombatTap();tap.seed(42);tap.observe(0,0,true,2.2,false,false);
            tap.attacked(scenario==0?-20:1,scenario!=1);
            tap.observe(10,2,scenario!=2,scenario==3?3.6:2.2,scenario==4,scenario==5);
            for(int tick=2;tick<7;tick++)
                expect(scenario==4?tap.input(tick,true,2.2,true,false)!=CombatTap.Input.S_BACK:
                    tap.input(tick,scenario!=2,scenario==3?3.6:2.2,false,scenario==5)==CombatTap.Input.NONE,
                    "stale/non-sprint/unavailable/distant state cannot produce an unwanted tap");
        }
        CombatTap trading=new CombatTap();trading.seed(42);trading.observe(0,0,true,2.3,false,false);
        trading.attacked(1,true);trading.attacked(2,false);trading.observe(10,3,true,2.3,false,true);
        expect(trading.input(3,true,2.3,false,true)==CombatTap.Input.W_RELEASE,
            "damage-response aim allows an immediate short W tap and a later non-sprint request cannot erase its response");
        expect(trading.input(4,true,2.3,false,true)==CombatTap.Input.NONE,"trade reset lasts one tick instead of abandoning pressure");
        trading.reset();trading.observe(0,0,true,1.9,false,false);trading.attacked(1,true);trading.observe(10,2,true,1.9,false,true);
        expect(trading.input(2,true,1.9,false,true)==CombatTap.Input.S_BACK,"compressed spacing selects a short S tap without a random retreat");
        for(boolean reacting:new boolean[]{false,true}) {
            CombatTap tap=new CombatTap();tap.seed(9);tap.observe(0,0,true,2.2,false,false);tap.attacked(1,true);tap.observe(10,2,true,2.2,false,false);
            tap.input(2,!reacting,4,true,reacting);
            expect(tap.input(4,true,2.2,false,false)==CombatTap.Input.NONE,"airborne/knockback/fleeing target cancellation cannot revive a queued tap");
        }
        CombatTap reset=new CombatTap();reset.seed(8);reset.observe(0,0,true,2.2,false,false);reset.attacked(1,true);reset.observe(10,2,true,2.2,false,false);reset.reset();
        expect(reset.input(4,true,2.2,false,false)==CombatTap.Input.NONE,"target change or stop clears tap intent");
        MotionProgress progress=new MotionProgress();Vec3Point fixed=new Vec3Point(.5,1,.5);
        for(int tick=0;tick<10;tick++) expect(!progress.stuck(fixed,tick,true,true),"brief corner contact doesn't overreact");
        expect(progress.stuck(fixed,10,true,true),"half second of attempted movement triggers recovery");
        for(int tick=11;tick<50;tick++) expect(!progress.stuck(fixed,tick,false,true),"turning and path-search waits are not stuck movement");
        for(int tick=50;tick<80;tick++) expect(!progress.stuck(fixed,tick,true,false),"airborne jump time cannot trigger corner recovery");
        progress.reset();
        for(int tick=0;tick<50;tick++) expect(!progress.stuck(new Vec3Point(.5+tick*.14,1,.5),tick,true,true),"steady small forward progress keeps its route");
        CollisionGeometry clear=map(false,false,false,false);PitRegion pit=area();Vec3Point goal=new Vec3Point(8.5,1,.5);
        for(double next:new double[]{.2,.5,1,2}) expect(TravelSprint.allowed(clear,pit,fixed,new Vec3Point(fixed.x+next,1,.5),goal,false),
            "safe running holds sprint even when the next grid waypoint is short");
        expect(!TravelSprint.allowed(clear,pit,fixed,new Vec3Point(1,1,.5),new Vec3Point(1.5,1,.5),false),"travel destination still provides a braking band");
        expect(!TravelSprint.allowed(clear,pit,fixed,new Vec3Point(1,1,.5),new Vec3Point(2.5,1,.5),true),"close combat chase cannot inherit travel sprint");
        expect(!TravelSprint.allowed(map(true,false,false,false),pit,new Vec3Point(1.1,1,.5),new Vec3Point(1.3,1,.5),goal,false),"small waypoint never authorizes sprint into a nearby block face");
        expect(!TravelSprint.allowed(map(false,false,true,false),pit,new Vec3Point(1.1,1,.5),new Vec3Point(1.3,1,.5),goal,false),"unloaded run-out blocks sprint");
        expect(!TravelSprint.allowed(map(false,false,false,true),pit,new Vec3Point(1.1,1,.5),new Vec3Point(1.3,1,.5),goal,false),"missing support in run-out blocks sprint");
        GroundRoute route=new GroundRoute(map(true,false,false,false).cached(),pit,pit,null,1,true,true);
        List<Node> climb=LocalApproach.route(route,new Vec3Point(1.5,1,.5),new Vec3Point(2.5,2,.5));
        validate(route,climb,new Vec3Point(1.5,1,.5));
        expect(route.point(climb.get(climb.size()-1)).y==2,"complete local approach can plan onto a full block");
        GroundRoute blocked=new GroundRoute(map(true,true,false,false).cached(),pit,pit,null,1,true,true);
        expect(!blocked.edgeClear(new Vec3Point(1.5,1,.5),new Vec3Point(2.5,2,.5)),"low ceiling rejects full-block jump");
        GroundRoute noJump=new GroundRoute(map(true,false,false,false).cached(),pit,pit,null,1,true,false);
        expect(!noJump.edgeClear(new Vec3Point(1.5,1,.5),new Vec3Point(2.5,2,.5)),"unsupported jump effects cannot authorize a block climb");
        List<Node> detour=LocalApproach.route(route,new Vec3Point(1.5,1,.5),new Vec3Point(4.5,1,.5));
        validate(route,detour,new Vec3Point(1.5,1,.5));
        CollisionGeometry captured=CollisionReplaySelfTest.capture();PitRegion actual=new PitRegion();actual.setCornerA(new Vec3Point(-25,62.5,-127));actual.setCornerB(new Vec3Point(25,78,-77));
        GroundRoute actualRoute=new GroundRoute(captured.cached(),actual,actual,null,63,true,true);
        for(Vec3Point start:Arrays.asList(new Vec3Point(-13.8,63.5,-116.9),new Vec3Point(.5,63,-100.5),new Vec3Point(-13.375,68.5,-105.223))) {
            Node aligned=actualRoute.start(start);
            if(aligned!=null) expect(actualRoute.edgeClear(start,actualRoute.point(aligned)),"captured stair/fractional-corner alignment remains collision checked");
        }
        List<Node> capturedApproach=LocalApproach.route(actualRoute,new Vec3Point(.5,63,-100.5),new Vec3Point(6.5,63,-100.5));
        validate(actualRoute,capturedApproach,new Vec3Point(.5,63,-100.5));
        Vec3Point middleCorner=new Vec3Point(14.846,65,-78.989),upperTarget=new Vec3Point(9.5,66,-80.5);
        List<Node> middleApproach=LocalApproach.route(actualRoute,middleCorner,upperTarget);
        validate(actualRoute,middleApproach,middleCorner);
        expect(actualRoute.point(middleApproach.get(1)).y>=65,"provisional middle-platform approach does not jump back down before an upward chase");
        expect(LocalApproach.route(actualRoute,new Vec3Point(24.9,63,-100.5),new Vec3Point(6.5,63,-100.5)).isEmpty(),"outside-body pit start cannot produce a local route");
        System.out.println("PASS: "+checks+" combat taps, sprint continuity, corner progress and checked block-approach checks");
    }
}