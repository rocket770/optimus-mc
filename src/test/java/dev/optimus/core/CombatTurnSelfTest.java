package dev.optimus.core;

import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTurn;

import java.util.*;

/** Acquisition deadlines, moving-target tracking and turn-envelope invariants. */
public final class CombatTurnSelfTest {
    private static int checks;
    private static void expect(boolean value,String detail) {
        checks++;if(!value) throw new AssertionError(detail);
    }
    public static void main(String[] args) {
        Set<Float> firstSteps=new HashSet<>();int min90=100,max90=0,min180=100,max180=0;
        for(int seed=0;seed<40;seed++) {
            for(boolean pitch:new boolean[]{false,true}) for(float target:new float[]{2,30,90,179,-90,-179}) {
                CombatTurn turn=new CombatTurn();turn.seed(seed);float angle=0,previous=0;
                float limit=pitch?18:32,acceleration=pitch?6:10;int elapsed=0;
                boolean accelerated=false,braked=false,cruised=false;
                for(int tick=1;tick<=30;tick++) {
                    float gap=target-angle,step=pitch?turn.pitch(gap,CombatProfile.BALANCED):turn.yaw(gap,CombatProfile.BALANCED);
                    expect(Float.isFinite(step) && Math.signum(step)==Math.signum(gap),"finite stationary turn follows the shortest remaining angle");
                    expect(Math.abs(step)<=limit+.001 && Math.abs(step)<=Math.abs(gap)+.001,"turn respects axis cap and current target without overshoot");
                    expect(Math.abs(Math.abs(step)-Math.abs(previous))<=acceleration+.002,"stationary acquisition accelerates and brakes within its limit");
                    accelerated|=Math.abs(step)>Math.abs(previous)+.01;
                    braked|=Math.abs(step)<Math.abs(previous)-.01;
                    cruised|=Math.abs(step)>limit*.9 && Math.abs(step-previous)<.001;
                    if(!pitch && target==90 && tick==1) firstSteps.add(step);
                    angle+=step;previous=step;
                    if(Math.abs(target-angle)<.001) { elapsed=tick;break; }
                }
                expect(elapsed>0,"stationary target converges without a settling tail");
                if(!pitch && target==90) {
                    expect(elapsed<=6,"90-degree yaw acquisition fits six ticks across seeds");
                    min90=Math.min(min90,elapsed);max90=Math.max(max90,elapsed);
                }
                if(!pitch && target==179) {
                    expect(elapsed<=9 && accelerated && cruised && braked,"large turn accelerates, cruises and brakes within nine ticks");
                    min180=Math.min(min180,elapsed);max180=Math.max(max180,elapsed);
                }
                if(pitch && target==90) expect(elapsed<=9 && braked,"large vertical correction brakes and converges promptly");
            }
            for(float speed:new float[]{-24,-15,-5,5,15,24}) {
                CombatTurn turn=new CombatTurn();turn.seed(seed);float angle=0;
                for(int tick=1;tick<=50;tick++) {
                    float desired=speed*tick,gap=CombatTurn.wrap(desired-angle),step=turn.yaw(gap,CombatProfile.BALANCED);
                    expect(Float.isFinite(step) && Math.abs(step)<=32 && step*gap>=0 && Math.abs(step)<=Math.abs(gap)+.001,
                        "moving opponent never produces a wrong-way, oversized or overshooting turn");
                    angle+=step;
                    if(tick>=10) expect(Math.abs(CombatTurn.wrap(desired-angle))<.05,"sustained strafe angular motion is tracked without braking lag");
                }
                float reversed=turn.yaw(-90*Math.signum(speed),CombatProfile.BALANCED,true);
                expect(reversed*speed<0 && Math.abs(reversed)<=16,"countertracking reverses promptly without coasting away");
            }
        }
        expect(firstSteps.size()>30,"independent seeds create bounded turn-speed variation");
        CombatTurn first=new CombatTurn(),second=new CombatTurn();first.seed(42);second.seed(42);
        for(int i=0;i<80;i++) {
            float gap=(float)(Math.sin(i*.3)*170);
            expect(first.yaw(gap,CombatProfile.BALANCED,i%7==0)==second.yaw(gap,CombatProfile.BALANCED,i%7==0),"same aim seed reproduces yaw");
            expect(first.pitch(gap*.4f,CombatProfile.BALANCED)==second.pitch(gap*.4f,CombatProfile.BALANCED),"same aim seed reproduces pitch");
        }
        first.seed(42);float initial=first.yaw(90,CombatProfile.BALANCED);
        first.yaw(90,CombatProfile.BALANCED);first.seed(42);
        expect(first.yaw(90,CombatProfile.BALANCED)==initial,"seeding resets turn momentum and variation");
        first.reset();float restart=first.yaw(90,CombatProfile.BALANCED);
        expect(restart>=9 && restart<=10,"target/session reset starts a fresh bounded acceleration");
        expect(first.yaw(Float.NaN,CombatProfile.BALANCED)==0 && first.pitch(Float.POSITIVE_INFINITY,CombatProfile.BALANCED)==0,"nonfinite targets clear their turn safely");
        expect(first.yaw(90,CombatProfile.BALANCED)>0 && first.pitch(-30,CombatProfile.BALANCED)<0,"valid tracking recovers after an invalid angle");
        first.reset();expect(first.yaw(-358,CombatProfile.BALANCED)==2,"yaw seam chooses the two-degree turn");
        first.reset();expect(first.yaw(90,CombatProfile.BASELINE)==14 && first.pitch(-30,CombatProfile.BASELINE)==-8,"baseline comparison keeps its original caps");
        first.reset();expect(first.yaw(90,CombatProfile.PACED)==1.5f && first.yaw(-90,CombatProfile.PACED)==0,"paced comparison keeps its original ramp and reversal behavior");
        System.out.println("Balanced acquisition across 40 seeds: yaw90="+min90+"-"+max90+" ticks; yaw179="+min180+"-"+max180+" ticks");
        System.out.println("PASS: "+checks+" turn acquisition, trapezoid, seeded variation, tracking and reversal checks");
    }
}