package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;

import java.util.*;
import net.minecraft.util.MathHelper;

/** Offline two-hop graph transitions. No live bot executor is implied by an editor edge. */
public final class EditorMomentumJump {
    private EditorMomentumJump() { }
    public static final class Plan {
        public final Vec3Point start;
        public final float yaw;
        public final List<Vec3Point> points;
        Plan(Vec3Point start,float yaw,List<Vec3Point> points) { this.start=start;this.yaw=yaw;this.points=points; }
    }
    /** Forward+sprint+held jump, starting at rest. Native clipping proves each grounded re-jump. */
    public static List<Vec3Point> samples(CollisionGeometry geometry,PitRegion area,Vec3Point start,float yaw,Vec3Point goal) {
        if(!geometry.standable(area,start)) return Collections.emptyList();
        Vec3Point p=start;double hx=0,hz=0,vy=-.0784;boolean grounded=true;int cooldown=0,jumps=0;
        List<Vec3Point> points=new ArrayList<>();points.add(p);
        float angle=yaw*.017453292f,sin=MathHelper.sin(angle),cos=MathHelper.cos(angle);
        for(int tick=0;tick<40;tick++) {
            if(cooldown>0) cooldown--;
            if(Math.abs(hx)<.005) hx=0;if(Math.abs(hz)<.005) hz=0;if(Math.abs(vy)<.005) vy=0;
            if(grounded && cooldown==0) { vy=(double).42f;hx-=(double)(sin*.2f);hz+=(double)(cos*.2f);cooldown=10;jumps++; }
            float friction=grounded?.6f*.91f:.91f;
            float acceleration=(grounded?.13f*(.16277136f/(friction*friction*friction)):.026f)*.98f;
            hx-=(double)(sin*acceleration);hz+=(double)(cos*acceleration);
            Vec3Point next=geometry.jumpMove(p,new Vec3Point(hx,vy,hz),area);
            if(next==null || Math.abs(next.x-p.x-hx)>1e-6 || Math.abs(next.z-p.z-hz)>1e-6
                    || vy>0 && next.y<p.y+vy-1e-6) return Collections.emptyList();
            grounded=vy<0 && next.y>p.y+vy+1e-6;points.add(next);
            if(grounded) {
                if(jumps==1 && Math.abs(next.y-start.y)>.01) return Collections.emptyList();
                if(jumps==2) return Math.abs(next.y-goal.y)<.01 && geometry.standable(area,next)
                    && geometry.autoStepClear(next,goal,area)?points:Collections.emptyList();
                vy=0;
            }
            vy=(vy-.08)*(double).98f;hx*=friction;hz*=friction;p=next;
        }
        return Collections.emptyList();
    }
    public static Plan find(CollisionGeometry geometry,PitRegion area,Vec3Point from,Vec3Point to) {
        double x=to.x-from.x,z=to.z-from.z;
        // Only the one-block-down, five-empty-block class is considered here.
        // Single jumps keep their existing limits and executor.
        boolean alongX=Math.abs(Math.abs(x)-6)<.01 && Math.abs(z)<=1.01;
        boolean alongZ=Math.abs(Math.abs(z)-6)<.01 && Math.abs(x)<=1.01;
        if(Math.abs(from.y-to.y-1)>.01 || !(alongX || alongZ)) return null;
        int dx=alongX?(int)Math.signum(x):0,dz=alongZ?(int)Math.signum(z):0;
        double cross=alongX?z:x;
        double side=(alongX?(from.z+to.z)/2:(from.x+to.x)/2)+Math.signum(cross)*.1;
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        for(double back:new double[]{1.5,1.6,1.7,1.8,1.9,2,2.1,2.2,2.3}) {
            Vec3Point start=new Vec3Point(alongX?from.x-dx*back:side,from.y,alongZ?from.z-dz*back:side);
            if(!geometry.autoStepClear(from,start,area)) continue;
            List<Vec3Point> centre=samples(geometry,area,start,yaw,to);if(centre.isEmpty()) continue;
            boolean valid=true;
            envelope:for(double a:new double[]{-.02,0,.02}) for(double b:new double[]{-.02,0,.02}) for(float turn:new float[]{-1,0,1}) {
                Vec3Point trial=new Vec3Point(start.x+a,start.y,start.z+b);
                if(!geometry.autoStepClear(from,trial,area) || samples(geometry,area,trial,yaw+turn,to).isEmpty()) { valid=false;break envelope; }
            }
            if(valid) return new Plan(start,yaw,centre);
        }
        return null;
    }
}