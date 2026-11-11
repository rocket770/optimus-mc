package dev.optimus.client;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.Vec3Point;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Cross-check attack-ray math with Minecraft's native hitbox intersection. No world or network. */
public final class CombatAimSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        Vec3 eye=new Vec3(0,1.62,0);
        for(double reach:new double[]{CombatClicks.ATTACK_REACH,CombatClicks.SWING_REACH})
        for(double distance:new double[]{1.4,3,3.2,3.5,3.8001}) for(double height:new double[]{0,1.5}) {
            AxisAlignedBB box=new AxisAlignedBB(-.3,height,distance-.3,.3,height+1.8,distance+.3);
            CollisionGeometry.Box shape=new CollisionGeometry.Box(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ);
            for(double yaw:new double[]{-20,-10,0,10,20}) for(double pitch:new double[]{-35,-10,0,10,35}) {
                double ry=Math.toRadians(yaw),rp=Math.toRadians(pitch);
                Vec3 look=new Vec3(-Math.sin(ry)*Math.cos(rp),-Math.sin(rp),Math.cos(ry)*Math.cos(rp)).normalize();
                MovingObjectPosition nativeHit=box.calculateIntercept(eye,eye.addVector(look.xCoord*reach,look.yCoord*reach,look.zCoord*reach));
                Vec3Point hit=CombatAim.hit(new Vec3Point(eye.xCoord,eye.yCoord,eye.zCoord),shape,
                    new Vec3Point(look.xCoord,look.yCoord,look.zCoord),reach);
                expect((nativeHit!=null)==(hit!=null),"core and native rays agree at reach="+reach+" distance="+distance+" height="+height+" yaw="+yaw+" pitch="+pitch);
                if(hit!=null) expect(nativeHit.hitVec.squareDistanceTo(new Vec3(hit.x,hit.y,hit.z))<1e-10,"core hit point matches native hit surface");
            }
        }
        Vec3Point origin=new Vec3Point(0,1.62,0);
        expect(CombatAim.clearPoint(origin,new CollisionGeometry.Box(-.3,0,3.8,.3,1.8,4.4),CombatClicks.ATTACK_REACH,hit->true)==null,
            "alternate aim points never authorize a body beyond attack reach");
        expect(CombatAim.clearPoint(origin,new CollisionGeometry.Box(-.3,0,1.7,.3,1.8,2.3),CombatClicks.ATTACK_REACH,hit->false)==null,
            "a fully obscured reachable body has no melee-priority aim point");
        System.out.println("PASS: "+checks+" native Minecraft hitbox intersection and exposed-aim safety checks");
    }
}