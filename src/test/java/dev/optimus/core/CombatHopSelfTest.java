package dev.optimus.core;

import dev.optimus.core.combat.CombatHop;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;

/** Elevated reach, safe landings and a bounded single attempt before route fallback. */
public final class CombatHopSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static CollisionGeometry floor(int problem) {
        return new CollisionGeometry((x,y,z)-> {
            if(problem==3 && x==0 && z==0 && y==2) return CollisionGeometry.Cell.UNLOADED;
            if(y==0 && (problem!=2 || x==0 && z==0)) return new CollisionGeometry.Cell(true,false,
                java.util.Collections.singletonList(new CollisionGeometry.Box(x,0,z,x+1,1,z+1)));
            if(problem==1 && y==3) return new CollisionGeometry.Cell(true,false,
                java.util.Collections.singletonList(new CollisionGeometry.Box(x,3,z,x+1,4,z+1)));
            return CollisionGeometry.Cell.AIR;
        }).cached();
    }
    public static void main(String[] args) {
        PitRegion area=new PitRegion();area.setCornerA(new Vec3Point(-5,0,-5));area.setCornerB(new Vec3Point(8,10,8));
        Vec3Point start=new Vec3Point(.5,1,.5),goal=new Vec3Point(2.9,4.5,.5);
        CollisionGeometry.Box elevated=new CollisionGeometry.Box(2.6,4.5,.2,3.2,6.3,.8);
        expect(CombatHop.possible(floor(0),area,start,1.62,elevated,0,0,.546,(a,b)->true),
            "a target beyond grounded melee becomes reachable during a safe native hop");
        expect(!CombatHop.possible(floor(1),area,start,1.62,elevated,0,0,.546,(a,b)->true),"low ceiling rejects the jump");
        expect(!CombatHop.possible(floor(3),area,start,1.62,elevated,0,0,.546,(a,b)->true),"unloaded overhead space rejects the jump");
        expect(!CombatHop.possible(floor(0),area,start,1.62,elevated,0,0,.546,(a,b)->false),"blocked future attack rays cannot justify a jump");
        expect(!CombatHop.possible(floor(0),area,start,1.62,elevated,.1,0,.546,(a,b)->true),"running momentum cannot inherit a stationary jump proof");
        expect(!CombatHop.possible(floor(0),area,start,1.62,new CollisionGeometry.Box(2.6,2,.2,3.2,3.8,.8),0,0,.546,(a,b)->true),
            "already reachable melee stays grounded");
        expect(!CombatHop.possible(floor(0),area,start,1.62,new CollisionGeometry.Box(4,4.5,.2,4.6,6.3,.8),0,0,.546,(a,b)->true),
            "horizontal distance beyond attack reach cannot be repaired by a vertical jump");
        expect(!CombatHop.possible(floor(2),area,new Vec3Point(.97,1,.5),1.62,elevated,.06,0,.546,(a,b)->true),
            "momentum and uncertainty that would leave the small landing reject the hop");
        for(int seed=0;seed<40;seed++) {
            CombatHop hop=new CombatHop();hop.seed(seed);Object target=new Object();int launch=0;
            for(int tick=100;tick<=104;tick++) {
                CombatHop.Action action=hop.update(tick,target,start,goal,true,true,true);
                expect(tick!=100 || action==CombatHop.Action.PREPARING,"human reaction never launches on the first observation");
                if(action==CombatHop.Action.JUMP) { launch=tick;break; }
            }
            expect(launch>=101 && launch<=103,"seeded reaction stays within one to three ticks");
            expect(hop.update(launch+1,target,new Vec3Point(.5,1.42,.5),goal,false,false,false)==CombatHop.Action.AIRBORNE,
                "airborne tracking retains the one-hop attempt without holding jump");
            expect(hop.update(launch+12,target,start,goal,true,true,true)==CombatHop.Action.NONE,"landing yields to ordinary pathfinding immediately");
            for(int tick=launch+13;tick<launch+160;tick++) expect(hop.update(tick,target,start,goal,true,true,true)==CombatHop.Action.NONE,
                "a failed reach attempt cannot repeat at the same target and standing position while search continues");
            expect(hop.update(launch+161,target,new Vec3Point(2.1,1,.5),goal,true,true,true)==CombatHop.Action.PREPARING,
                "meaningful route progress can enable a fresh attempt from a different position");
            hop.cancel();expect(!hop.active(),"damage/cancellation releases jump ownership");
        }
        CombatHop waiting=new CombatHop();waiting.seed(4);Object target=new Object();
        for(int tick=0;tick<=12;tick++) expect(waiting.update(tick,target,start,goal,true,true,false)==CombatHop.Action.PREPARING,
            "bounded preparation can align the camera while ordinary attack checks remain independent");
        expect(waiting.update(13,target,start,goal,true,true,false)==CombatHop.Action.NONE,"alignment timeout returns to pathfinding");
        expect(!waiting.mayTry(200,target,start,goal),"alignment timeout cannot restart forever from the same position");
        System.out.println("PASS: "+checks+" elevated jump reach, geometry, reaction, single-attempt and route-fallback checks");
    }
}