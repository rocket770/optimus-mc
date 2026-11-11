package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.SprintGap;

import java.util.*;
import net.minecraft.util.MathHelper;

/** Offline corner/edge launches with a proved final grounded step and a narrow live-state envelope. */
public final class EditorPrecisionJump {
    private EditorPrecisionJump() { }
    public static final class Plan {
        public final Vec3Point start,runUp;
        public final float yaw;
        public final double speed;
        public final List<Vec3Point> points;
        Plan(Vec3Point start,Vec3Point runUp,float yaw,List<Vec3Point> points) { this.start=start;this.runUp=runUp;this.yaw=yaw;this.speed=.15;this.points=points; }
    }
    /** One ordinary forward sprint step, then one jump. No injected onGround at takeoff. */
    public static List<Vec3Point> samples(CollisionGeometry geometry,PitRegion area,Vec3Point start,float yaw,double speed,Vec3Point goal) {
        if(!SprintGap.launchSupported(geometry,area,start) || speed<.14 || speed>.16) return Collections.emptyList();
        float angle=yaw*.017453292f,sin=MathHelper.sin(angle),cos=MathHelper.cos(angle);
        Vec3Point p=start;double hx=-(double)sin*speed,hz=(double)cos*speed,vy=-.0784;boolean grounded=true;
        List<Vec3Point> points=new ArrayList<>();points.add(start);
        for(int tick=0;tick<26;tick++) {
            if(Math.abs(hx)<.005) hx=0;if(Math.abs(hz)<.005) hz=0;if(Math.abs(vy)<.005) vy=0;
            if(tick==1) { if(!grounded) return Collections.emptyList();vy=(double).42f;hx-=(double)(sin*.2f);hz+=(double)(cos*.2f); }
            float friction=grounded?.6f*.91f:.91f;
            float acceleration=(grounded?.13f*(.16277136f/(friction*friction*friction)):.026f)*.98f;
            hx-=(double)(sin*acceleration);hz+=(double)(cos*acceleration);
            Vec3Point next=geometry.jumpMove(p,new Vec3Point(hx,vy,hz),area);
            if(next==null || Math.abs(next.x-p.x-hx)>1e-6 || Math.abs(next.z-p.z-hz)>1e-6
                    || vy>0 && next.y<p.y+vy-1e-6) return Collections.emptyList();
            grounded=vy<0 && next.y>p.y+vy+1e-6;points.add(next);
            if(tick==0 && (!grounded || Math.abs(next.y-start.y)>.01)) return Collections.emptyList();
            if(tick>1 && grounded) return Math.abs(next.y-goal.y)<.01 && geometry.standable(area,next)
                && geometry.autoStepClear(next,goal,area)?points:Collections.emptyList();
            if(grounded) vy=0;
            vy=(vy-.08)*(double).98f;hx*=friction;hz*=friction;p=next;
        }
        return Collections.emptyList();
    }
    public static Plan find(CollisionGeometry geometry,PitRegion area,Vec3Point from,Vec3Point to) {
        double x=to.x-from.x,z=to.z-from.z,rise=to.y-from.y;
        if(Math.abs(x)<.1 || Math.abs(z)<.1 || Math.abs(x)>4.01 || Math.abs(z)>4.01
                || x*x+z*z<5 || rise>1.01 || rise< -1.01 || geometry.autoStepClear(from,to,area)) return null;
        float direct=(float)Math.toDegrees(Math.atan2(-x,z));
        for(double a:new double[]{.2,.4,.6,.7,.75,.775,0,-.2,-.4,-.6}) for(double b:new double[]{.2,.4,.6,.7,.75,.775,0,-.2,-.4,-.6}) {
            Vec3Point start=new Vec3Point(from.x+Math.signum(x)*a,from.y,from.z+Math.signum(z)*b);
            if(!geometry.autoStepClear(from,start,area)) continue;
            for(float turn:new float[]{0,-5,5,-10,10,-15,15}) {
                float yaw=direct+turn;double angle=Math.toRadians(yaw),dx=-Math.sin(angle),dz=Math.cos(angle);
                Vec3Point runUp=new Vec3Point(start.x-dx*1.6,start.y,start.z-dz*1.6);
                if(!geometry.autoStepClear(from,runUp,area) || !geometry.autoStepClear(runUp,start,area)) continue;
                List<Vec3Point> points=samples(geometry,area,start,yaw,.15,to);if(points.isEmpty()) continue;
                boolean valid=true;
                envelope:for(double ox:new double[]{-.01,0,.01}) for(double oz:new double[]{-.01,0,.01})
                    for(float heading:new float[]{-.5f,0,.5f}) for(double speed:new double[]{.149,.15,.151}) {
                        Vec3Point trial=new Vec3Point(start.x+ox,start.y,start.z+oz);
                        if(!geometry.autoStepClear(runUp,trial,area) || samples(geometry,area,trial,yaw+heading,speed,to).isEmpty()) { valid=false;break envelope; }
                    }
                if(valid) return new Plan(start,runUp,yaw,points);
            }
        }
        return null;
    }
}