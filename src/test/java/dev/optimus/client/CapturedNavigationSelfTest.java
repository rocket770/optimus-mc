package dev.optimus.client;

import dev.optimus.core.CollisionReplaySelfTest;
import dev.optimus.core.combat.CombatFocus;
import dev.optimus.core.combat.CombatHop;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.SprintGap;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.*;
import net.minecraft.potion.Potion;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.*;
import net.minecraft.world.EnumDifficulty;
import sun.misc.Unsafe;

/** Closed-loop replay: real navigator -> real key handler -> native movement/collisions.
 * No window, sockets, live client, or teleporting along the planner's predicted arc.
 */
public final class CapturedNavigationSelfTest {
    private static Field field(Class<?> type,String name) throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) try {
            Field f=c.getDeclaredField(name);f.setAccessible(true);return f;
        } catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object o,String n) throws Exception { return field(o.getClass(),n).get(o); }
    private static void set(Object o,String n,Object v) throws Exception { field(o.getClass(),n).set(o,v); }
    private static final class Player extends EntityPlayerSP {
        String label="OfflineBot";
        boolean sprint;int jumpCooldown,jumps;
        private Player() { super(null,null,null,null); }
        @Override public boolean isEntityAlive() { return true; }
        @Override public String getName() { return label; }
        @Override public void swingItem() { }
        @Override public void addChatMessage(IChatComponent message) { }
        @Override public boolean isPotionActive(Potion potion) { return false; }
        @Override public boolean isSprinting() { return sprint; }
        @Override public void setSprinting(boolean value) { sprint=value; }
        @Override public float getAIMoveSpeed() { return sprint?.13f:.1f; }
        @Override public boolean isServerWorld() { return true; }
        @Override public boolean isInWater() { return false; }
        @Override public boolean isInLava() { return false; }
        @Override public boolean isOnLadder() { return false; }
        @Override public boolean isSneaking() { return Minecraft.getMinecraft().gameSettings.keyBindSneak.isKeyDown(); }
        @Override public boolean isWet() { return false; }
        @Override public void addMovementStat(double x,double y,double z) { }
        @Override protected boolean canTriggerWalking() { return false; }
        @Override protected void doBlockCollisions() { }
        @Override protected void updateFallState(double y,boolean ground,Block b,BlockPos p) { }
        void physics(GameSettings keys) {
            // EntityLivingBase.onLivingUpdate input damping, deadzone and jump cooldown.
            if(jumpCooldown>0) jumpCooldown--;
            if(Math.abs(motionX)<.005) motionX=0;
            if(Math.abs(motionY)<.005) motionY=0;
            if(Math.abs(motionZ)<.005) motionZ=0;
            float forward=(keys.keyBindForward.isKeyDown()?1:0)-(keys.keyBindBack.isKeyDown()?1:0);
            float side=(keys.keyBindLeft.isKeyDown()?1:0)-(keys.keyBindRight.isKeyDown()?1:0);
            if(keys.keyBindSneak.isKeyDown()) { forward*=.3f;side*=.3f; }
            if(forward>=.8f && keys.keyBindSprint.isKeyDown() && getFoodStats().getFoodLevel()>6) sprint=true;
            if(forward<.8f || isCollidedHorizontally) sprint=false;
            if(!keys.keyBindJump.isKeyDown()) jumpCooldown=0;
            else if(onGround && jumpCooldown==0) {
                // Vanilla jump impulse, omitting Forge events/statistics that need a booted game.
                motionY=(double).42f;
                if(sprint) { float yaw=rotationYaw*.017453292f;motionX-=MathHelper.sin(yaw)*.2f;motionZ+=MathHelper.cos(yaw)*.2f; }
                jumpCooldown=10;jumps++;
            }
            prevPosX=posX;prevPosY=posY;prevPosZ=posZ;
            // Calls vanilla moveFlying AND moveEntity, including its two step-up attempts.
            moveEntityWithHeading(side*.98f,forward*.98f);
            // EntityPlayer refreshes this after movement; do not update it before takeoff.
            jumpMovementFactor=sprint?.026f:.02f;
        }
    }
    private static final class Controller extends PlayerControllerMP {
        int attacks;
        private Controller() { super(null,null); }
        @Override public void updateController() { }
        @Override public void attackEntity(EntityPlayer player,Entity target) { attacks++;player.setSprinting(false); }
    }
    private static final class FloorBlock extends Block { private FloorBlock() { super(Material.rock); } }
    private static final class World extends WorldClient {
        private static final IBlockState FLOOR=new FloorBlock().getDefaultState();
        Map<String,List<AxisAlignedBB>> cells;
        boolean combatRays;
        private World() { super(null,null,0,EnumDifficulty.NORMAL,null); }
        @Override public boolean isBlockLoaded(BlockPos p) { return true; }
        @Override public IBlockState getBlockState(BlockPos p) { return FLOOR; }
        @Override public boolean isFlammableWithin(AxisAlignedBB b) { return false; }
        @Override public MovingObjectPosition rayTraceBlocks(Vec3 a,Vec3 b) {
            if(!combatRays) return null;
            MovingObjectPosition nearest=null;double best=Double.POSITIVE_INFINITY;
            AxisAlignedBB query=new AxisAlignedBB(Math.min(a.xCoord,b.xCoord),Math.min(a.yCoord,b.yCoord),Math.min(a.zCoord,b.zCoord),
                Math.max(a.xCoord,b.xCoord),Math.max(a.yCoord,b.yCoord),Math.max(a.zCoord,b.zCoord));
            for(AxisAlignedBB box:getCollidingBoundingBoxes(null,query)) {
                MovingObjectPosition hit=box.calculateIntercept(a,b);
                if(hit==null) continue;
                double x=hit.hitVec.xCoord-a.xCoord,y=hit.hitVec.yCoord-a.yCoord,z=hit.hitVec.zCoord-a.zCoord,d=x*x+y*y+z*z;
                if(d<best) { best=d;nearest=hit; }
            }
            return nearest;
        }
        @Override public List<AxisAlignedBB> getCollidingBoundingBoxes(Entity e,AxisAlignedBB b) {
            List<AxisAlignedBB> result=new ArrayList<>();
            for(int x=MathHelper.floor_double(b.minX);x<=MathHelper.floor_double(b.maxX);x++)
                for(int y=MathHelper.floor_double(b.minY)-1;y<=MathHelper.floor_double(b.maxY);y++)
                    for(int z=MathHelper.floor_double(b.minZ);z<=MathHelper.floor_double(b.maxZ);z++) {
                        List<AxisAlignedBB> boxes=cells.get(x+":"+y+":"+z);
                        if(boxes!=null) for(AxisAlignedBB box:boxes) if(box.intersectsWith(b)) result.add(box);
                    }
            return result;
        }
        void load() throws IOException {
            load("duels-collision.tsv.gz");
        }
        void load(String resource) throws IOException {
            cells=new HashMap<>();
            try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(
                    CollisionReplaySelfTest.class.getResourceAsStream(resource)),StandardCharsets.UTF_8))) {
                String line;while((line=in.readLine())!=null) if(line.startsWith("B ")) {
                    String[] p=line.split(" ");
                    cells.computeIfAbsent(p[1]+":"+p[2]+":"+p[3],k->new ArrayList<>()).add(new AxisAlignedBB(
                        Double.parseDouble(p[5]),Double.parseDouble(p[6]),Double.parseDouble(p[7]),
                        Double.parseDouble(p[8]),Double.parseDouble(p[9]),Double.parseDouble(p[10])));
                }
            }
        }
        CollisionGeometry platforms(double startY,double landingY,int gap,int diagonal) {
            cells=new HashMap<>();
            for(int x=-5;x<=8;x++) for(int z=-104;z<=-96;z++) {
                add(x,62,z,x,62,z,x+1,63,z+1);
                if(x<=0 && (diagonal==0 || diagonal==1 && z<=-100 || diagonal==2 && z==-101))
                    add(x,(int)Math.floor(startY-.01),z,x,Math.floor(startY-.01),z,x+1,startY,z+1);
                if(x>=gap+1 && (diagonal==0 || diagonal==1 && z>=-99 || diagonal==2 && z==-101))
                    add(x,(int)Math.floor(landingY-.01),z,x,Math.floor(landingY-.01),z,x+1,landingY,z+1);
            }
            return new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> result=new ArrayList<>();
                List<AxisAlignedBB> boxes=cells.get(x+":"+y+":"+z);
                if(boxes!=null) for(AxisAlignedBB b:boxes) result.add(new CollisionGeometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
                return new CollisionGeometry.Cell(true,false,result);
            });
        }
        void add(int x,int y,int z,double a,double b,double c,double d,double e,double f) {
            cells.computeIfAbsent(x+":"+y+":"+z,k->new ArrayList<>()).add(new AxisAlignedBB(a,b,c,d,e,f));
        }
        CollisionGeometry stairs(int dx,int dz) {
            cells=new HashMap<>();combatRays=false;
            for(int x=-10;x<=10;x++) for(int z=-115;z<=-94;z++) {
                add(x,62,z,x,62,z,x+1,63,z+1);
                int along=x*dx+(z+105)*dz,cross=dx!=0?z+105:x;
                if(Math.abs(cross)>2) continue;
                if(along>=1 && along<=4) {
                    int y=62+along;add(x,y,z,x,y,z,x+1,y+.5,z+1);
                    add(x,y,z,x+(dx>0?.5:0),y+.5,z+(dz>0?.5:0),x+1-(dx<0?.5:0),y+1,z+1-(dz<0?.5:0));
                } else if(along>=5) add(x,66,z,x,66,z,x+1,67,z+1);
            }
            return new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> result=new ArrayList<>();List<AxisAlignedBB> boxes=cells.get(x+":"+y+":"+z);
                if(boxes!=null) for(AxisAlignedBB b:boxes) result.add(new CollisionGeometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
                return new CollisionGeometry.Cell(true,false,result);
            });
        }
        CollisionGeometry hopArena(int scenario) {
            cells=new HashMap<>();combatRays=true;
            for(int x=-5;x<=12;x++) for(int z=-106;z<=-94;z++) add(x,62,z,x,62,z,x+1,63,z+1);
            double roof=scenario==4?65:66.5;
            for(int x=2;x<=7;x++) for(int z=-102;z<=-99;z++)
                add(x,(int)Math.floor(roof-1),z,x,roof-1,z,x+1,roof,z+1);
            for(int x=1;x<=3;x++) add(x,62+x,-98,x,62+x,-98,x+1,63+x,-97);
            if(scenario==7) add(0,65,-101,0,65,-101,1,66,-100);
            return new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> result=new ArrayList<>();List<AxisAlignedBB> boxes=cells.get(x+":"+y+":"+z);
                if(boxes!=null) for(AxisAlignedBB b:boxes) result.add(new CollisionGeometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
                return new CollisionGeometry.Cell(true,false,result);
            });
        }
    }
    private static Vec3Point point(Player p) { return new Vec3Point(p.posX,p.posY,p.posZ); }
    private static void position(Player p,Vec3Point at,float yaw) {
        p.width=.6f;p.height=1.8f;p.stepHeight=.6f;p.setPosition(at.x,at.y,at.z);
        p.onGround=true;p.motionX=p.motionZ=0;p.motionY=-.0784;p.rotationYaw=yaw;
        p.sprint=false;p.jumpMovementFactor=.02f;p.jumpCooldown=p.jumps=0;
        p.isCollidedHorizontally=p.isCollidedVertically=p.isCollided=false;
    }
    private static void combatHopReplays(ClientEvents events,Player rival,List<String> rows,List<String> summary) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();World world=(World)mc.theWorld;Player player=(Player)mc.thePlayer;
        PitRegion pit=OptimusMod.settings.hypixelPit;LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");
        LobbySafety safety=(LobbySafety)get(events,"lobbySafety");Controller controller=(Controller)mc.playerController;
        Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
        Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
        List<String> failures=new ArrayList<>();
        for(int scenario=0;scenario<9;scenario++) {
            set(nav,"geometry",world.hopArena(scenario));nav.reset();release.invoke(events);
            ((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset();((CombatHop)get(get(events,"combat"),"combatHop")).seed(42+scenario);
            position(player,new Vec3Point(.5,63,-100.5),scenario<4?scenario*90:0);
            player.getDataWatcher().updateObject(6,Float.valueOf(20));player.hurtTime=0;
            position(rival,new Vec3Point(scenario==5?6.5:2.9,scenario==4?65:66.5,-100.5),0);
            String name="elevated_jump_attack_"+scenario;int before=controller.attacks,hopLaunches=0,elapsed=0;
            boolean arrived=false,airHit=false,routeAfterHop=false,movedTarget=false,damageCanceled=false,extendedSearch=false;
            for(int tick=1;tick<=500;tick++) {
                elapsed=tick;long now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",world,now);
                if(!safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("hop fixture verification");
                set(events,"tickNo",(long)scenario*600+tick);fight.invoke(get(events,"combat"),pit,point(player));
                CombatHop hop=(CombatHop)get(get(events,"combat"),"combatHop");
                if(scenario==8 && player.hurtTime>0 && !hop.active() && !mc.gameSettings.keyBindJump.isKeyDown()) damageCanceled=true;
                if(player.onGround && hop.launched() && mc.gameSettings.keyBindJump.isKeyDown()) hopLaunches++;
                if(!player.onGround && controller.attacks>before && hop.active()) airHit=true;
                if(scenario==6 && movedTarget && !hop.active()
                        && (get(nav,"search")!=null || !((List<?>)get(nav,"path")).isEmpty())) routeAfterHop=true;
                if(get(nav,"search")!=null && ((Integer)get(get(nav,"search"),"budget"))==131072) extendedSearch=true;
                rows.add(name+","+tick+","+player.posX+","+player.posY+","+player.posZ+","+player.rotationYaw+","+player.onGround+","
                    +mc.gameSettings.keyBindForward.isKeyDown()+","+mc.gameSettings.keyBindBack.isKeyDown()+","+mc.gameSettings.keyBindJump.isKeyDown()+","+player.sprint+","+nav.activity+","
                    +mc.gameSettings.keyBindLeft.isKeyDown()+","+mc.gameSettings.keyBindRight.isKeyDown()+",,");
                if(scenario<4?airHit && player.onGround:scenario==8?damageCanceled && controller.attacks>before:controller.attacks>before) { arrived=true;break; }
                player.physics(mc.gameSettings);
                if(scenario==6 && hopLaunches==1 && !movedTarget) {
                    position(rival,new Vec3Point(6.5,66.5,-100.5),0);movedTarget=true;
                }
                if(scenario==8 && hopLaunches==1 && player.hurtTime==0 && !damageCanceled) {
                    player.getDataWatcher().updateObject(6,Float.valueOf(19));player.hurtTime=10;
                } else if(player.hurtTime>0) player.hurtTime--;
                if(!pit.containsBody(point(player),.3,1.8)) break;
            }
            boolean valid=arrived && (scenario<4?hopLaunches==1 && player.jumps==1:scenario==6?hopLaunches==1 && routeAfterHop && extendedSearch:scenario==8?hopLaunches==1 && damageCanceled:hopLaunches==0);
            if(!valid) failures.add(name);
            System.out.println(name+": "+(valid?"PASS":"FAILED")+" hops="+hopLaunches+" jumps="+player.jumps+" attacks="+(controller.attacks-before)
                +" airborneHit="+airHit+" fallback="+routeAfterHop+" extendedSearch="+extendedSearch+" damageCanceled="+damageCanceled+" ticks="+elapsed+" end="+point(player).serialize()+" last="+nav.activity);
            summary.add(name+",.5,63,-100.5,"+rival.posX+","+rival.posY+","+rival.posZ+","+(valid?"ARRIVED":"FAILED")+","+elapsed+","+player.jumps+",0,0,0");
        }
        if(!failures.isEmpty()) throw new AssertionError("Native elevated attack/fallback scenarios failed: "+failures);
        System.out.println("PASS: 9 native elevated jump attacks, grounded melee, blocked headroom, damage and route fallback scenarios");
    }
    private static void stairReplays(ClientEvents events,Player rival,List<String> rows,List<String> summary) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();World world=(World)mc.theWorld;Player player=(Player)mc.thePlayer;
        PitRegion pit=OptimusMod.settings.hypixelPit;LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");
        LobbySafety safety=(LobbySafety)get(events,"lobbySafety");Controller controller=(Controller)mc.playerController;
        Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
        Method apply=ClientEvents.class.getDeclaredMethod("navigateStep",Vec3Point.class,PitRegion.class,LobbyNavigator.Step.class);apply.setAccessible(true);
        Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
        List<String> failures=new ArrayList<>();int scenario=0;
        for(boolean descending:new boolean[]{false,true})
        for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) for(int heading=0;heading<4;heading++) {
            Vec3Point low=new Vec3Point(.5,63,-104.5),high=new Vec3Point(.5+direction[0]*7,67,-104.5+direction[1]*7);
            Vec3Point start=descending?high:low,goal=descending?low:high;
            set(nav,"geometry",world.stairs(direction[0],direction[1]));nav.reset();release.invoke(events);
            ((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset();position(player,start,heading*90);
            if(rival!=null) position(rival,goal,0);
            String name=(descending?"stair_descent_":"stair_ascent_")+(scenario++);int before=controller.attacks,ascentSprint=0,elapsed=0;boolean arrived=false;
            for(int tick=1;tick<=180;tick++) {
                elapsed=tick;long now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",world,now);
                if(!safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("stair fixture verification");
                set(events,"tickNo",(long)scenario*200+tick);
                if(rival!=null) { fight.invoke(get(events,"combat"),pit,point(player));arrived=controller.attacks>before; }
                else arrived=apply.invoke(events,goal,pit,nav.step(goal,pit,false,tick))==LobbyNavigator.Status.ARRIVED;
                if(player.posY>63.1 && player.posY<67 && player.sprint && mc.gameSettings.keyBindForward.isKeyDown()) ascentSprint++;
                rows.add(name+","+tick+","+player.posX+","+player.posY+","+player.posZ+","+player.rotationYaw+","+player.onGround+","
                    +mc.gameSettings.keyBindForward.isKeyDown()+","+mc.gameSettings.keyBindBack.isKeyDown()+","+mc.gameSettings.keyBindJump.isKeyDown()+","+player.sprint+","+nav.activity+","
                    +mc.gameSettings.keyBindLeft.isKeyDown()+","+mc.gameSettings.keyBindRight.isKeyDown()+",,");
                if(arrived) break;
                player.physics(mc.gameSettings);
                if(!pit.containsBody(point(player),.3,1.8)) break;
            }
            // A backwards initial view needs time to turn before W can sprint;
            // it must still sustain forward sprint during the remaining ascent.
            boolean valid=arrived && player.jumps==0
                    && ascentSprint>=(descending && rival!=null?1:6)
                    && (!descending || rival!=null || elapsed<=40);
            if(!valid) failures.add(name);
            System.out.println(name+": "+(valid?"PASS":"FAILED")+" ascentSprint="+ascentSprint+" jumps="+player.jumps+" ticks="+elapsed+" end="+point(player).serialize());
            summary.add(name+","+start.x+","+start.y+","+start.z+","+goal.x+","+goal.y+","+goal.z+","+(valid?"ARRIVED":"FAILED")+","+elapsed+","+player.jumps+","+ascentSprint+",0,0");
        }
        if(!failures.isEmpty()) throw new AssertionError("Native stair sprint scenarios failed: "+failures);
        System.out.println("PASS: native ascent/descent across four directions and four camera headings");
    }
    private static void hintReplays(ClientEvents events,Player rival,List<String> rows,List<String> summary) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();Player player=(Player)mc.thePlayer;World world=(World)mc.theWorld;
        LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");PitRegion pit=OptimusMod.settings.hypixelPit;
        LobbySafety safety=(LobbySafety)get(events,"lobbySafety");Controller controller=(Controller)mc.playerController;
        Method apply=ClientEvents.class.getDeclaredMethod("navigateStep",Vec3Point.class,PitRegion.class,LobbyNavigator.Step.class);apply.setAccessible(true);
        Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
        Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
        RouteHints previous=OptimusMod.routeHints;boolean previousEnabled=OptimusMod.routeHintsEnabled;
        OptimusMod.routeHints=Boolean.getBoolean("optimus.bundledHints")?RouteHints.load(Files.createTempDirectory("optimus-bundled-hints").toFile()):RouteHintsSelfTest.replayHints();OptimusMod.routeHintsEnabled=true;
        String[] names={"east_precision","main_precision","main_precision_return","west_momentum","east_ledge","east_ledge_return","ledge_to_jump","jump_to_ledge","floor_crossing","long_hint_offset"};
        double[][] cases={{21.5,65,-111.5,23.5,66,-107.5},{2.5,69,-106.5,5.5,70,-102.5},{5.5,70,-102.5,2.5,69,-106.5},
            {-19.5,67.5,-100.5,-21.5,66.5,-106.5},{16.5,68.5,-96.5,20.5,68,-97.5},{20.5,68,-97.5,16.5,68.5,-96.5},
            {20.5,68,-97.5,23.5,66,-107.5},{23.5,66,-107.5,20.5,68,-97.5},{-14.5,63,-116.5,20.5,63,-115.5},
            {1.499,63,-119.502,20.5,63,-115.5}};
        List<String> failures=new ArrayList<>();
        try {
            for(int c=0;c<cases.length;c++) for(int facing=0;facing<6;facing++) {
                if(Integer.getInteger("optimus.hintCase",-1)>=0 && c!=Integer.getInteger("optimus.hintCase",-1)) continue;
                double[] data=cases[c];Vec3Point start=new Vec3Point(data[0],data[1],data[2]),goal=new Vec3Point(data[3],data[4],data[5]);
                nav.reset();((Map<?,?>)get(nav,"rejectedHints")).clear();set(nav,"hintRetryAt",0L);release.invoke(events);
                position(player,start,(facing%4)*90);player.hurtTime=0;player.getDataWatcher().updateObject(6,Float.valueOf(20));
                if(facing>=4) {
                    double length=Math.sqrt(start.horizontalDistanceSq(goal)),dx=(goal.x-start.x)/length,dz=(goal.z-start.z)/length;
                    player.motionX=dx*.15+(facing==5?dz*.04:0);player.motionZ=dz*.15-(facing==5?dx*.04:0);
                }
                if(rival!=null) { position(rival,goal,0);((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset(); }
                int before=controller.attacks,elapsed=0;boolean arrived=false,usedHint=false,usedAdvanced=false;
                String name=names[c]+"_"+facing;
                for(int tick=1;tick<=260;tick++) {
                    elapsed=tick;long now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",world,now);
                    if(!safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("hint fixture verification");
                    long clock=(c*6L+facing)*300+tick;set(events,"tickNo",clock);
                    if(rival==null) arrived=apply.invoke(events,goal,pit,nav.step(goal,pit,true,clock))==LobbyNavigator.Status.ARRIVED;
                    else { fight.invoke(get(events,"combat"),pit,point(player));arrived=controller.attacks>before && player.onGround; }
                    usedHint|=(Boolean)get(nav,"hintedPath");usedAdvanced|=get(get(nav,"advanced"),"hintFrames")!=null;
                    rows.add(name+","+tick+","+player.posX+","+player.posY+","+player.posZ+","+player.rotationYaw+","+player.onGround+","
                        +mc.gameSettings.keyBindForward.isKeyDown()+","+mc.gameSettings.keyBindBack.isKeyDown()+","+mc.gameSettings.keyBindJump.isKeyDown()+","+player.sprint+","+nav.activity+","
                        +mc.gameSettings.keyBindLeft.isKeyDown()+","+mc.gameSettings.keyBindRight.isKeyDown()+",,");
                    if(arrived) break;player.physics(mc.gameSettings);
                    if(!pit.containsBody(point(player),.3,1.8) || c<8 && player.posY<Math.min(start.y,goal.y)-.35) break;
                }
                boolean valid=arrived && (c>=4 || usedAdvanced) && (c!=3 || player.jumps==2);
                if(!valid) failures.add(name);
                System.out.println(name+": "+(valid?"PASS":"FAILED")+" ticks="+elapsed+" jumps="+player.jumps+" hint="+usedHint+" advanced="+usedAdvanced+" end="+point(player).serialize()+" last="+nav.activity);
                Files.write(Paths.get("diagnostics/route-hints-progress.csv"),rows,StandardCharsets.UTF_8);
                summary.add(name+","+start.x+","+start.y+","+start.z+","+goal.x+","+goal.y+","+goal.z+","+(valid?"ARRIVED":"FAILED")+","+elapsed+","+player.jumps+",0,0,0");
            }
            if(!failures.isEmpty()) throw new AssertionError("Runtime hint routes failed: "+failures);
            System.out.println("PASS: "+(cases.length*6)+" native runtime hint navigation cases"+(rival==null?"":" with combat and eligible attacks"));
            if(rival!=null) hintInterruptions(events,rival);
        } finally { release.invoke(events);OptimusMod.routeHints=previous;OptimusMod.routeHintsEnabled=previousEnabled; }
    }
    private static void hintInterruptions(ClientEvents events,Player rival) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();Player player=(Player)mc.thePlayer;World world=(World)mc.theWorld;
        LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");PitRegion pit=OptimusMod.settings.hypixelPit;
        LobbySafety safety=(LobbySafety)get(events,"lobbySafety");Controller controller=(Controller)mc.playerController;
        Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
        Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
        CollisionGeometry original=(CollisionGeometry)get(nav,"geometry");
        for(int scenario=0;scenario<5;scenario++) {
            nav.reset();((Map<?,?>)get(nav,"rejectedHints")).clear();set(nav,"hintRetryAt",0L);release.invoke(events);
            ((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset();
            position(player,new Vec3Point(21.5,65,-111.5),0);player.hurtTime=0;player.getDataWatcher().updateObject(6,Float.valueOf(20));
            position(rival,new Vec3Point(23.5,66,-107.5),0);boolean interrupted=false;
            for(int tick=1;tick<=100;tick++) {
                long clock=20000+scenario*1000+tick,now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",world,now);
                if(!safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("hint interruption verification");
                set(events,"tickNo",clock);fight.invoke(get(events,"combat"),pit,point(player));
                boolean ready=scenario==0?nav.preparingHint():scenario==2?get(get(nav,"advanced"),"hintFrames")!=null && player.onGround && player.jumps==0:get(get(nav,"advanced"),"hintFrames")!=null && !player.onGround;
                if(!ready) { player.physics(mc.gameSettings);continue; }
                if(scenario==0 || scenario==1) position(rival,new Vec3Point(player.posX+.8,player.posY,player.posZ),0);
                if(scenario==1) { player.hurtTime=10;player.getDataWatcher().updateObject(6,Float.valueOf(19));player.motionX+=.08; }
                if(scenario==2) position(rival,new Vec3Point(-5.5,63,-100.5),0);
                if(scenario==3) set(nav,"geometry",new CollisionGeometry((x,y,z)->CollisionGeometry.Cell.UNLOADED));
                int attacks=controller.attacks;
                if(scenario==4) events.stop("Offline stop during hinted flight");
                else { set(events,"tickNo",clock+1);fight.invoke(get(events,"combat"),pit,point(player)); }
                if(get(get(nav,"advanced"),"hintJump")!=null || get(get(nav,"advanced"),"hintFrames")!=null || mc.gameSettings.keyBindJump.isKeyDown()) throw new AssertionError("hint interruption retained movement: "+scenario);
                if(scenario==0 && mc.gameSettings.keyBindSneak.isKeyDown()) throw new AssertionError("melee takeover retained fine-positioning sneak");
                if(scenario==0 || scenario==1) {
                    for(int after=2;after<=12 && controller.attacks==attacks;after++) {
                        set(events,"tickNo",clock+after);fight.invoke(get(events,"combat"),pit,point(player));player.physics(mc.gameSettings);
                    }
                    if(controller.attacks==attacks) throw new AssertionError("eligible attacks did not resume after hint interruption: "+scenario);
                }
                if(scenario==3) set(nav,"geometry",original);
                if(scenario==4 && (mc.gameSettings.keyBindForward.isKeyDown() || mc.gameSettings.keyBindSprint.isKeyDown() || mc.gameSettings.keyBindSneak.isKeyDown())) throw new AssertionError("stop retained hinted inputs");
                interrupted=true;break;
            }
            if(!interrupted) throw new AssertionError("hint interruption setup did not execute: "+scenario);
        }
        set(nav,"geometry",original);System.out.println("PASS: native melee, damage, moving-target, unloaded-corridor and stop interruption checks");
    }
    private static void handoffReplays(ClientEvents events,Player rival,List<String> rows,List<String> summary) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();Player player=(Player)mc.thePlayer;World world=(World)mc.theWorld;
        LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");PitRegion pit=OptimusMod.settings.hypixelPit;
        LobbySafety safety=(LobbySafety)get(events,"lobbySafety");Controller controller=(Controller)mc.playerController;
        Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
        Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
        RouteHints previous=OptimusMod.routeHints;
        OptimusMod.routeHints=Boolean.getBoolean("optimus.bundledHints")?RouteHints.load(Files.createTempDirectory("optimus-bundled-handoff").toFile()):RouteHintsSelfTest.handoffHints();
        List<String> failures=new ArrayList<>();
        try {
            for(int c=0;c<6;c++) for(int facing=0;facing<4;facing++) {
                Vec3Point start=c==0?new Vec3Point(-14.5,68.5,-105.5):c==1?new Vec3Point(13.5,65,-79.5):c==2?new Vec3Point(.5,63,-100.5):c==5?new Vec3Point(-2.575,69,-81.5):new Vec3Point(-1.5,69,-81.5);
                Vec3Point goal=c==1 || c==3?new Vec3Point(-1.5,68.5,-87.5):c>=4?new Vec3Point(-3.5,68.5,-92.5):new Vec3Point(5.5,70,-102.5);
                nav.reset();((Map<?,?>)get(nav,"rejectedHints")).clear();set(nav,"hintRetryAt",0L);release.invoke(events);
                ((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset();position(player,start,c==5?179.25f+facing*.5f:facing*90);position(rival,goal,0);
                player.hurtTime=0;player.getDataWatcher().updateObject(6,Float.valueOf(20));
                int before=controller.attacks,restarts=0,elapsed=0;boolean arrived=false,preparing=false;
                String name=(c==0?"roof_via_main":c==1?"middle_via_upper":c==2?"floor_via_roof":c==3?"north_roof_near":c==4?"north_roof_far":"north_roof_fine_heading")+"_"+facing;
                for(int tick=1;tick<=650;tick++) {
                    elapsed=tick;long clock=(c*4L+facing)*1000+tick,now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",world,now);
                    if(!safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("handoff verification");
                    set(events,"tickNo",clock);fight.invoke(get(events,"combat"),pit,point(player));
                    if(preparing && nav.activity.startsWith("Finding path")) restarts++;
                    preparing=nav.preparingGap() && !nav.jumpInProgress();
                    rows.add(name+","+tick+","+player.posX+","+player.posY+","+player.posZ+","+player.rotationYaw+","+player.onGround+","
                        +mc.gameSettings.keyBindForward.isKeyDown()+","+mc.gameSettings.keyBindBack.isKeyDown()+","+mc.gameSettings.keyBindJump.isKeyDown()+","+player.sprint+","+nav.activity+","
                        +mc.gameSettings.keyBindLeft.isKeyDown()+","+mc.gameSettings.keyBindRight.isKeyDown()+",,");
                    if(controller.attacks>before && player.onGround) { arrived=true;break; }
                    player.physics(mc.gameSettings);if(!pit.containsBody(point(player),.3,1.8) || c<2 && player.posY<start.y-.35 || c>=3 && player.posY<goal.y-.35) break;
                }
                boolean valid=arrived && restarts==0;if(!valid) failures.add(name);
                System.out.println(name+": "+(valid?"PASS":"FAILED")+" ticks="+elapsed+" restarts="+restarts+" jumps="+player.jumps+" end="+point(player).serialize()+" last="+nav.activity);
                summary.add(name+","+start.x+","+start.y+","+start.z+","+goal.x+","+goal.y+","+goal.z+","+(valid?"ARRIVED":"FAILED")+","+elapsed+","+player.jumps+",0,0,"+restarts);
            }
            nav.reset();set(nav,"hintRetryAt",0L);release.invoke(events);position(player,new Vec3Point(-2.5,69,-106.5),0);
            long ready=awaitHintPlan(nav,new Vec3Point(5.5,70,-102.5),pit,50000);
            if(!(Boolean)get(nav,"hintedPath")) throw new AssertionError("moving-target fixture must start with a cached jump corridor");
            nav.step(new Vec3Point(6.5,70,-101.5),pit,true,ready+6);
            boolean retained=(Boolean)get(nav,"hintedPath") && get(nav,"pathHints")!=null;
            System.out.println("moving_goal_jump_metadata: "+(retained?"PASS":"FAILED"));if(!retained) failures.add("moving_goal_jump_metadata");
            nav.reset();set(nav,"hintRetryAt",0L);position(player,new Vec3Point(-2.5,69,-106.5),0);
            ready=awaitHintPlan(nav,new Vec3Point(5.5,70,-102.5),pit,51000);
            nav.step(new Vec3Point(-21.5,75,-126.5),pit,true,ready+6);
            boolean searchingWithMetadata=get(nav,"search")!=null && (Boolean)get(nav,"hintedPath") && get(nav,"pathHints")!=null;
            System.out.println("background_search_jump_metadata: "+(searchingWithMetadata?"PASS":"FAILED"));if(!searchingWithMetadata) failures.add("background_search_jump_metadata");
            if(!failures.isEmpty()) throw new AssertionError("Prepared jump handoff failed: "+failures);
            System.out.println("PASS: 24 reported intermediate-jump/near-far/fine-heading combat routes and two metadata handoff checks");
        } finally { release.invoke(events);OptimusMod.routeHints=previous; }
    }
    private static long awaitHintPlan(LobbyNavigator nav,Vec3Point goal,PitRegion pit,long tick) throws Exception {
        for(int i=0;i<1000;i++) {
            nav.step(goal,pit,true,tick+i);
            if((Boolean)get(nav,"hintedPath") && get(nav,"search")==null) return tick+i;
        }
        throw new AssertionError("mixed search did not produce a route using a required hint");
    }
    private static void optionalHintReplays(ClientEvents events,Player rival,List<String> rows,List<String> summary) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();Player player=(Player)mc.thePlayer;
        LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");PitRegion pit=OptimusMod.settings.hypixelPit;
        LobbySafety safety=(LobbySafety)get(events,"lobbySafety");Controller controller=(Controller)mc.playerController;
        Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
        Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
        RouteHints previous=OptimusMod.routeHints;
        OptimusMod.routeHints=Boolean.getBoolean("optimus.bundledHints")?RouteHints.load(Files.createTempDirectory("optimus-optional-hints").toFile()):RouteHintsSelfTest.optionalHints();
        try {
            for(int c=0;c<4;c++) for(int facing=0;facing<4;facing++) {
                Vec3Point start=c%2==0?new Vec3Point(-14.5,63,-116.5):new Vec3Point(-14.5,68.5,-105.5),goal=new Vec3Point(-18.5,63,-90.5);
                nav.reset();set(nav,"hintRetryAt",0L);((Map<?,?>)get(nav,"rejectedHints")).clear();release.invoke(events);
                ((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset();position(player,start,facing*90);position(rival,goal,0);
                if(c>=2) { awaitHintPlan(nav,new Vec3Point(5.5,70,-102.5),pit,1000);set(get(events,"combat"),"turningTarget",rival); }
                int attacks=controller.attacks,elapsed=0;boolean arrived=false;String name="west_optional_"+c+"_"+facing;
                for(int tick=1;tick<=450;tick++) {
                    elapsed=tick;long clock=50000+c*4000L+facing*700L+tick,now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",mc.theWorld,now);
                    if(!safety.confirm("mc.hypixel.net",mc.theWorld,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("optional hint verification");
                    set(events,"tickNo",clock);fight.invoke(get(events,"combat"),pit,point(player));
                    rows.add(name+","+tick+","+player.posX+","+player.posY+","+player.posZ+","+player.rotationYaw+","+player.onGround+","
                        +mc.gameSettings.keyBindForward.isKeyDown()+","+mc.gameSettings.keyBindBack.isKeyDown()+","+mc.gameSettings.keyBindJump.isKeyDown()+","+player.sprint+","+nav.activity+","
                        +mc.gameSettings.keyBindLeft.isKeyDown()+","+mc.gameSettings.keyBindRight.isKeyDown()+",,");
                    if(c%2==0 && player.posY>64.25 || get(get(nav,"advanced"),"hintFrames")!=null
                            || player.onGround && player.posY>66 && point(player).horizontalDistanceSq(new Vec3Point(-19.5,67.5,-100.5))<4
                            || player.onGround && player.posY>65 && point(player).horizontalDistanceSq(new Vec3Point(-21.5,66.5,-106.5))<4)
                        throw new AssertionError("unnecessary roof/wall chain for floor target: "+name+" "+point(player).serialize()+" "+nav.activity);
                    if(controller.attacks>attacks && player.onGround && Math.abs(player.posY-goal.y)<.1) { arrived=true;break; }
                    player.physics(mc.gameSettings);if(!pit.containsBody(point(player),.3,1.8)) break;
                }
                if(!arrived) throw new AssertionError("optional-hint chase did not arrive: "+name+" "+point(player).serialize()+" "+nav.activity);
                System.out.println(name+": PASS ticks="+elapsed+" jumps="+player.jumps);
                summary.add(name+","+start.x+","+start.y+","+start.z+","+goal.x+","+goal.y+","+goal.z+",ARRIVED,"+elapsed+","+player.jumps+",0,0,0");
            }
            System.out.println("PASS: sixteen native fresh/moved-target west-side floor chases reject unnecessary roof and wall parkour");
        } finally { release.invoke(events);OptimusMod.routeHints=previous; }
    }
    private static void shortcutReplays(ClientEvents events,List<String> rows,List<String> summary) throws Exception {
        Minecraft mc=Minecraft.getMinecraft();Player player=(Player)mc.thePlayer;
        LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");PitRegion pit=OptimusMod.settings.hypixelPit;
        LobbySafety safety=(LobbySafety)get(events,"lobbySafety");
        Method navigate=ClientEvents.class.getDeclaredMethod("navigate",Vec3Point.class,PitRegion.class,boolean.class);navigate.setAccessible(true);
        Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
        RouteHints previous=OptimusMod.routeHints;OptimusMod.routeHints=RouteHintsSelfTest.handoffHints();
        try {
            Vec3Point original=new Vec3Point(.5,63,-100.5),goal=new Vec3Point(5.5,70,-102.5);
            Vec3Point[] starts={new Vec3Point(-17.5,63,-110.5),new Vec3Point(-14.5,63,-116.5),
                new Vec3Point(-13.5,68.5,-105.5),new Vec3Point(-4.5,69,-106.5)};
            for(int c=0;c<starts.length;c++) for(int facing=0;facing<4;facing++) {
                nav.reset();set(nav,"hintRetryAt",0L);((Map<?,?>)get(nav,"rejectedHints")).clear();release.invoke(events);
                position(player,original,0);awaitHintPlan(nav,goal,pit,1000);
                if(!(Boolean)get(nav,"hintedPath")) throw new AssertionError("shortcut needs an existing cached corridor");
                position(player,starts[c],facing*90);boolean arrived=false;int elapsed=0;
                String name="rejoin_"+c+"_"+facing;
                for(int tick=1;tick<=650;tick++) {
                    elapsed=tick;long clock=2000+c*4000L+facing*700L+tick,now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",mc.theWorld,now);
                    if(!safety.confirm("mc.hypixel.net",mc.theWorld,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("shortcut verification");
                    set(events,"tickNo",clock);Object status=navigate.invoke(events,goal,pit,true);
                    if(tick==1 && (nav.activity.startsWith("Finding path") || nav.activity.contains("corridor changed"))) throw new AssertionError("reachable later corridor was discarded: "+name);
                    rows.add(name+","+tick+","+player.posX+","+player.posY+","+player.posZ+","+player.rotationYaw+","+player.onGround+","
                        +mc.gameSettings.keyBindForward.isKeyDown()+","+mc.gameSettings.keyBindBack.isKeyDown()+","+mc.gameSettings.keyBindJump.isKeyDown()+","+player.sprint+","+nav.activity+","
                        +mc.gameSettings.keyBindLeft.isKeyDown()+","+mc.gameSettings.keyBindRight.isKeyDown()+",,");
                    if(status==LobbyNavigator.Status.ARRIVED) { arrived=true;break; }
                    player.physics(mc.gameSettings);
                    if(!pit.containsBody(point(player),.3,1.8) || c>=2 && player.posY<starts[c].y-.35) break;
                }
                if(!arrived) throw new AssertionError("shortcut did not complete: "+name+" at "+point(player).serialize()+" "+nav.activity);
                System.out.println(name+": PASS ticks="+elapsed+" jumps="+player.jumps);
                summary.add(name+","+starts[c].x+","+starts[c].y+","+starts[c].z+","+goal.x+","+goal.y+","+goal.z+",ARRIVED,"+elapsed+","+player.jumps+",0,0,0");
            }
            // Isolate travel-jump pacing on a known flat segment; the sixteen
            // complete rejoin routes above use the captured lobby geometry.
            set(nav,"geometry",((World)mc.theWorld).platforms(63,63,0,0));
            for(double speed:new double[]{.153,.16,.17,.19}) {
                nav.reset();release.invoke(events);position(player,original,-90);player.sprint=true;player.motionX=speed;player.jumpMovementFactor=.026f;
                GroundRoute ground=new GroundRoute(((CollisionGeometry)get(nav,"geometry")).cached(),pit,pit,null,63,true);
                set(nav,"plannedTerrain",ground);set(nav,"path",Arrays.asList(RouteHints.node(original),RouteHints.node(new Vec3Point(8.5,63,-100.5))));
                set(nav,"index",1);set(nav,"hintedPath",true);set(nav,"pathHints",OptimusMod.routeHints);set(nav,"plannedGoal",goal);set(nav,"targetMode",true);
                boolean landed=false;
                for(int tick=1;tick<=30;tick++) {
                    long clock=30000+tick,now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",mc.theWorld,now);
                    safety.confirm("mc.hypixel.net",mc.theWorld,now,"DUELS","duelslobby9","dynamiclobbyTEST",false);set(events,"tickNo",clock);
                    navigate.invoke(events,goal,pit,true);
                    if(tick==1 && get(nav,"sprintLeap")==null) throw new AssertionError("checked intermediate route segment did not sprint jump: "+speed);
                    if(nav.committedGoal()==null || nav.committedGoal().horizontalDistanceSq(goal)>.001 || Math.abs(nav.committedGoal().y-goal.y)>.001)
                        throw new AssertionError("travel jump replaced the final pursuit goal");
                    if(tick>1 && player.onGround && get(nav,"sprintLeap")==null) { landed=true;break; }
                    player.physics(mc.gameSettings);
                }
                if(!landed || player.jumps!=1 || player.posX<original.x+3 || Math.abs(player.posZ-original.z)>.2 || Math.abs(player.posY-63)>.01)
                    throw new AssertionError("intermediate sprint jump did not land on checked route: "+speed+" at "+point(player).serialize());
            }
            System.out.println("PASS: 16 native corridor rejoins and four intermediate sprint jumps preserving the final chase goal");
        } finally { release.invoke(events);OptimusMod.routeHints=previous; }
    }
    public static void main(String[] args) throws Exception {
        final boolean slabAndDrop=args.length>0 && args[0].equals("slab-and-drop");
        final boolean reportedRoof=args.length>0 && args[0].equals("reported-roof");
        final boolean reportedJumps=args.length>0 && args[0].equals("reported-jumps");
        final boolean wasd=args.length>0 && args[0].equals("wasd");
        final boolean steering=args.length>0 && args[0].equals("steering");
        final boolean reportedPost=args.length>0 && args[0].equals("post");
        final boolean simpleClimbs=args.length>0 && args[0].equals("block-climbs");
        final boolean fightingPost=args.length>0 && args[0].equals("post-combat");
        final boolean fightingJumps=args.length>0 && args[0].equals("jumps-combat");
        final boolean fightingLongGap=args.length>0 && args[0].equals("long-gap-combat");
        final boolean fightingHop=args.length>0 && args[0].equals("jump-attack");
        final boolean fightingStairs=args.length>0 && args[0].equals("stairs-combat");
        final boolean fightingHints=args.length>0 && args[0].equals("hints-combat");
        final boolean handoff=args.length>0 && args[0].equals("hint-handoff");
        final boolean shortcuts=args.length>0 && args[0].equals("hint-shortcuts");
        final boolean optional=args.length>0 && args[0].equals("optional-hints");
        final boolean hints=fightingHints || args.length>0 && args[0].equals("hints");
        final boolean stairSprint=fightingStairs || args.length>0 && args[0].equals("stairs");
        final boolean reportedLongGap=fightingLongGap || args.length>0 && args[0].equals("long-gap");
        final boolean fighting=fightingPost || fightingJumps || fightingLongGap || fightingHop || fightingStairs || fightingHints || handoff || shortcuts || optional;
        final String fixture=hints || reportedRoof || reportedJumps || steering || reportedPost || reportedLongGap || fighting?"duels-pit-2026-10-15.tsv.gz":"duels-collision.tsv.gz";
        final String reportName=optional?"optional-hint-navigation":shortcuts?"hint-shortcut-navigation":handoff?"jump-handoff-navigation":hints?(fightingHints?"route-hints-combat":"route-hints-navigation"):stairSprint?(fightingStairs?"stairs-combat-navigation":"stairs-navigation"):fightingHop?"jump-attack-navigation":reportedLongGap?(fightingLongGap?"long-gap-combat-navigation":"long-gap-navigation"):slabAndDrop?"slab-and-drop-navigation":reportedRoof?"reported-roof-navigation":reportedJumps?"reported-jumps-navigation":wasd?"wasd-navigation":steering?"steering-navigation":reportedPost?"post-navigation":simpleClimbs?"block-climb-navigation":fightingJumps?"jumps-combat-navigation":fightingPost?"post-combat-navigation":"captured-navigation";
        Unsafe unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        Field singleton=field(Minecraft.class,"theMinecraft");Object old=singleton.get(null);
        ClientSettings oldSettings=OptimusMod.settings;EmbeddedChannel channel=null;
        CombatTrace trace=null;
        List<String> rows=new ArrayList<>();rows.add("case,tick,x,y,z,yaw,on_ground,forward,back,jump,sprint,activity,left,right,waypoint_x,waypoint_z");
        List<String> summary=new ArrayList<>();summary.add("case,from_x,from_y,from_z,to_x,to_y,to_z,result,ticks,jumps,sprint_ticks,corner_ticks,idle_ticks");
        List<String> failures=new ArrayList<>();
        try {
            Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);singleton.set(null,mc);
            mc.gameSettings=new GameSettings();World world=(World)unsafe.allocateInstance(World.class);mc.theWorld=world;
            set(world,"theProfiler",new Profiler());world.load(fixture);
            Player player=(Player)unsafe.allocateInstance(Player.class);mc.thePlayer=player;player.worldObj=world;
            player.label="OfflineBot";player.eyeHeight=player.getDefaultEyeHeight();
            set(player,"foodStats",new FoodStats());
            player.capabilities=new PlayerCapabilities();
            net.minecraft.entity.DataWatcher watcher=new net.minecraft.entity.DataWatcher(player);
            watcher.addObject(6,Float.valueOf(20));set(player,"dataWatcher",watcher);
            player.inventory=new InventoryPlayer(player);player.inventory.mainInventory[0]=new ItemStack(new ItemSword(Item.ToolMaterial.IRON));
            mc.playerController=(Controller)unsafe.allocateInstance(Controller.class);
            set(mc,"currentServerData",new ServerData("Offline fixture","mc.hypixel.net",false));
            NetworkManager manager=new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient handler=(NetHandlerPlayClient)unsafe.allocateInstance(NetHandlerPlayClient.class);
            set(handler,"netManager",manager);set(player,"sendQueue",handler);
            OptimusMod.settings=new ClientSettings(Files.createTempDirectory("optimus-captured-navigation").toFile());
            ClientSettings settings=OptimusMod.settings;PitRegion pit=settings.hypixelPit;
            pit.setCornerA(new Vec3Point(-25,62.5,-127));pit.setCornerB(new Vec3Point(25,78,-77));
            settings.pitLobby="duelslobby9";settings.pitInstance="dynamiclobbyTEST";
            settings.pitEntrance=new Vec3Point(-1.5,69,-79.5);
            ClientEvents events=new ClientEvents();LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");
            Player rival=null;
            if(fighting) {
                rival=(Player)unsafe.allocateInstance(Player.class);rival.label="OfflineRival";rival.worldObj=world;rival.eyeHeight=rival.getDefaultEyeHeight();
                net.minecraft.entity.DataWatcher rivalWatcher=new net.minecraft.entity.DataWatcher(rival);rivalWatcher.addObject(6,Float.valueOf(20));set(rival,"dataWatcher",rivalWatcher);
                set(world,"playerEntities",new ArrayList<EntityPlayer>(Arrays.asList(player,rival)));
                settings.whitelist.add("OfflineRival");settings.combatProfile=CombatProfile.BALANCED;
                trace=new CombatTrace(settings.traceDirectory());set(events,"trace",trace);
                ((Random)get(get(events,"combat"),"random")).setSeed(settings.combatSeed);
                ((CombatTurn)get(get(events,"combat"),"combatTurn")).seed(settings.combatSeed^0x5455524eL);
            }
            set(nav,"geometry",CollisionReplaySelfTest.capture(fixture));
            LobbySafety safety=(LobbySafety)get(events,"lobbySafety");
            TransitionWatch watch=new TransitionWatch(safety,manager);channel=new EmbeddedChannel(watch);set(manager,"channel",channel);
            set(events,"transitionWatch",watch);set(events,"sessionWorld",world);set(events,"sessionPlayer",player);set(events,"armed",true);
            RunSafety run=(RunSafety)get(events,"run");run.startChecking(0);run.activate("duelslobby9","dynamiclobbyTEST",world);
            Method apply=ClientEvents.class.getDeclaredMethod("navigateStep",Vec3Point.class,PitRegion.class,LobbyNavigator.Step.class);apply.setAccessible(true);
            Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);
            Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
            if(handoff) { handoffReplays(events,rival,rows,summary);return; }
            if(optional) { optionalHintReplays(events,rival,rows,summary);return; }
            if(shortcuts) { shortcutReplays(events,rows,summary);return; }
            if(hints) { hintReplays(events,rival,rows,summary);return; }
            if(fightingHop) { combatHopReplays(events,rival,rows,summary);return; }
            if(stairSprint) { stairReplays(events,rival,rows,summary);return; }
            if(reportedLongGap) {
                world.platforms(63,63,0,0);release.invoke(events);
                position(player,new Vec3Point(.5,63,-100.5),0);
                net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(),true);
                double apex=63;
                for(int tick=0;tick<20;tick++) {
                    player.physics(mc.gameSettings);apex=Math.max(apex,player.posY);
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(),false);
                }
                double rise=apex-63;
                if(rise>=1.25 || Math.abs(rise-CollisionGeometry.MAXIMUM_JUMP_RISE)>1e-6)
                    throw new AssertionError("jump ceiling must match native 1.8.9 movement: "+rise);
                System.out.println("PASS: native jump apex "+rise+" remains below the rejected 1.25-block ledge");
                release.invoke(events);world.load(fixture);
            }
            // Fixed, named real-map cases, rather than searching for whichever pairs happen to pass.
            String[] names={"west_quartz_climb","west_diagonal_gap","south_slab_climb","wool_diagonal_climb","wool_diagonal_step",
                "west_quartz_approach","west_diagonal_return","south_slab_parallel","west_slab_walk","west_slab_long_walk",
                "quartz_stair_chain","floor_to_quartz_stairs","wool_facing_east","wool_facing_south","wool_facing_west"};
            double[][] cases={{-14.5,63,-116.5,-13.5,64,-116.5},{-14.5,63,-116.5,-13.5,63,-117.5},
                {-.5,70.5,-96.5,1.5,71.5,-96.5},{1.5,68,-112.5,-.5,69,-110.5},{1.5,68,-112.5,.5,69,-110.5},
                {-15.5,63,-116.5,-13.5,64,-116.5},{-13.5,63,-117.5,-14.5,63,-116.5},
                {-.5,70.5,-95.5,1.5,71.5,-95.5},{-23.5,66.5,-109.5,-23.5,66.5,-108.5},{-23.5,66.5,-109.5,-23.5,66.5,-107.5},
                {-15.5,63,-116.5,-13.5,65,-115.5},{.5,63,-100.5,-13.5,65,-115.5},
                {1.5,68,-112.5,.5,69,-110.5},{1.5,68,-112.5,.5,69,-110.5},{1.5,68,-112.5,.5,69,-110.5}};
            if(slabAndDrop) {
                names=new String[]{"slab_to_full_adjacent","slab_to_full_gap","slab_to_full_diagonal_gap","walk_down_half","walk_down_one","walk_down_three","walk_down_seven"};
                cases=new double[][]{{.5,69.5,-100.5,1.5,70,-100.5},{.5,69.5,-100.5,2.5,70,-100.5},
                    {.5,69.5,-100.5,2.5,70,-98.5},{.5,70,-100.5,1.5,69.5,-100.5},
                    {.5,70,-100.5,1.5,69,-100.5},{.5,70,-100.5,1.5,67,-100.5},{.5,70,-100.5,1.5,63,-100.5}};
            }
            if(reportedRoof) {
                names=new String[]{"reported_roof_edge","reported_roof_approach","reported_roof_parallel","reported_roof_reverse","reported_roof_step_down"};
                cases=new double[][]{{-11.5,68.5,-108.5,-7.5,69,-108.5},{-13,68.5,-109,-7.5,69,-109},
                    {-11.5,68.5,-109.5,-7.5,69,-109.5},{-7.5,69,-108.5,-11.5,68.5,-108.5},
                    {-13.5,68.5,-108.5,-10.5,65.5,-108.5}};
            }
            if(reportedJumps || fightingJumps) {
                names=new String[]{"west_reported_north","west_reported_east","west_reported_south","west_reported_west",
                    "north_reported_north","north_reported_east","north_reported_south","north_reported_west"};
                cases=new double[8][];
                for(int i=0;i<8;i++) cases[i]=i<4?new double[]{-14,68.5,-105,-19,67.5,-100}:new double[]{-2,69,-81,-2,68.5,-88};
            }
            if(wasd) {
                names=new String[]{"walk_look_north","walk_look_west","walk_look_south","walk_look_east",
                    "jump_look_north","jump_look_west","jump_look_south","jump_look_east"};
                cases=new double[8][];
                for(int i=0;i<8;i++) cases[i]=i<4?new double[]{.5,63,-100.5,1.5,63,-100.5}:new double[]{.5,63,-100.5,1.5,64,-100.5};
            }
            if(steering) {
                names=new String[24];cases=new double[24][];
                for(int i=0;i<16;i++) { names[i]=(i<8?"long_forward_":"long_chase_")+((i%8)*45);cases[i]=new double[]{.5,63,-100.5,7.5,63,-100.5}; }
                for(int i=16;i<24;i++) {
                    names[i]=(i<20?"west_offset_":"north_offset_")+(i%4);
                    cases[i]=i<20?new double[]{-13.85,68.5,-105.15,-19,67.5,-100}:new double[]{-1.85,69,-80.9,-2,68.5,-88};
                }
            }
            if(reportedPost || fightingPost || simpleClimbs) {
                names=new String[12];cases=new double[12][];
                for(int i=0;i<12;i++) {
                    names[i]=(reportedPost || fightingPost?"reported_post_":"normal_block_")+i;
                    cases[i]=reportedPost || fightingPost?new double[]{i<4?15.5:i<8?15:16.5,64,i<4?-81.5:-82,13.5,65,-79.5}
                        :new double[]{i<4?.5:i<8?.69:.55,63,-100.5,1.5,64,-100.5};
                    if(fightingPost && i>=4) { cases[i][3]=12.5;cases[i][5]=-78.5; }
                }
            }
            if(reportedLongGap) {
                names=new String[36];cases=new double[36][];
                double[][] destinations={{9.5,66,-80.5},{9,66,-81},{8.5,66,-80.5}};
                for(int i=0;i<36;i++) {
                    names[i]="east_platform_detour_"+i;
                    int startCase=i%12;double[] destination=destinations[i/12];
                    cases[i]=new double[]{startCase<4?16.5:startCase<8?15:17.5,64,startCase<8?-82:-82.5,
                        destination[0],destination[1],destination[2]};
                }
            }
            for(int c=0;c<cases.length;c++) {
                double[] v=cases[c];Vec3Point start=new Vec3Point(v[0],v[1],v[2]),goal=new Vec3Point(v[3],v[4],v[5]);
                if(slabAndDrop) set(nav,"geometry",world.platforms(start.y,goal.y,c==1 || c==2?1:0,c==2?1:c==1?2:0));
                if(wasd) set(nav,"geometry",world.platforms(start.y,goal.y,0,0));
                if(simpleClimbs) set(nav,"geometry",world.platforms(63,64,0,0));
                if(steering) {
                    if(c<16) set(nav,"geometry",world.platforms(63,63,0,0));
                    else { world.load(fixture);set(nav,"geometry",CollisionReplaySelfTest.capture(fixture)); }
                }
                nav.reset();release.invoke(events);position(player,start,steering?c<16?(c%8)*45:15+(c%4)*90:reportedJumps || wasd || reportedPost || reportedLongGap || fighting || simpleClimbs?(c%4)*90:c>=12?(c-11)*90:0);boolean arrived=false;
                if(rival!=null) { position(rival,goal,0);((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset(); }
                if(steering && c>=16) { player.motionX=.08;player.motionZ=.04; }
                if(simpleClimbs && c>=8) player.motionX=.15;
                int limit=reportedLongGap?240:fightingPost?90:steering || reportedPost || fightingJumps?180:simpleClimbs?50:reportedRoof || reportedJumps?180:c==10 || c==11?1000:80;
                int elapsed=0,sprintTicks=0,cornerTicks=0,idleTicks=0,firstJump=0;boolean complexClimb=false,diagonalLaunch=false,usedMiddlePlatform=false;
                int attacksBefore=((Controller)mc.playerController).attacks;
                for(int tick=1;tick<=limit;tick++) {
                    elapsed=tick;
                    long now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",world,now);
                    if(!safety.confirm("mc.hypixel.net",world,now,"DUELS","duelslobby9","dynamiclobbyTEST",false)) throw new AssertionError("fixture verification");
                    set(events,"tickNo",fighting?(long)c*(limit+1)+tick:(long)tick);
                    // The reported approach chases a real, non-grid target position.
                    LobbyNavigator.Step step=fighting?new LobbyNavigator.Step(LobbyNavigator.Status.WAITING,null,false)
                        :nav.step(goal,pit,reportedJumps || reportedLongGap || steering && c>=8 || reportedRoof && c==1,tick);
                    Object status;
                    if(fighting) {
                        fight.invoke(get(events,"combat"),pit,point(player));
                        boolean fought=((Controller)mc.playerController).attacks-attacksBefore>=(fightingPost && c<4?3:1);
                        boolean positioned=fightingLongGap?player.onGround && player.posY>=65:fightingPost && c<4?player.onGround && player.jumps==0:player.onGround && Math.abs(player.posY-goal.y)<.1 && point(player).horizontalDistanceSq(goal)<4;
                        status=fought && positioned?LobbyNavigator.Status.ARRIVED:LobbyNavigator.Status.MOVING;
                    } else status=apply.invoke(events,goal,pit,step);
                    if((reportedPost || fightingPost) && player.jumps==0 && get(nav,"gapLeap")!=null) {
                        SprintGap.Plan gap=(SprintGap.Plan)get(nav,"gapLeap");diagonalLaunch=Math.abs(gap.candidate.dx)>.01 && Math.abs(gap.candidate.dz)>.01;
                    }
                    complexClimb|=simpleClimbs && (step.platformLeap!=null || step.gapLeap!=null);
                    usedMiddlePlatform|=reportedLongGap && player.onGround && Math.abs(player.posY-65)<.01
                        && player.posX>=11.7 && player.posX<=15.3 && player.posZ>=-80.3 && player.posZ<=-77.7;
                    if(wasd && Math.abs(MathHelper.wrapAngleTo180_float(player.rotationYaw-(c%4)*90))>.001) throw new AssertionError("walking/short jumping redirected the fixed camera");
                    rows.add(names[c]+","+tick+","+player.posX+","+player.posY+","+player.posZ+","+player.rotationYaw+","+player.onGround+","+
                        mc.gameSettings.keyBindForward.isKeyDown()+","+mc.gameSettings.keyBindBack.isKeyDown()+","+mc.gameSettings.keyBindJump.isKeyDown()+","+player.sprint+","+nav.activity+","+
                        mc.gameSettings.keyBindLeft.isKeyDown()+","+mc.gameSettings.keyBindRight.isKeyDown()+","+(step.waypoint==null?"":step.waypoint.x)+","+(step.waypoint==null?"":step.waypoint.z));
                    if(player.sprint && mc.gameSettings.keyBindForward.isKeyDown()) sprintTicks++;
                    if(nav.activity.equals("Aligning route corner")) cornerTicks++;
                    if(!mc.gameSettings.keyBindForward.isKeyDown() && !mc.gameSettings.keyBindBack.isKeyDown()
                        && !mc.gameSettings.keyBindLeft.isKeyDown() && !mc.gameSettings.keyBindRight.isKeyDown()) idleTicks++;
                    if(status==LobbyNavigator.Status.ARRIVED) { arrived=true;break; }
                    player.physics(mc.gameSettings);
                    if(firstJump==0 && player.jumps>0) firstJump=tick;
                    if(!pit.containsBody(point(player),.3,1.8)) break;
                    if((reportedJumps || fightingJumps || steering && c>=16) && player.posY<Math.min(start.y,goal.y)-.35) break;
                    if((reportedPost || fightingPost || reportedLongGap) && player.posY<start.y-.35) break;
                }
                System.out.println(names[c]+": "+(arrived?"ARRIVED":"FAILED")+" jumps="+player.jumps+" end="+point(player).serialize()+" last="+nav.activity
                    +(fighting?" attacks="+(((Controller)mc.playerController).attacks-attacksBefore)+" "+get(get(events,"combat"),"combatDetail"):""));
                summary.add(names[c]+","+start.x+","+start.y+","+start.z+","+goal.x+","+goal.y+","+goal.z+","+(arrived?"ARRIVED":"FAILED")+","+elapsed+","+player.jumps+","+sprintTicks+","+cornerTicks+","+idleTicks);
                boolean jumpCountWrong=reportedLongGap?player.jumps<1 || player.jumps>2:fightingJumps?player.jumps!=1:fightingPost?player.jumps!=(c<4?0:1):reportedPost || simpleClimbs?player.jumps!=1:steering?player.jumps!=(c<16?0:1):wasd?player.jumps!=(c<4?0:1):reportedJumps?player.jumps!=1:reportedRoof?(c==4?player.jumps!=0:player.jumps==0):slabAndDrop?(c==1 || c==2?player.jumps==0:player.jumps!=0):c!=1 && c!=6 && c!=8 && c!=9 && player.jumps==0;
                if((reportedPost || fightingPost && c>=4) && !diagonalLaunch) failures.add(names[c]+" failed to use the checked diagonal post approach");
                if(simpleClimbs && (complexClimb || firstJump>3)) failures.add(names[c]+" planned/waited for full parkour instead of a normal adjacent climb");
                if(reportedLongGap && !usedMiddlePlatform) failures.add(names[c]+" skipped the reachable middle platform");
                if(steering && c<16 && sprintTicks<(c<8?12:8)) failures.add(names[c]+" lacked sustained forward sprint");
                if(!arrived || jumpCountWrong) failures.add(names[c]);
            }
            if(slabAndDrop) {
                CollisionGeometry g=world.platforms(70,63,0,0);Vec3Point from=new Vec3Point(.5,70,-100.5),to=new Vec3Point(1.5,63,-100.5);
                PitRegion elsewhere=new PitRegion();elsewhere.setCornerA(new Vec3Point(14,62.5,-127));elsewhere.setCornerB(new Vec3Point(25,78,-77));
                if(!new GroundRoute(g,pit,pit,null,63,false).drop(from,to)) throw new AssertionError("authorized pit drop is independent of parkour/jump capability");
                if(new GroundRoute(g,pit,elsewhere,null,63,false).edgeClear(from,to)) throw new AssertionError("deep outside-pit descent cannot inherit damage-free pit policy");
                if(g.walkOffClear(from,new Vec3Point(4.5,63,-100.5),pit)) throw new AssertionError("walking off a ledge cannot authorize a distant gap");
                if(g.walkOffClear(from,new Vec3Point(1.5,64,-100.5),pit)) throw new AssertionError("drop needs an actually supported landing");
            }
            if(reportedRoof) {
                Method move=MovementInput.class.getDeclaredMethod("move",boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class);move.setAccessible(true);
                // Cross-check ground projection against native acceleration/inertia,
                // including diagonal normalization and the ordinary sneak slowdown.
                set(nav,"geometry",world.platforms(63,63,0,0));
                for(boolean sneak:new boolean[]{false,true}) for(int[] input:new int[][]{{1,0},{-1,0},{0,1},{1,1},{0,0}}) {
                    release.invoke(events);position(player,new Vec3Point(.5,63,-100.5),-90);player.motionX=.18;player.motionZ=.04;
                    Vec3Point delta=GroundMotion.delta(.18,.04,-90,input[0],input[1],.1*.16277136/Math.pow(.6*.91,3),sneak?.3:1);
                    move.invoke(get(events,"input"),input[0]>0,input[0]<0,input[1]>0,input[1]<0,false,sneak);player.physics(mc.gameSettings);
                    if(Math.abs(player.posX-(.5+delta.x))>1e-6 || Math.abs(player.posZ-(-100.5+delta.z))>1e-6)
                        throw new AssertionError("ground input projection differs from native movement");
                }
                world.load(fixture);set(nav,"geometry",CollisionReplaySelfTest.capture(fixture));
                release.invoke(events);position(player,new Vec3Point(-10.72,68.5,-108.5),-90);player.motionX=.18;
                if(nav.safeCombatInput(-1,0,false,false,pit)) throw new AssertionError("retreat must not approve an eastward momentum fall");
                double startY=player.posY;
                for(int tick=0;tick<12;tick++) {
                    NearbySteering.Input brake=nav.combatBrake(pit);
                    if(!nav.safeCombatInput(brake.forward,brake.side,false,true,pit)) throw new AssertionError("roof brake requires a supported native sneak step");
                    move.invoke(get(events,"input"),brake.forward>0,brake.forward<0,brake.side>0,brake.side<0,false,true);
                    player.physics(mc.gameSettings);
                    if(!player.onGround || Math.abs(player.posY-startY)>.01) throw new AssertionError("native momentum brake fell off captured roof");
                }
                System.out.println("PASS: 10 native ground-input projections and 12 captured-roof momentum braking frames");
                release.invoke(events);
            }
        } finally {
            if(trace!=null) trace.close();
            if(channel!=null) channel.finish();singleton.set(null,old);OptimusMod.settings=oldSettings;
            Path report=Paths.get("diagnostics",reportName+".csv");Files.createDirectories(report.getParent());Files.write(report,rows,StandardCharsets.UTF_8);
            Files.write(Paths.get("diagnostics",reportName+"-summary.csv"),summary,StandardCharsets.UTF_8);
        }
        if(!failures.isEmpty()) throw new AssertionError("Native closed-loop routes failed: "+failures);
        System.out.println("PASS: native navigation replay ("+reportName+") through actual key handler and native physics");
    }
}