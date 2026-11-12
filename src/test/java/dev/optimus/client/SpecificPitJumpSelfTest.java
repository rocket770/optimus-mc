package dev.optimus.client;

import dev.optimus.core.CollisionReplaySelfTest;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.*;
import net.minecraft.entity.player.*;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.*;
import net.minecraft.network.*;
import io.netty.channel.embedded.EmbeddedChannel;
import sun.misc.Unsafe;

/** Actual WASD/camera adapter and native physics from approach, through wall alignment and both jumps. */
public final class SpecificPitJumpSelfTest {
    private static int checks;
    private static void expect(boolean b,String why) { checks++;if(!b) throw new AssertionError(why); }
    private static Field field(Class<?> t,String n)throws Exception {
        for(Class<?> c=t;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(n);
    }
    private static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private static void set(Object o,String n,Object v)throws Exception { field(o.getClass(),n).set(o,v); }
    private static Method method(Class<?> t,String n,Class<?>... args)throws Exception { Method m=t.getDeclaredMethod(n,args);m.setAccessible(true);return m; }
    private static Vec3Point point(EntityPlayerSP p) { return new Vec3Point(p.posX,p.posY,p.posZ); }
    public static void main(String[] args)throws Exception {
        Unsafe unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        Class<?> pt=Class.forName("dev.optimus.client.CapturedNavigationSelfTest$Player"),wt=Class.forName("dev.optimus.client.CapturedNavigationSelfTest$World"),ct=Class.forName("dev.optimus.client.CapturedNavigationSelfTest$Controller");
        Field singleton=field(Minecraft.class,"theMinecraft");Object old=singleton.get(null);ClientSettings previous=OptimusMod.settings;
        RouteHints previousHints=OptimusMod.routeHints;boolean previousEnabled=OptimusMod.routeHintsEnabled;
        EmbeddedChannel channel=null;CombatTrace trace=null;Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);singleton.set(null,mc);
        try {
            mc.gameSettings=new GameSettings();WorldClient world=(WorldClient)unsafe.allocateInstance(wt);mc.theWorld=world;
            field(wt,"theProfiler").set(world,new Profiler());method(wt,"load",String.class).invoke(world,"duels-pit-2026-10-15.tsv.gz");
            EntityPlayerSP player=(EntityPlayerSP)unsafe.allocateInstance(pt);mc.thePlayer=player;player.worldObj=world;
            set(player,"foodStats",new FoodStats());player.capabilities=new PlayerCapabilities();player.inventory=new InventoryPlayer(player);
            net.minecraft.entity.DataWatcher watcher=new net.minecraft.entity.DataWatcher(player);watcher.addObject(6,Float.valueOf(20));set(player,"dataWatcher",watcher);
            mc.playerController=(PlayerControllerMP)unsafe.allocateInstance(ct);set(mc,"currentServerData",new ServerData("Offline fixture","mc.hypixel.net",false));
            NetworkManager manager=new NetworkManager(EnumPacketDirection.CLIENTBOUND);NetHandlerPlayClient handler=(NetHandlerPlayClient)unsafe.allocateInstance(NetHandlerPlayClient.class);
            set(handler,"netManager",manager);set(player,"sendQueue",handler);
            OptimusMod.settings=new ClientSettings(Files.createTempDirectory("specific-pit-jump").toFile());ClientSettings settings=OptimusMod.settings;
            settings.applySuppliedArena("duelslobby9","dynamiclobbyTEST");PitRegion pit=settings.hypixelPit;
            CollisionGeometry geometry=CollisionReplaySelfTest.capture("duels-pit-2026-10-15.tsv.gz").cached();
            expect(SpecificPitJump.available(geometry,pit),"captured corner crossing has a complete checked two-hop plan");
            expect(!SpecificPitJump.available(new CollisionGeometry((x,y,z)->CollisionGeometry.Cell.UNLOADED),pit),"unloaded geometry rejects the specific jump");
            CollisionGeometry.Source source=(CollisionGeometry.Source)field(CollisionGeometry.class,"source").get(geometry);
            CollisionGeometry blocked=new CollisionGeometry((x,y,z)->x==0&&y==69&&z==-121?new CollisionGeometry.Cell(true,true,Collections.emptyList()):source.cell(x,y,z));
            expect(!SpecificPitJump.available(blocked,pit),"changed flight corridor rejects the jump");
            expect(SpecificPitJump.project(geometry,pit,SpecificPitJump.SETUP,.02,-.0784,0,true,0).isEmpty(),"moving lineup cannot be used as a rest launch");
            RouteHints hints=RouteHints.load(Files.createTempDirectory("specific-route-hints").toFile());
            expect(hints.jump(RouteHints.node(SpecificPitJump.FROM),RouteHints.node(SpecificPitJump.TO)).kind==SpecificPitJump.KIND,"known map exposes one directed optional built-in edge");
            expect(hints.jump(RouteHints.node(SpecificPitJump.TO),RouteHints.node(SpecificPitJump.FROM))==null,"this routine does not authorize an unproved reverse jump");
            GroundRoute ground=new GroundRoute(geometry,pit,pit,null,63,true);
            expect(hints.augment(ground,geometry,pit,true,k->false).traversable(RouteHints.node(SpecificPitJump.FROM),RouteHints.node(SpecificPitJump.TO)),"mixed planner can choose the specific edge");
            expect(!hints.augment(ground,geometry,pit,false,k->false).traversable(RouteHints.node(SpecificPitJump.FROM),RouteHints.node(SpecificPitJump.TO)),"unsupported jump physics cannot use the edge");
            Method position=method(CapturedNavigationSelfTest.class,"position",pt,Vec3Point.class,float.class),physics=method(pt,"physics",GameSettings.class);
            ClientEvents events=new ClientEvents();LobbySafety safety=(LobbySafety)field(ClientEvents.class,"lobbySafety").get(events);
            TransitionWatch watch=new TransitionWatch(safety,manager);channel=new EmbeddedChannel(watch);set(manager,"channel",channel);
            set(events,"transitionWatch",watch);set(events,"sessionWorld",world);set(events,"sessionPlayer",player);set(events,"armed",true);
            RunSafety run=(RunSafety)field(ClientEvents.class,"run").get(events);run.startChecking(0);expect(run.activate("duelslobby9","dynamiclobbyTEST",world),"native run activates");
            LobbyNavigator navigator=(LobbyNavigator)field(ClientEvents.class,"navigator").get(events);set(navigator,"geometry",geometry);
            Method adapter=method(ClientEvents.class,"navigateStep",Vec3Point.class,PitRegion.class,LobbyNavigator.Step.class);
            int scenarios=0;
            for(Vec3Point start:Arrays.asList(SpecificPitJump.FROM,new Vec3Point(5.3,68,-118.6),new Vec3Point(5.65,68,-118.45),new Vec3Point(5.48,68,-118.30000001192093)))
            for(float facing:new float[]{0,90,180,270}) {
                scenarios++;position.invoke(null,player,start,facing);navigator.reset();
                set(navigator,"path",Arrays.asList(RouteHints.node(SpecificPitJump.FROM),RouteHints.node(SpecificPitJump.TO)));set(navigator,"index",1);
                set(navigator,"plannedTerrain",ground);set(navigator,"plannedGoal",SpecificPitJump.TO);set(navigator,"pathHints",hints);set(navigator,"hintedPath",true);
                set(get(navigator,"advanced"),"hintJump",hints.jump(RouteHints.node(SpecificPitJump.FROM),RouteHints.node(SpecificPitJump.TO)));set(get(navigator,"advanced"),"hintSetupAt",0L);
                boolean landed=false,committed=false;
                for(int tick=1;tick<=180;tick++) {
                    long now=System.nanoTime()/1000000;safety.request("mc.hypixel.net",world,now);safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false);
                    set(events,"tickNo",(long)tick);LobbyNavigator.Step step=navigator.step(SpecificPitJump.TO,pit,true,tick);
                    expect(step.status!=LobbyNavigator.Status.FAILED,"specific jump approach remains valid: "+navigator.activity+" at "+point(player).serialize());
                    adapter.invoke(events,SpecificPitJump.TO,pit,step);committed|=navigator.jumpInProgress();physics.invoke(player,mc.gameSettings);
                    if(committed && !navigator.jumpInProgress()) { landed=player.onGround&&Math.abs(player.posY-68.5)<.001&&geometry.autoStepClear(point(player),SpecificPitJump.TO,pit);break; }
                }
                expect(landed && field(pt,"jumps").getInt(player)==2,"actual wall lineup and two jumps reach the top: start="+start.serialize()+" yaw="+facing+" last="+navigator.activity+" at="+point(player).serialize());
            }
            OptimusMod.routeHints=hints;OptimusMod.routeHintsEnabled=true;
            Method release=method(ClientEvents.class,"releaseKeys");
            for(Vec3Point start:Arrays.asList(SpecificPitJump.FROM,new Vec3Point(4.5,68,-118.6)))for(float facing:new float[]{0,90,180,270}) {
                scenarios++;release.invoke(events);position.invoke(null,player,start,facing);navigator.reset();boolean landed=false;
                for(int tick=1;tick<=350;tick++) {
                    long now=System.nanoTime()/1000000;safety.request("mc.hypixel.net",world,now);safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false);
                    set(events,"tickNo",(long)tick);LobbyNavigator.Step step=navigator.step(SpecificPitJump.TO,pit,true,tick);
                    expect(step.status!=LobbyNavigator.Status.FAILED,"live route reaches specific jump: "+navigator.activity+" at "+point(player).serialize());
                    adapter.invoke(events,SpecificPitJump.TO,pit,step);physics.invoke(player,mc.gameSettings);
                    if(player.onGround&&Math.abs(player.posY-68.5)<.001&&!navigator.jumpInProgress()) { landed=geometry.autoStepClear(point(player),SpecificPitJump.TO,pit);break; }
                }
                expect(landed && field(pt,"jumps").getInt(player)==2,"fresh mixed planner selects and executes corner crossing: "+navigator.activity+" at "+point(player).serialize());
            }
            EntityPlayerSP rival=(EntityPlayerSP)unsafe.allocateInstance(pt);rival.worldObj=world;set(rival,"label","OfflineRival");
            net.minecraft.entity.DataWatcher rivalHealth=new net.minecraft.entity.DataWatcher(rival);rivalHealth.addObject(6,Float.valueOf(20));set(rival,"dataWatcher",rivalHealth);
            set(world,"playerEntities",new ArrayList<EntityPlayer>(Arrays.asList(player,rival)));settings.whitelist.add("OfflineRival");
            player.inventory.mainInventory[0]=new net.minecraft.item.ItemStack(new net.minecraft.item.ItemSword(net.minecraft.item.Item.ToolMaterial.IRON));
            player.inventory.currentItem=0;trace=new CombatTrace(settings.traceDirectory());set(events,"trace",trace);
            Method fight=method(CombatController.class,"fight",PitRegion.class,Vec3Point.class);
            for(float facing:new float[]{0,90,180,270}) {
                scenarios++;release.invoke(events);navigator.reset();set(get(events,"combat"),"turningTarget",null);
                position.invoke(null,player,SpecificPitJump.FROM,facing);position.invoke(null,rival,SpecificPitJump.TO,0f);
                int before=field(ct,"attacks").getInt(mc.playerController);boolean attacked=false;
                for(int tick=1;tick<=450;tick++) {
                    long now=System.nanoTime()/1000000;safety.request("mc.hypixel.net",world,now);safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false);
                    set(events,"tickNo",(long)tick);fight.invoke(get(events,"combat"),pit,point(player));physics.invoke(player,mc.gameSettings);
                    if(player.onGround&&Math.abs(player.posY-68.5)<.001&&field(ct,"attacks").getInt(mc.playerController)>before) { attacked=true;break; }
                }
                expect(attacked && field(pt,"jumps").getInt(player)==2,"combat chase preserves the lineup/arc and attacks on top: "+navigator.activity+" at "+point(player).serialize());
            }
            System.out.println("PASS: "+checks+" specific corner-jump checks across "+scenarios+" native approaches/headings");
        } finally { if(trace!=null)trace.close();if(channel!=null)channel.finish();singleton.set(null,old);OptimusMod.settings=previous;OptimusMod.routeHints=previousHints;OptimusMod.routeHintsEnabled=previousEnabled; }
    }
}