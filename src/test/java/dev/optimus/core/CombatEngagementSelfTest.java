package dev.optimus.core;

import dev.optimus.core.combat.CombatSpacing;
import dev.optimus.core.combat.CombatStrafe;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;

/** Momentum-aware distance and checked sustained strafing, independent of aim reaction. */
public final class CombatEngagementSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        CombatSpacing spacing=new CombatSpacing();
        expect(spacing.direction(2.6,0,0,2.3,true)==CombatSpacing.Direction.HOLD,"engaged stationary target keeps useful reach instead of closing to 1.7");
        expect(spacing.direction(2.7,-.35,.18,2.4,true)==CombatSpacing.Direction.HOLD,"two closing players release forward early instead of committing to a retreat");
        expect(spacing.direction(1.9,-.1,.1,1.6,true)==CombatSpacing.Direction.HOLD,"normal closing contact releases W without reversing into S");
        expect(spacing.direction(2.7,.18,.1,2.4,true)==CombatSpacing.Direction.FORWARD,"separating opponent retains forward pressure");
        expect(spacing.direction(2.5,0,0,2.7,false)==CombatSpacing.Direction.FORWARD,"unavailable elevated/covered melee still closes toward a usable hit");
        expect(spacing.direction(1.2,.2,0,.9,true)==CombatSpacing.Direction.BACK,"overlap makes space even before the other player separates");
        expect(spacing.direction(2.5,0,0,2.2,true,true)==CombatSpacing.Direction.FORWARD,"active trade carries safe pressure into the next hit instead of stopping sprint early");
        expect(spacing.direction(2.2,0,0,1.9,true,true)==CombatSpacing.Direction.FORWARD,"acquired trade pressure survives small range changes");
        expect(spacing.direction(1.95,0,0,1.65,true,true)==CombatSpacing.Direction.HOLD,"trade pressure releases before point-blank crowding");
        expect(spacing.direction(2.2,0,0,1.9,true,true)==CombatSpacing.Direction.HOLD,"released pressure needs a meaningful gap before restarting");
        expect(spacing.direction(2.1,-.05,-.18,1.8,true,true)==CombatSpacing.Direction.FORWARD,
            "incoming knockback keeps counter-input active even while the opponent closes the gap");
        expect(spacing.direction(2.05,-.2,.05,1.75,true,true)==CombatSpacing.Direction.FORWARD,
            "an opponent rushing an active trade gets counter-pressure instead of perpetual backpedaling");
        spacing.reset();
        expect(spacing.sprint(2.25,true,false,true),"active trade can re-engage sprint after a W tap inside ordinary chase range");
        expect(!spacing.sprint(2.25,false,false,true),"trade pressure cannot keep sprint when forward movement is ineligible");
        expect(spacing.sprint(2.05,true,false,true) && !spacing.sprint(1.9,true,false,true),
            "trade sprint resumes after a tap at close range and still ends at the contact bound");
        expect(spacing.direction(Double.NaN,0,0,0,true)==CombatSpacing.Direction.HOLD,"invalid observations release movement");
        CombatStrafe strafe=new CombatStrafe();Vec3Point self=new Vec3Point(0,1,0),target=new Vec3Point(0,1,2.55),still=new Vec3Point(.1,0,0);
        int side=0;
        for(int tick=0;tick<30;tick++) {
            NearbySteering.Input input=strafe.choose(tick,self,target,still,0,0,0,0,true,i->true);
            expect(input.forward==0 && input.side!=0,"lateral trading opponent gets sustained side movement");
            expect(side==0 || input.side==side,"symmetric ground does not cause random strafe reversals");side=input.side;
        }
        NearbySteering.Input opposite=strafe.choose(31,self,target,still,0,0,0,0,true,i->i.side==-1);
        expect(opposite==NearbySteering.Input.RIGHT,"blocked preferred side uses a checked opposite side");
        expect(strafe.choose(32,self,target,still,0,0,0,1,false,i->true)==NearbySteering.Input.FORWARD,"escaping opponent receives straight forward pursuit");
        expect(strafe.choose(33,self,target,still,0,0,0,1,true,i->false)==NearbySteering.Input.NONE,"unsafe candidates never authorize input");
        expect(strafe.choose(50,self,target,new Vec3Point(0,0,0),0,0,0,0,true,i->true)==NearbySteering.Input.NONE,
            "a settled straight-line trade does not force endless lateral drift");
        System.out.println("PASS: "+checks+" momentum spacing and coordinated strafe checks");
    }
}