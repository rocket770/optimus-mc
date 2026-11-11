package dev.optimus.core;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.Vec3Point;

import java.util.Random;
import static dev.optimus.core.combat.CombatClicks.Action.*;

/** Engagement sequences with range/aim/visibility changes, pauses and real attack pacing. */
public final class CombatClicksSelfTest {
    private static int checks;
    private static final Vec3Point EYE=new Vec3Point(0,1.62,0);
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static CombatClicks.Action at(CombatClicks clicks,long tick,double distance,float yaw,
                                         boolean allowed,boolean visible,boolean clearRay) {
        CollisionGeometry.Box box=new CollisionGeometry.Box(-.3,0,distance-.3,.3,1.8,distance+.3);
        boolean reach=CombatAim.distanceSquared(EYE,box)<=CombatClicks.ATTACK_REACH*CombatClicks.ATTACK_REACH;
        boolean hit=visible && clearRay && CombatAim.hit(EYE,box,yaw,0,CombatClicks.ATTACK_REACH)!=null;
        boolean near=visible && clearRay && !reach && CombatAim.hit(EYE,box,yaw,0,CombatClicks.SWING_REACH)!=null;
        return clicks.choose(tick,allowed,hit,near);
    }
    public static void main(String[] args) {
        CombatClicks clicks=new CombatClicks();clicks.defer(100,6);
        for(int tick=100;tick<106;tick++) {
            expect(at(clicks,tick,2.5,0,true,true,true)==NONE,"activation/recovery delay blocks real attacks");
            expect(at(clicks,tick,3.5,0,true,true,true)==NONE,"activation/recovery delay also blocks air swings");
        }
        expect(at(clicks,106,3.5,0,true,true,true)==AIR_SWING,"opponent at 3.5 blocks starts a nearby missed swing");
        clicks.performed(AIR_SWING,106,0);
        expect(at(clicks,106,2.5,0,true,true,true)==NONE,"no double click in one tick");
        expect(at(clicks,107,3.5,0,true,true,true)==NONE,"near misses are paced");
        expect(at(clicks,107,3,0,true,true,true)==ATTACK,"returning into reach next tick is not delayed by a missed swing");
        clicks.performed(ATTACK,107,4);
        for(int tick=108;tick<111;tick++)
            expect(at(clicks,tick,2.5,0,true,true,true)==NONE,"a miss cannot erase the real-hit cooldown");
        expect(at(clicks,111,3.5,0,true,true,true)==AIR_SWING,"knockback into near-miss range keeps swinging after hit cooldown");
        clicks.performed(AIR_SWING,111,0);
        expect(at(clicks,112,3,0,true,true,true)==ATTACK,"closing the knockback gap restores real attack immediately");
        clicks.performed(ATTACK,112,2);
        expect(at(clicks,114,3,0,true,true,true)==ATTACK,"real two-tick profile interval remains available despite four-tick air-swing spacing");
        for(double distance:new double[]{3.05001,3.2,3.5,3.79}) {
            CombatClicks fresh=new CombatClicks();
            expect(at(fresh,200,distance,0,true,true,true)==AIR_SWING,"just-outside-range can swing but never attack");
            expect(at(fresh,200,distance,0,false,true,true)==NONE,"lost lobby/run/pit/target permission blocks air swing");
            expect(at(fresh,200,distance,0,true,false,true)==NONE,"hidden target cannot trigger air swing");
            expect(at(fresh,200,distance,0,true,true,false)==NONE,"obstructed view ray cannot trigger air swing");
            expect(at(fresh,200,distance,45,true,true,true)==NONE,"off-target crosshair cannot trigger air swing");
            expect(at(fresh,200,distance,180,true,true,true)==NONE,"target behind bot cannot trigger air swing");
        }
        expect(at(new CombatClicks(),200,3.8001,0,true,true,true)==NONE,"swing radius ends at 3.5 blocks from eye to body");
        expect(at(new CombatClicks(),200,8,0,true,true,true)==NONE,"long chase does not spam air swings");
        expect(at(new CombatClicks(),200,2.5,0,false,true,true)==NONE,"lost permission also blocks reachable attacks");
        expect(at(new CombatClicks(),200,2.5,45,true,true,true)==NONE,"bad near aim is not disguised as an out-of-range swing");
        clicks=new CombatClicks();int swings=0;
        for(int tick=0;tick<100;tick++) {
            CombatClicks.Action action=at(clicks,tick,3.5,0,true,true,true);
            if(action==AIR_SWING) { swings++;clicks.performed(action,tick,0); }
            expect(action!=ATTACK,"sustained out-of-reach target never produces an attack request");
        }
        expect(swings==25,"sustained near miss is limited to five swings/second");
        expect(at(clicks,1000,3.5,0,true,true,true)==AIR_SWING,"pause resumes with one action");
        clicks.performed(AIR_SWING,1000,0);
        expect(at(clicks,1000,3.5,0,true,true,true)==NONE && at(clicks,1001,3.5,0,true,true,true)==NONE,
            "missed time cannot produce catch-up bursts");
        clicks.defer(1001,6);
        expect(at(clicks,1006,3,0,true,true,true)==NONE && at(clicks,1007,3,0,true,true,true)==ATTACK,
            "GUI/recovery deferral overrides both cadence histories");
        for(CombatProfile profile:CombatProfile.values()) {
            clicks=new CombatClicks();Random pacing=new Random(42);long tick=0;
            for(int i=0;i<30;i++) {
                expect(at(clicks,tick,2.5,0,true,true,true)==ATTACK,"profile starts next real attack when ready");
                int delay=profile.attackDelay(pacing);clicks.performed(ATTACK,tick,delay);
                for(int dt=1;dt<delay;dt++)
                    expect(at(clicks,tick+dt,2.5,0,true,true,true)==NONE,"profile delay remains enforced on every intervening tick");
                tick+=delay;
            }
        }
        System.out.println("PASS: "+checks+" near-miss, range-transition, pause and attack-cadence checks");
    }
}