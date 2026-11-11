package dev.optimus.core;

import dev.optimus.core.combat.CombatSpacing;
import dev.optimus.core.combat.CombatStrafe;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;

/** Temporal regressions: small observation changes must not repeatedly pulse movement keys. */
public final class CombatStabilitySelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        CombatSpacing spacing=new CombatSpacing();
        CombatSpacing.Direction previous=spacing.direction(2.7,0,0,2.4,true,true);
        int forwardChanges=0,backs=0;
        for(int tick=0;tick<80;tick++) {
            double distance=tick%2==0?2.19:2.21;
            CombatSpacing.Direction next=spacing.direction(distance,0,0,distance-.3,true,true);
            if(next!=previous) forwardChanges++;
            if(next==CombatSpacing.Direction.BACK) backs++;
            previous=next;
        }
        CombatStrafe strafe=new CombatStrafe();Vec3Point self=new Vec3Point(0,1,0),target=new Vec3Point(0,1,2.55);
        int sideChanges=0,previousSide=0;
        for(int tick=0;tick<80;tick++) {
            NearbySteering.Input input=strafe.choose(tick,self,target,new Vec3Point(tick%2==0?.034:.036,0,0),
                0,0,0,0,true,i->true);
            if(tick>0 && input.side!=previousSide) sideChanges++;
            previousSide=input.side;
        }
        System.out.println("80-tick boundary noise: forward changes="+forwardChanges+", side changes="+sideChanges);
        expect(forwardChanges<=2,"two-centimetre range noise must not repeatedly pulse W during a trade");
        expect(backs==0,"ordinary contact noise cannot request a backward correction");
        expect(sideChanges<=12,"tiny lateral observation changes cannot toggle A/D every tick");
        for(int tick=0;tick<80;tick++) expect(!spacing.separating(tick%2==0?.045:.055),
            "radial noise around the old chase threshold cannot repeatedly disable strafing");
        expect(spacing.separating(.3),"clear separation still enables immediate pursuit");
        for(int tick=0;tick<8;tick++) spacing.separating(0);
        expect(!spacing.separating(0),"settled contact promptly releases the separation state");

        spacing.reset();
        for(int tick=0;tick<40;tick++) {
            double distance=tick%2==0?1.89:1.91;
            expect(spacing.direction(distance,-.15,.05,distance-.3,true,true)!=CombatSpacing.Direction.BACK,
                "a rushing opponent at normal contact range does not alternate W and S");
        }
        expect(spacing.direction(1.2,-.1,.05,.9,true,true)==CombatSpacing.Direction.BACK,"actual overlap still makes room");
        expect(spacing.direction(1.45,.04,-.05,1.15,true,true)==CombatSpacing.Direction.BACK,"compression correction has its own release band");
        expect(spacing.direction(1.7,.04,-.05,1.4,true,true)!=CombatSpacing.Direction.BACK,"backward correction ends promptly after space opens");
        expect(spacing.direction(3.4,.2,0,3.1,false,true)==CombatSpacing.Direction.FORWARD,"a genuinely escaping opponent is pursued immediately");

        strafe.reset();
        NearbySteering.Input held=strafe.choose(0,self,target,new Vec3Point(.1,0,0),0,0,0,0,true,i->true);
        expect(held.side!=0,"clear lateral target motion can start a strafe");
        for(int tick=1;tick<8;tick++) {
            final int forward=tick%2;
            NearbySteering.Input input=strafe.choose(tick,self,target,new Vec3Point(tick%2==0?-.1:.1,0,0),
                0,0,0,forward,true,i->true);
            expect(input.forward==forward && input.side==held.side,"forward releases do not rescore or reverse a safe held strafe");
        }
        NearbySteering.Input safe=strafe.choose(8,self,target,new Vec3Point(.1,0,0),0,0,0,0,true,i->i.side==-held.side);
        expect(safe.side==-held.side,"a newly blocked held side yields immediately to safe input");
        expect(strafe.choose(9,self,target,new Vec3Point(.1,0,0),0,0,0,1,false,i->true)==NearbySteering.Input.FORWARD,
            "lost strafe eligibility interrupts a hold immediately");
        expect(strafe.choose(10,self,target,new Vec3Point(.1,0,0),0,0,0,1,true,i->false)==NearbySteering.Input.NONE,
            "a hold never bypasses terrain validation");
        System.out.println("PASS: "+checks+" temporal combat movement stability checks");
    }
}