package dev.optimus.client;

import dev.optimus.core.CollisionReplaySelfTest;
import dev.optimus.core.EditorMomentumJump;
import dev.optimus.core.EditorPrecisionJump;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.SprintGap;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.*;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.FoodStats;
import net.minecraft.entity.player.PlayerCapabilities;
import sun.misc.Unsafe;

/** Each advanced editor edge, through actual input and native movement, including launch variation. */
public final class EditorMomentumJumpSelfTest {
    private static int checks;
    private static void expect(boolean b,String message) { checks++;if(!b) throw new AssertionError(message); }
    private static Field field(Class<?> type,String name) throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField(name);f.setAccessible(true);return f; } catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> type,String name,Class<?>... args) throws Exception { Method m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m; }
    private static Vec3Point point(JsonElement element) { JsonArray a=element.getAsJsonArray();return new Vec3Point(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble()); }
    public static void main(String[] args) throws Exception {
        String data=new String(Files.readAllBytes(Paths.get("editor/pit-map.js")),StandardCharsets.UTF_8).trim();
        JsonObject map=new JsonParser().parse(data.substring("window.PIT_MAP=".length(),data.length()-1)).getAsJsonObject();
        JsonArray chains=map.getAsJsonArray("momentumChains"),precision=map.getAsJsonArray("precisionJumps"),nodes=map.getAsJsonArray("nodes");
        expect(chains.size()>0,"momentum graph connections are present");
        expect(precision.size()>0,"precision graph connections are present");
        CollisionGeometry geometry=CollisionReplaySelfTest.capture("duels-pit-2026-10-15.tsv.gz").cached();
        PitRegion pit=new PitRegion();pit.setCornerA(new Vec3Point(-25,62.5,-127));pit.setCornerB(new Vec3Point(25,78,-77));
        Vec3Point roof=new Vec3Point(-20.5,67.5,-100.5),wall=new Vec3Point(-21.5,66.5,-106.5);
        expect(EditorMomentumJump.find(geometry,pit,roof,wall)!=null,"reported lower-roof to wall connection has a checked chain");
        expect(!new GroundRoute(geometry,pit,pit,null,63,true).edgeClear(roof,wall),"ordinary routing still rejects this as a single jump");
        expect(EditorMomentumJump.find(new CollisionGeometry((x,y,z)->CollisionGeometry.Cell.UNLOADED),pit,roof,wall)==null,"unloaded terrain cannot produce a chain");
        PitRegion low=new PitRegion();low.setCornerA(pit.cornerA());low.setCornerB(new Vec3Point(25,70,-77));
        expect(EditorMomentumJump.find(geometry,low,roof,wall)==null,"the complete first and second jump need headroom inside the region");
        expect(EditorMomentumJump.find(geometry,pit,roof,new Vec3Point(wall.x,roof.y,wall.z))==null,"a flat five-block gap cannot inherit the downhill chain");
        double[][] reported={{21.5,65,-111.5,23.5,66,-107.5},{2.5,69,-106.5,4.5,70,-102.5},{4.5,70,-102.5,2.5,69,-106.5}};
        for(double[] pair:reported) {
            Vec3Point from=new Vec3Point(pair[0],pair[1],pair[2]),to=new Vec3Point(pair[3],pair[4],pair[5]);
            expect(EditorPrecisionJump.find(geometry,pit,from,to)!=null,"reported corner jump gets a checked precision plan: "+from.serialize()+">"+to.serialize());
        }
        expect(EditorPrecisionJump.find(geometry,pit,new Vec3Point(21.5,65,-111.5),new Vec3Point(23.5,67,-107.5))==null,"a two-block rise cannot become a precision jump");
        expect(EditorPrecisionJump.find(geometry,pit,roof,wall)==null,"the five-empty-block wall crossing does not become a precision single jump");
        Unsafe unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        Class<?> pt=Class.forName("dev.optimus.client.CapturedNavigationSelfTest$Player"),wt=Class.forName("dev.optimus.client.CapturedNavigationSelfTest$World");
        Field singleton=field(Minecraft.class,"theMinecraft");Object old=singleton.get(null);int cases=0,precisionCases=0;
        Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);singleton.set(null,mc);
        try {
            mc.gameSettings=new GameSettings();WorldClient world=(WorldClient)unsafe.allocateInstance(wt);mc.theWorld=world;
            field(wt,"theProfiler").set(world,new Profiler());method(wt,"load",String.class).invoke(world,"duels-pit-2026-10-15.tsv.gz");
            EntityPlayerSP player=(EntityPlayerSP)unsafe.allocateInstance(pt);mc.thePlayer=player;player.worldObj=world;
            field(pt,"foodStats").set(player,new FoodStats());player.capabilities=new PlayerCapabilities();
            Method position=method(CapturedNavigationSelfTest.class,"position",pt,Vec3Point.class,float.class),physics=method(pt,"physics",GameSettings.class);
            for(JsonElement item:chains) {
                JsonObject chain=item.getAsJsonObject();Vec3Point from=point(nodes.get(chain.get("from").getAsInt())),goal=point(nodes.get(chain.get("to").getAsInt()));
                Vec3Point start=point(chain.getAsJsonArray("points").get(1));float yaw=chain.get("yaw").getAsFloat();
                for(double x:new double[]{-.02,0,.02}) for(double z:new double[]{-.02,0,.02}) for(float turn:new float[]{-1,0,1}) {
                    Vec3Point trial=new Vec3Point(start.x+x,start.y,start.z+z);
                    List<Vec3Point> predicted=EditorMomentumJump.samples(geometry,pit,trial,yaw+turn,goal);
                    expect(!predicted.isEmpty() && geometry.autoStepClear(from,trial,pit),"setup and entire two-hop prediction remain checked");
                    position.invoke(null,player,trial,yaw+turn);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(),true);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(),true);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(),true);
                    for(int tick=1;tick<predicted.size();tick++) {
                        physics.invoke(player,mc.gameSettings);Vec3Point p=predicted.get(tick);
                        expect(Math.abs(player.posY-p.y)<1e-5 && Math.hypot(player.posX-p.x,player.posZ-p.z)<1e-5,
                            "native input matches chain predictor at edge "+chain.get("from")+">"+chain.get("to")+" tick "+tick);
                    }
                    expect(player.onGround && (Integer)field(pt,"jumps").get(player)==2 && Math.abs(player.posY-goal.y)<.01,
                        "two real jumps land on the requested platform");
                    expect(geometry.autoStepClear(new Vec3Point(player.posX,player.posY,player.posZ),goal,pit),"native landing reconnects to supported goal");cases++;
                }
                expect(EditorMomentumJump.find(geometry,pit,goal,from)==null,"chain cannot authorize the uphill reverse jump");
                expect(!SprintGap.candidate(from,goal),"five-empty-block transitions do not become single jumps");
            }
            for(JsonElement item:precision) {
                JsonObject jump=item.getAsJsonObject();Vec3Point from=point(nodes.get(jump.get("from").getAsInt())),goal=point(nodes.get(jump.get("to").getAsInt()));
                Vec3Point start=point(jump.getAsJsonArray("points").get(2)),runUp=point(jump.get("runUp"));float yaw=jump.get("yaw").getAsFloat();
                for(double x:new double[]{-.01,0,.01}) for(double z:new double[]{-.01,0,.01})
                    for(float turn:new float[]{-.5f,0,.5f}) for(double speed:new double[]{.149,.15,.151}) {
                        Vec3Point trial=new Vec3Point(start.x+x,start.y,start.z+z);
                        List<Vec3Point> predicted=EditorPrecisionJump.samples(geometry,pit,trial,yaw+turn,speed,goal);
                        expect(!predicted.isEmpty() && geometry.autoStepClear(from,runUp,pit) && geometry.autoStepClear(runUp,trial,pit),"precision setup stays on checked supported ground");
                        position.invoke(null,player,trial,yaw+turn);float angle=(yaw+turn)*.017453292f;
                        player.motionX=-(double)net.minecraft.util.MathHelper.sin(angle)*speed;player.motionZ=(double)net.minecraft.util.MathHelper.cos(angle)*speed;
                        field(pt,"sprint").set(player,true);player.jumpMovementFactor=.026f;
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(),true);
                        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(),true);
                        for(int tick=1;tick<predicted.size();tick++) {
                            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(),tick==2);
                            physics.invoke(player,mc.gameSettings);Vec3Point p=predicted.get(tick);
                            expect(Math.abs(player.posY-p.y)<1e-5 && Math.hypot(player.posX-p.x,player.posZ-p.z)<1e-5,
                                "native precision input matches predictor at "+jump.get("from")+">"+jump.get("to")+" tick "+tick);
                            if(tick==1) expect(player.onGround,"the actual final step retains native grounded state before jump input");
                        }
                        expect(player.onGround && (Integer)field(pt,"jumps").get(player)==1 && Math.abs(player.posY-goal.y)<.01,
                            "one real precision jump lands at the correct elevation");
                        expect(geometry.autoStepClear(new Vec3Point(player.posX,player.posY,player.posZ),goal,pit),"precision landing reconnects to the saved area");precisionCases++;
                    }
            }
            System.out.println("PASS: "+checks+" editor advanced-jump checks across "+cases+" native chain replays and "+precisionCases+" native precision replays");
        } finally {
            if(mc.gameSettings!=null) for(KeyBinding k:new KeyBinding[]{mc.gameSettings.keyBindForward,mc.gameSettings.keyBindSprint,mc.gameSettings.keyBindJump}) KeyBinding.setKeyBindState(k.getKeyCode(),false);
            singleton.set(null,old);
        }
    }
}