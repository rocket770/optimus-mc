package dev.optimus.core;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatFocus;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.PursuitGoal;

import java.util.*;

/** Moving-target retention, first-observed-hit reaction and safe direct/air pursuit. */
public final class CombatFollowSelfTest {
    private static int checks;
    private static void expect(boolean condition,String why) { checks++;if(!condition) throw new AssertionError(why); }
    private static class Target { boolean allowed=true;double distance;Target(double distance) { this.distance=distance; } }
    private static CollisionGeometry floor(int gap,int wall,int unloaded,int hazard) {
        return new CollisionGeometry((x,y,z)-> {
            if(x==unloaded) return CollisionGeometry.Cell.UNLOADED;
            if(x==hazard && y==2) return new CollisionGeometry.Cell(true,true,Collections.emptyList());
            if((y==1 && x!=gap) || (x==wall && y==2)) return new CollisionGeometry.Cell(true,false,
                Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1)));
            return CollisionGeometry.Cell.AIR;
        });
    }
    public static void main(String[] args) {
        CombatFocus<Target> focus=new CombatFocus<>();Target a=new Target(9),b=new Target(25);
        List<Target> targets=Arrays.asList(a,b);
        expect(focus.select(targets,t->t.allowed,t->t.distance)==a,"nearest eligible target acquired");
        a.distance=36;b.distance=4;
        expect(focus.select(targets,t->t.allowed,t->t.distance)==a,"moving away or another target becoming closer doesn't lose focus");
        expect(focus.select(Arrays.asList(b),t->t.allowed,t->t.distance)==b,"removed entity cannot remain a ghost target");
        b.allowed=false;
        expect(focus.select(targets,t->t.allowed,t->t.distance)==a,"leaving whitelist/pit/alive predicate releases prior target");
        a.distance=48*48;
        expect(focus.select(targets,t->t.allowed,t->t.distance)==null,"neither invalid nor out-of-range target can be selected");
        a.distance=9;b.allowed=true;focus.reset();
        expect(focus.select(targets,t->t.allowed,t->t.distance)==b,"fresh run clears old target preference");
        focus.reset();a.distance=4;b.distance=9;
        expect(focus.select(targets,t->t.allowed,t->t.distance,true,0)==a,"all-target mode acquires the nearest player");
        a.distance=4;b.distance=3;
        expect(focus.select(targets,t->t.allowed,t->t.distance,true,1)==a,"crossing players get a brief stable aim commitment");
        expect(focus.select(targets,t->t.allowed,t->t.distance,true,4)==b,"all-target mode switches to the nearest within four ticks");
        a.distance=.25;
        expect(focus.select(targets,t->t.allowed,t->t.distance,true,5)==a,"a much closer player interrupts the short commitment immediately");
        a.allowed=false;
        expect(focus.select(targets,t->t.allowed,t->t.distance,true,6)==b,"an invalid retained target is released immediately in all mode");
        a.allowed=true;a.distance=.1;
        expect(focus.select(targets,t->t.allowed,t->t.distance)==b,"named mode preserves the existing eligible target retention");
        focus.reset();
        expect(!focus.observeDamage(20,0,1) && !focus.reacting(1),"initial healthy state doesn't simulate a hit");
        expect(focus.observeDamage(19,10,2) && focus.reacting(2),"health loss/hurt onset react in same observed tick");
        expect(!focus.observeDamage(19,9,3),"normal hurt countdown doesn't reset route every frame");
        expect(focus.reacting(11) && !focus.reacting(12),"reaction window is bounded to ten client ticks");
        expect(focus.observeDamage(19,10,13),"hurt restart detects a new hit without requiring health loss");
        expect(!focus.observeDamage(20,9,14),"healing doesn't trigger damage reaction");
        focus.reset();expect(!focus.reacting(14),"stop clears pending reaction");
        CombatTurn turn=new CombatTurn();
        float ordinary=turn.yaw(90,CombatProfile.BALANCED);
        expect(ordinary>=9 && ordinary<=10,"ordinary balanced turn starts its faster bounded acceleration");
        float reaction=turn.yaw(90,CombatProfile.BALANCED,true);
        expect(reaction>ordinary && reaction<=26,"hit reaction accelerates countertracking without an instant snap");
        float reversal=turn.yaw(-90,CombatProfile.BALANCED,true);
        expect(reversal<0 && reversal>=-16,"hit reaction reverses toward target on first tick");
        float vertical=turn.pitch(90,CombatProfile.BALANCED,true);
        expect(vertical>=8.6f && vertical<=9.6f,"reactive pitch retains bounded acceleration");
        expect(turn.yaw(2,CombatProfile.BALANCED,true)==2,"reactive aim doesn't overshoot small gap");
        turn.reset();
        expect(turn.yaw(180,CombatProfile.PACED,true)==-1.5f,"paced profile keeps its original acceleration");
        PitRegion pit=new PitRegion();pit.setCornerA(new Vec3Point(0,1,0));pit.setCornerB(new Vec3Point(10,10,10));
        Vec3Point start=new Vec3Point(2.5,2,2.5),target=new Vec3Point(6.5,2,2.5);
        CollisionGeometry clear=floor(-1,-1,-1,-1);
        expect(clear.pursuitClear(start,target,pit),"clear nearby floor follows directly instead of starting A*");
        expect(clear.pursuitClear(start,new Vec3Point(6.5,3,2.5),pit),"jumping target remains followed along supported floor underneath");
        expect(!floor(4,-1,-1,-1).pursuitClear(start,target,pit),"safe endpoints cannot authorize pursuit across a missing floor");
        expect(!floor(-1,4,-1,-1).pursuitClear(start,target,pit),"wall between nearby targets requires a route");
        expect(!floor(-1,-1,4,-1).pursuitClear(start,target,pit),"unloaded middle of corridor blocks direct chase");
        expect(!floor(-1,-1,-1,4).pursuitClear(start,target,pit),"hazard between safe endpoints blocks direct chase");
        expect(!clear.pursuitClear(start,new Vec3Point(9.9,2,2.5),pit),"whole target body must remain inside pit");
        expect(!clear.pursuitClear(new Vec3Point(.5,2,2.5),new Vec3Point(9.5,2,2.5),pit),"direct pursuit radius is bounded");
        PitRegion wide=new PitRegion();wide.setCornerA(new Vec3Point(0,1,0));wide.setCornerB(new Vec3Point(20,10,10));
        CombatFocus<Target> chase=new CombatFocus<>();Target opponent=new Target(4),nearby=new Target(9);
        List<Target> pair=Arrays.asList(opponent,nearby);
        expect(chase.select(pair,t->t.allowed,t->t.distance)==opponent && chase.pursuitRange()==8,"new target uses normal direct-chase acquisition range");
        chase.followingDirect(true);
        for(double distance:new double[]{3,5,7.9,8.1,7.8,9,11.9}) {
            opponent.distance=distance*distance;nearby.distance=1;
            Vec3Point live=new Vec3Point(start.x+distance,2,start.z);
            expect(chase.select(pair,t->t.allowed,t->t.distance)==opponent,"successive knockbacks cannot replace target with a closer player");
            boolean direct=clear.pursuitClear(start,live,wide,chase.pursuitRange());
            chase.followingDirect(direct);
            expect(direct && chase.pursuitRange()==12,"established chase crosses old eight-block boundary without switching to route mode");
        }
        expect(!clear.pursuitClear(start,new Vec3Point(14.6,2,2.5),wide,chase.pursuitRange()),"continuation remains bounded to twelve checked blocks");
        Vec3Point far=new Vec3Point(11.5,2,2.5);
        expect(!clear.pursuitClear(start,far,wide,8) && clear.pursuitClear(start,far,wide,12),"extended range belongs to established direct chase only");
        expect(!floor(8,-1,-1,-1).pursuitClear(start,far,wide,12),"extended chase cannot cross a floor gap in a later batch");
        expect(!floor(-1,8,-1,-1).pursuitClear(start,far,wide,12),"extended chase cannot cross a wall in a later batch");
        expect(!floor(-1,-1,8,-1).pursuitClear(start,far,wide,12),"extended chase rejects unloaded intermediate terrain");
        expect(!floor(-1,-1,-1,8).pursuitClear(start,far,wide,12),"extended chase rejects hazardous intermediate terrain");
        chase.followingDirect(false);
        expect(chase.pursuitRange()==8,"blocked or occluded corridor immediately ends continuation band");
        chase.followingDirect(true);opponent.allowed=false;
        expect(chase.select(pair,t->t.allowed,t->t.distance)==nearby && chase.pursuitRange()==8,"ineligible target loses selection and chase continuation immediately");
        chase.followingDirect(true);chase.targetsChanged();
        expect(chase.pursuitRange()==8,"live target changes clear previous chase mode");
        chase.select(pair,t->t.allowed,t->t.distance);chase.followingDirect(true);chase.reset();
        expect(chase.pursuitRange()==8,"stop cannot retain a previous chase band");
        for(double height:new double[]{.1,.6,1.2,1.6,2.4,1.7,.8,0}) {
            Vec3Point live=new Vec3Point(6.5,2+height,2.5),destination=clear.pursuitDestination(live,true,pit);
            expect(destination!=null && destination.x==live.x && destination.z==live.z && destination.y==2,
                "target knockback arc resolves to stable supported floor instead of restarting routes on vertical changes");
            expect(clear.pursuitClear(start,destination,pit),"checked direct pursuit persists through the airborne target arc");
        }
        Vec3Point high=new Vec3Point(6.5,5.5,2.5);
        expect(clear.pursuitDestination(high,true,pit)==high,"unmodeled high target keeps actual destination for normal pathfinding");
        Vec3Point standing=new Vec3Point(6.5,3,2.5);
        expect(clear.pursuitDestination(standing,false,pit)==standing,"grounded target on another level is never projected through a platform");
        expect(clear.pursuitDestination(new Vec3Point(9.9,3,2.5),true,pit)==null,"projection cannot pull an out-of-bounds target back into eligibility");
        Vec3Point overGap=new Vec3Point(4.5,3.5,2.5);
        CollisionGeometry missingFloor=floor(4,-1,-1,-1);
        expect(missingFloor.pursuitDestination(overGap,true,pit)==overGap && !missingFloor.pursuitClear(start,overGap,pit),
            "airborne target over a gap cannot invent a supported pursuit floor");
        CollisionGeometry lowCeiling=new CollisionGeometry((x,y,z)-> {
            if(y==1 || y==4) return new CollisionGeometry.Cell(true,false,Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1)));
            return CollisionGeometry.Cell.AIR;
        });
        Vec3Point abovePlatform=new Vec3Point(6.5,5.2,2.5);
        Vec3Point platformGoal=lowCeiling.pursuitDestination(abovePlatform,true,pit);
        expect(platformGoal!=null && platformGoal.y==5 && !lowCeiling.pursuitClear(start,platformGoal,pit),
            "airborne target resolves its own platform, never a walk destination beneath a solid ceiling");
        Vec3Point airborne=new Vec3Point(2.5,3,2.5);
        expect(clear.knockbackControlClear(airborne,new Vec3Point(2.7,3,2.5),pit),"ordinary knockback permits checked small air-control input");
        expect(!floor(-1,-1,2,-1).knockbackControlClear(airborne,new Vec3Point(2.7,3,2.5),pit),"air control cannot move over unloaded landing column");
        expect(clear.knockbackControlClear(new Vec3Point(2.5,6,2.5),new Vec3Point(2.7,6,2.5),pit),"damage-free pit permits deeper checked landing column");
        expect(!clear.knockbackControlClear(airborne,new Vec3Point(9.9,3,2.5),pit),"air control cannot leave pit or make a large step");
        expect(CombatProfile.BALANCED.minimumAttackTicks==2 && CombatProfile.BALANCED.maximumAttackTicks==4,"reaction doesn't bypass attack cooldown");
        CombatFocus<Target> engaged=new CombatFocus<>();
        expect(!engaged.recentCombat(100),"fresh run allows travel jumps");
        engaged.attacked(100);
        expect(engaged.recentCombat(100) && engaged.recentCombat(111) && !engaged.recentCombat(112),"outgoing hit protects close combat from new sprint jumps for twelve ticks");
        engaged.observeDamage(20,0,120);engaged.observeDamage(18,10,121);
        expect(engaged.recentCombat(132) && !engaged.recentCombat(133),"incoming hit also protects the close combat response from jumping");
        engaged.attacked(140);engaged.targetsChanged();
        expect(engaged.recentCombat(141),"changing targets cannot bypass the recent-combat jump hold");
        engaged.reset();expect(!engaged.recentCombat(141),"stop clears combat hold");
        Vec3Point oldGoal=new Vec3Point(10,2,2);
        for(int tick=1;tick<16;tick++) {
            Vec3Point moving=new Vec3Point(10+tick*.2,2,2);
            expect(!PursuitGoal.refresh(oldGoal,moving,true,tick),"running target cannot cancel search before it can finish");
        }
        expect(!PursuitGoal.refresh(oldGoal,new Vec3Point(13.2,2,2),true,59)
            && PursuitGoal.refresh(oldGoal,new Vec3Point(13.2,2,2),true,60),"moving search gets enough bounded time to finish while checked local movement continues");
        expect(!PursuitGoal.refresh(oldGoal,new Vec3Point(12,2,2),false,5) && PursuitGoal.refresh(oldGoal,new Vec3Point(12,2,2),false,6),
            "completed route makes progress before its moving destination is refreshed");
        expect(PursuitGoal.refresh(oldGoal,new Vec3Point(17,2,2),true,1),"large target displacement refreshes immediately");
        expect(PursuitGoal.refresh(oldGoal,new Vec3Point(10,6,2),true,1),"different elevation refreshes immediately");
        expect(!PursuitGoal.refresh(oldGoal,new Vec3Point(10.5,2.1,2),true,100),"small harmless goal jitter never forces replan");
        Vec3Point eye=new Vec3Point(0,1.62,0);
        CollisionGeometry.Box opponentBox=new CollisionGeometry.Box(-.3,0,2.7,.3,1.8,3.3);
        Vec3Point bodyAim=CombatAim.point(eye,opponentBox);
        expect(bodyAim.y<1.8 && bodyAim.y>0 && bodyAim.y<eye.y,"body aim stays inside target below its top edge");
        Vec3Point bodyHit=CombatAim.hit(eye,opponentBox,0,0,2.75);
        expect(bodyHit!=null && Math.abs(bodyHit.z-2.7)<1e-9,"three-block feet separation can still put actual target surface inside 2.75-block reach");
        expect(CombatAim.distanceSquared(eye,opponentBox)<2.75*2.75,"reachable body keeps melee active instead of switching into pathfinding");
        expect(CombatAim.hit(eye,new CollisionGeometry.Box(-.3,0,2.7501,.3,1.8,3.35),0,0,2.75)==null,"actual hitbox beyond reach cannot be attacked");
        expect(CombatAim.hit(eye,opponentBox,45,0,2.75)==null,"looking beside target rejects attack even when target is near");
        expect(CombatAim.hit(eye,opponentBox,180,0,2.75)==null,"target behind the view ray cannot be attacked");
        expect(CombatAim.hit(eye,opponentBox,0,60,2.75)==null,"looking under target rejects attack");
        expect(CombatAim.hit(eye,opponentBox,Float.NaN,0,2.75)==null,"nonfinite view angle rejects attack");
        CollisionGeometry.Box rising=new CollisionGeometry.Box(-.3,1.5,2.2,.3,3.3,2.8);
        Vec3Point aim=CombatAim.point(eye,rising);
        float pitch=(float)-Math.toDegrees(Math.atan2(aim.y-eye.y,aim.z-eye.z));
        expect(CombatAim.hit(eye,rising,0,pitch,2.75)!=null,"airborne body in reach remains attackable despite greater feet distance");
        Vec3Point knockedEye=new Vec3Point(0,2.62,0);
        CollisionGeometry.Box lowerTarget=new CollisionGeometry.Box(-.3,0,1.7,.3,1.8,2.3);
        aim=CombatAim.point(knockedEye,lowerTarget);pitch=(float)-Math.toDegrees(Math.atan2(aim.y-knockedEye.y,aim.z-knockedEye.z));
        expect(CombatAim.hit(knockedEye,lowerTarget,0,pitch,2.75)!=null,"bot can aim and hit a reachable body while airborne from knockback");
        expect(CombatAim.hit(eye,new CollisionGeometry.Box(-.3,0,1.1,.3,1.8,1.7),10,0,2.75)!=null,
            "close target inside crosshair is hittable even when centre angle exceeds former eight-degree tolerance");
        System.out.println("PASS: "+checks+" target retention, knockback continuity, damage reaction and supported pursuit checks");
    }
}