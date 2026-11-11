package dev.optimus.client;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatFocus;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatSpacing;
import dev.optimus.core.combat.CombatStrafe;
import dev.optimus.core.combat.CombatTap;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.combat.ComboEscape;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.MotionProgress;
import dev.optimus.core.navigation.PlatformJump;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.RunSafety;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.*;
import net.minecraft.network.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import sun.misc.Unsafe;

/** Actual fight/navigation handoffs in a disconnected native client shell. No sockets or game window. */
public final class CombatHandoffSelfTest {
    private static int checks;
    private static List<AxisAlignedBB> rayBoxes=Collections.emptyList();
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static Field field(Class<?> type,String name) throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField(name);f.setAccessible(true);return f; } catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object object,String name) throws Exception { return field(object.getClass(),name).get(object); }
    private static void set(Object object,String name,Object value) throws Exception { field(object.getClass(),name).set(object,value); }
    private static final class Player extends EntityPlayerSP {
        boolean sprint;int swings,locationRequests,closedMenus,lastClosedWindow;
        private Player() { super(null,null,null,null); }
        @Override public String getName() { return "OfflineRival"; }
        @Override public boolean isEntityAlive() { return true; }
        @Override public boolean canEntityBeSeen(Entity target) {
            return worldObj.rayTraceBlocks(new Vec3(posX,posY+getEyeHeight(),posZ),new Vec3(target.posX,target.posY+target.getEyeHeight(),target.posZ))==null;
        }
        @Override public void sendChatMessage(String message) { if(message.equals("/locraw")) locationRequests++; }
        @Override public void addChatMessage(IChatComponent message) { }
        @Override public void closeScreen() {
            closedMenus++;lastClosedWindow=openContainer.windowId;
            openContainer=inventoryContainer;Minecraft.getMinecraft().currentScreen=null;
        }
        @Override public float getEyeHeight() { return 1.62f; }
        @Override public boolean isPotionActive(Potion potion) { return false; }
        @Override public boolean isSprinting() { return sprint; }
        @Override public void setSprinting(boolean value) { sprint=value; }
        @Override public float getAIMoveSpeed() { return sprint?.13f:.1f; }
        @Override public void swingItem() { swings++; }
        @Override public AxisAlignedBB getEntityBoundingBox() { return new AxisAlignedBB(posX-.3,posY,posZ-.3,posX+.3,posY+1.8,posZ+.3); }
    }
    private static final class Controller extends PlayerControllerMP {
        int attacks,slotUpdates;
        private Controller() { super(null,null); }
        @Override public void attackEntity(EntityPlayer player,Entity target) { attacks++;player.setSprinting(false); }
        @Override public void updateController() { slotUpdates++; }
    }
    private static final class FloorBlock extends Block { private FloorBlock() { super(Material.rock); } }
    // Forge's launch-time event transformer supplies this override in the live
    // client. This disconnected fixture has no LaunchClassLoader or LWJGL window.
    private static final class TestMouseEvent extends MouseEvent {
        @Override public boolean isCancelable() { return true; }
    }
    private static final class World extends WorldClient {
        private static final IBlockState FLOOR=new FloorBlock().getDefaultState();
        private World() { super(null,null,0,EnumDifficulty.NORMAL,null); }
        @Override public MovingObjectPosition rayTraceBlocks(Vec3 from,Vec3 to) {
            for(AxisAlignedBB b:rayBoxes) { MovingObjectPosition hit=b.calculateIntercept(from,to);if(hit!=null) return hit; }
            return null;
        }
        @Override public boolean isBlockLoaded(BlockPos p) { return true; }
        @Override public IBlockState getBlockState(BlockPos p) { return FLOOR; }
    }
    private static void position(Player p,double x,double y,double z,boolean ground) {
        p.posX=p.prevPosX=x;p.posY=p.prevPosY=y;p.posZ=p.prevPosZ=z;p.onGround=ground;
    }
    private static void health(Player p,float value) { p.getDataWatcher().updateObject(6,Float.valueOf(value)); }
    private static void openMenu(Minecraft mc,Player player,int window) {
        GuiChest menu=new GuiChest(player.inventory,new InventoryBasic("Duels menu",false,9));
        menu.inventorySlots.windowId=window;player.openContainer=menu.inventorySlots;mc.currentScreen=menu;
    }
    public static void main(String[] args) throws Exception {
        Field singleton=field(Minecraft.class,"theMinecraft");Object old=singleton.get(null);
        ClientSettings oldSettings=OptimusMod.settings;
        ClientEvents oldEvents=OptimusMod.events;
        KeyBinding oldToggle=OptimusMod.toggleKey,oldStop=OptimusMod.stopKey;
        Unsafe unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        EmbeddedChannel channel=null;CombatTrace trace=null;
        try {
            Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);singleton.set(null,mc);
            mc.gameSettings=new GameSettings();mc.theWorld=(World)unsafe.allocateInstance(World.class);
            Player player=(Player)unsafe.allocateInstance(Player.class),rival=(Player)unsafe.allocateInstance(Player.class);
            mc.thePlayer=player;player.jumpMovementFactor=.02f;
            set(player,"foodStats",new FoodStats());
            for(Player p:Arrays.asList(player,rival)) {
                net.minecraft.entity.DataWatcher watcher=new net.minecraft.entity.DataWatcher(p);watcher.addObject(6,Float.valueOf(20));set(p,"dataWatcher",watcher);
                p.inventory=new InventoryPlayer(p);
                p.inventory.mainInventory[0]=new ItemStack(new ItemSword(Item.ToolMaterial.IRON));
            }
            player.worldObj=rival.worldObj=mc.theWorld;
            player.capabilities=new PlayerCapabilities();player.capabilities.allowFlying=true;
            set(mc.theWorld,"playerEntities",new ArrayList<EntityPlayer>(Arrays.asList(player,rival)));
            Controller controller=(Controller)unsafe.allocateInstance(Controller.class);mc.playerController=controller;
            set(mc,"currentServerData",new ServerData("Offline fixture","mc.hypixel.net",false));
            NetworkManager manager=new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient handler=(NetHandlerPlayClient)unsafe.allocateInstance(NetHandlerPlayClient.class);
            set(handler,"netManager",manager);set(player,"sendQueue",handler);
            OptimusMod.settings=new ClientSettings(Files.createTempDirectory("optimus-handoff").toFile());
            ClientSettings settings=OptimusMod.settings;PitRegion pit=settings.hypixelPit;
            pit.setCornerA(new Vec3Point(-10,0,-10));pit.setCornerB(new Vec3Point(20,10,20));
            settings.pitLobby="duelslobby9";settings.pitInstance="dynamiclobbyTEST";settings.pitEntrance=new Vec3Point(.5,1,.5);
            settings.whitelist.add("OfflineRival");settings.combatProfile=CombatProfile.BALANCED;settings.parkourEnabled=true;
            ClientEvents events=new ClientEvents();LobbyNavigator nav=(LobbyNavigator)get(events,"navigator");
            ((Random)get(get(events,"combat"),"random")).setSeed(settings.combatSeed);
            ((CombatTap)get(get(events,"combat"),"combatTap")).seed(settings.combatSeed^0x57415441504cL);
            ((ComboEscape)get(get(events,"combat"),"comboEscape")).seed(settings.combatSeed^0x434f4d424fL);
            ((CombatTurn)get(get(events,"combat"),"combatTurn")).seed(settings.combatSeed^0x5455524eL);
            CollisionGeometry geometry=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(y==0 || x==1 && z==0 && y==1) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            set(nav,"geometry",geometry);
            LobbySafety safety=(LobbySafety)get(events,"lobbySafety");
            TransitionWatch watch=new TransitionWatch(safety,manager);channel=new EmbeddedChannel(watch);set(manager,"channel",channel);
            set(events,"transitionWatch",watch);set(events,"sessionWorld",mc.theWorld);set(events,"sessionPlayer",player);set(events,"armed",true);
            long now=System.nanoTime()/1000000L;safety.request("mc.hypixel.net",mc.theWorld,now);
            expect(safety.confirm("mc.hypixel.net",mc.theWorld,now,"DUELS","duelslobby9","dynamiclobbyTEST",false),"disconnected fixture retains real lobby verification predicates");
            RunSafety run=(RunSafety)get(events,"run");run.startChecking(now);
            expect(run.activate("duelslobby9","dynamiclobbyTEST",mc.theWorld),"fixture run activates its pinned lobby identity");
            trace=new CombatTrace(settings.traceDirectory());set(events,"trace",trace);
            Method navigate=ClientEvents.class.getDeclaredMethod("navigateStep",Vec3Point.class,PitRegion.class,LobbyNavigator.Step.class);navigate.setAccessible(true);
            position(player,.5,1,.5,true);player.rotationYaw=0;player.sprint=true;set(events,"tickNo",10L);
            LobbyNavigator.Step align=nav.preparePlatform(new Vec3Point(1.5,2,.5),pit,10);
            expect(align!=null && align.alignOnly,"checked climb requests heading alignment");
            Object result=navigate.invoke(events,new Vec3Point(1.5,2,.5),pit,align);
            expect(result==LobbyNavigator.Status.MOVING && player.rotationYaw==0,"releasing inherited sprint preserves camera yaw when a sideways jump is available");
            expect(!mc.gameSettings.keyBindForward.isKeyDown() && !mc.gameSettings.keyBindJump.isKeyDown(),"heading ownership does not launch or walk before aligned");
            expect(!player.isSprinting(),"alignment clears inherited native sprint even before the bot owns that sprint state");
            LobbyNavigator.Step smallAlignment=new LobbyNavigator.Step(new Vec3Point(2,1,0),(PlatformJump.Plan)null);
            for(int tick=0;tick<20;tick++) navigate.invoke(events,new Vec3Point(2,1,0),pit,smallAlignment);
            expect(player.rotationYaw<0 && player.rotationYaw>=-22.5,"a requested precise adjustment turns only to the nearest WASD axis instead of stalling or facing the landing");
            expect(!mc.gameSettings.keyBindForward.isKeyDown() && !mc.gameSettings.keyBindJump.isKeyDown(),"precise adjustment retains released movement until a launch is checked");

            Method fight=CombatController.class.getDeclaredMethod("fight",PitRegion.class,Vec3Point.class);fight.setAccessible(true);
            CombatEquipment equipment=(CombatEquipment)get(get(events,"combat"),"combatEquipment");equipment.ready(player,48);
            GroundRoute terrain=new GroundRoute(geometry.cached(),pit,pit,null,1,true,true);
            // No damage is necessary: a nearby raised target must discard even an
            // opposing retained climb and an active detour before this tick's aim.
            nav.reset();position(player,.5,1,.5,true);position(rival,1.5,2,.5,true);player.rotationYaw=0;
            set(nav,"plannedTerrain",terrain);set(nav,"path",Arrays.asList(new GridPathfinder.Node(0,2,0),new GridPathfinder.Node(-1,4,0)));
            set(nav,"index",1);set(nav,"targetMode",true);set(nav,"plannedGoal",new Vec3Point(-.5,2,.5));
            set(get(events,"combat"),"turningTarget",rival);set(get(events,"combat"),"detourUntil",999L);set(events,"tickNo",50L);
            fight.invoke(get(events,"combat"),pit,new Vec3Point(player.posX,player.posY,player.posZ));
            expect(player.rotationYaw<0,"reachable nearby raised target owns immediate combat aim without waiting for damage");
            expect(((List<?>)get(nav,"path")).isEmpty() && get(nav,"search")==null,"nearby melee discards retained climbing waypoints and search");
            expect(!nav.jumpInProgress() && !nav.preparingGap() && !mc.gameSettings.keyBindJump.isKeyDown(),"in-reach raised target cannot trigger a fresh climb or runway");
            expect((long)get(get(events,"combat"),"detourUntil")==0,"an old detour cannot steal nearby melee priority");
            player.rotationYaw=-90;player.rotationPitch=0;set(events,"tickNo",51L);int beforeMelee=controller.attacks;
            Vec3 staleHead=player.getLookVec();
            expect(Math.abs(staleHead.xCoord)<.001 && staleHead.zCoord>.99,"native head yaw can lag the camera yaw changed by combat on this tick");
            fight.invoke(get(events,"combat"),pit,new Vec3Point(player.posX,player.posY,player.posZ));
            expect(controller.attacks==beforeMelee+1,"aligned in-reach target gets a native attack using current camera aim despite stale native head yaw");
            CombatTap actualTap=(CombatTap)get(get(events,"combat"),"combatTap");actualTap.seed(42);actualTap.observe(0,52,true,2,false,false);
            set(events,"tickNo",52L);player.sprint=true;int beforeNative=controller.attacks,beforeNativeSwings=player.swings;
            Method requestAttack=CombatController.class.getDeclaredMethod("requestAttack",EntityPlayer.class,CombatProfile.class);requestAttack.setAccessible(true);
            requestAttack.invoke(get(events,"combat"),rival,CombatProfile.BALANCED);
            expect(controller.attacks==beforeNative+1 && player.swings==beforeNativeSwings+1 && !player.isSprinting(),"verified sword attack adapter observes the ordinary native post-hit sprint reset");
            actualTap.observe(10,53,true,2,false,false);boolean outgoingTap=false;
            for(int tick=53;tick<=56;tick++) outgoingTap|=actualTap.input(tick,true,2,false,false)!=CombatTap.Input.NONE;
            expect(outgoingTap,"guarded sprint attack records pre-hit sprint even when the native attack clears it");
            LobbyNavigator.Step pending=nav.preparePlatform(new Vec3Point(1.5,2,.5),pit,60);
            expect(pending!=null && pending.jump,"fixture prepares a real grounded platform launch");
            nav.commit(pending,true,60);expect(nav.jumpInProgress(),"fixture has a pending committed launch");
            set(events,"tickNo",61L);fight.invoke(get(events,"combat"),pit,new Vec3Point(player.posX,player.posY,player.posZ));
            expect(!nav.jumpInProgress() && !mc.gameSettings.keyBindJump.isKeyDown(),"close melee cancels a committed launch before takeoff");
            nav.reset();position(player,.5,1,.5,true);player.motionX=player.motionZ=0;player.rotationYaw=-90;
            pending=nav.preparePlatform(new Vec3Point(1.5,2,.5),pit,70);
            set(nav,"plannedGoal",new Vec3Point(1.5,2,.5));nav.commit(pending,true,70);
            position(player,.67,1.75,.5,false);player.motionX=.08;player.motionY=.248;player.rotationPitch=0;
            set(events,"tickNo",72L);beforeMelee=controller.attacks;
            fight.invoke(get(events,"combat"),pit,new Vec3Point(player.posX,player.posY,player.posZ));
            expect(nav.jumpInProgress(),"already airborne movement retains its checked landing rather than dropping its controls");
            expect(controller.attacks==beforeMelee+1,"eligible close attack still executes during the checked airborne landing");

            // Actual guarded hits must benefit from faster acquisition, not just
            // an isolated angle model. Exercise both directions from 90 degrees.
            for(int side=0;side<2;side++) {
                nav.reset();((CombatFocus<?>)get(get(events,"combat"),"combatFocus")).reset();
                position(player,.5,1,.5,true);position(rival,2.5,1,.5,true);
                player.motionX=player.motionY=player.motionZ=0;player.rotationYaw=side==0?0:180;player.rotationPitch=0;
                set(get(events,"combat"),"turningTarget",null);int beforeAcquisition=controller.attacks,acquired=0;
                for(int frame=1;frame<=6;frame++) {
                    float oldYaw=player.rotationYaw,gap=CombatTurn.wrap(-90-oldYaw);
                    set(events,"tickNo",(long)(79+side*7+frame));fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
                    float movement=CombatTurn.wrap(player.rotationYaw-oldYaw);
                    expect(movement*gap>=0 && Math.abs(movement)<=CombatProfile.BALANCED.yawLimit,
                        "actual combat acquisition turns toward the target within its faster bound");
                    expect(!nav.jumpInProgress() && !mc.gameSettings.keyBindJump.isKeyDown(),"reachable acquisition retains combat without parkour");
                    if(controller.attacks>beforeAcquisition) { acquired=frame;break; }
                }
                expect(acquired>0,"actual 90-degree combat acquisition produces a guarded hit within six ticks");
            }

            // Retained elevated route points away from an in-reach attacker; hit response must win.
            nav.reset();position(player,.5,1,.5,true);position(rival,2.2,2,.5,true);player.rotationYaw=0;
            set(nav,"plannedTerrain",terrain);set(nav,"path",Arrays.asList(new GridPathfinder.Node(0,2,0),new GridPathfinder.Node(1,4,0)));
            set(nav,"index",1);set(nav,"targetMode",true);set(nav,"plannedGoal",new Vec3Point(rival.posX,rival.posY,rival.posZ));set(get(events,"combat"),"turningTarget",rival);
            CombatFocus<EntityPlayer> focus=(CombatFocus<EntityPlayer>)get(get(events,"combat"),"combatFocus");focus.observeDamage(20,0,99);
            health(player,19);player.hurtTime=10;set(events,"tickNo",100L);
            fight.invoke(get(events,"combat"),pit,new Vec3Point(player.posX,player.posY,player.posZ));
            expect(player.rotationYaw<0 && player.rotationYaw>=-32 && nav.activity.startsWith("Countering hit"),"grounded damage response counteraims immediately within its faster cap instead of following the retained climb");
            expect(!nav.jumpInProgress() && !mc.gameSettings.keyBindJump.isKeyDown(),"combo response does not launch a fresh platform jump");
            player.rotationYaw=-90;
            int escapeFrames=0,backstepFrames=0;
            for(int tick=101;tick<=130;tick++) {
                boolean hit=tick%6==0;player.hurtTime=hit?10:Math.max(0,player.hurtTime-1);if(hit) health(player,player.getHealth()-.2f);
                position(player,.5,tick%6==0?1:1.6,.5,tick%6==0);player.motionX=-.14;player.motionY=tick%3==0?-.2:.1;
                set(events,"tickNo",(long)tick);int before=controller.attacks;
                fight.invoke(get(events,"combat"),pit,new Vec3Point(player.posX,player.posY,player.posZ));
                expect(nav.activity.contains("aim/attacks active") && get(nav,"search")==null,"repeated grounded/airborne combo frames retain combat instead of path-search waits");
                expect(controller.attacks-before<=1,"combo response issues at most one native attack per tick");
                expect(!mc.gameSettings.keyBindJump.isKeyDown(),"countering uses ordinary movement without launching parkour");
                if(nav.activity.startsWith("Escaping combo")) {
                    escapeFrames++;
                    if(mc.gameSettings.keyBindBack.isKeyDown()) backstepFrames++;
                    expect(!mc.gameSettings.keyBindForward.isKeyDown() && !player.isSprinting(),"actual repeated-hit response stops straight-line pressure while preserving aim and attacks");
                    expect(mc.gameSettings.keyBindLeft.isKeyDown() || mc.gameSettings.keyBindRight.isKeyDown(),"safe open terrain permits sideways combo escape during ground/air response");
                }
            }
            expect(escapeFrames>=6 && backstepFrames>0,"actual combat handler attempts a brief back/side escape during a sustained combo");
            expect(controller.attacks>=6,"eligible in-reach counterattacks remain active throughout the combo");
            // Damage timer has expired, but an uncommitted airborne frame still cannot invoke A*.
            position(player,.5,1.8,.5,false);position(rival,4,1,.5,true);player.hurtTime=0;player.motionY=-.15;
            set(events,"tickNo",200L);fight.invoke(get(events,"combat"),pit,new Vec3Point(player.posX,player.posY,player.posZ));
            expect(nav.activity.startsWith("Airborne tracking") && get(nav,"search")==null,"airborne countertracking survives the short reaction timer without a route wait");
            expect(mc.gameSettings.keyBindForward.isKeyDown(),"safe air input continues closing after the reaction timer");
            expect(nav.safeKnockbackStep(new Vec3Point(.5,1.1,.5),new Vec3Point(.3,.85,.5),pit),"native ground clipping permits defensive input on the descending landing tick");
            expect(!nav.safeKnockbackStep(new Vec3Point(-9.6,1.1,.5),new Vec3Point(-10.2,1.2,.5),pit),"native air projection still refuses pit boundary escape");
            Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);release.invoke(events);

            // Exposed eyes/upper body behind a narrow raised obstruction: the old
            // fixed body point stayed blocked and discarded all recovery movement.
            List<CollisionGeometry.Box> cover=Arrays.asList(new CollisionGeometry.Box(1,1,0,2,2,1),
                new CollisionGeometry.Box(2,1,0,3,2,1),new CollisionGeometry.Box(1.4375,2,0,1.5625,3,1));
            rayBoxes=new ArrayList<>();
            for(CollisionGeometry.Box b:cover) rayBoxes.add(new AxisAlignedBB(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
            CollisionGeometry covered=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(y==0) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                for(CollisionGeometry.Box b:cover) if((int)Math.floor(b.minX)==x && (int)Math.floor(b.minY)==y && (int)Math.floor(b.minZ)==z) boxes.add(b);
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            set(nav,"geometry",covered);nav.reset();focus.reset();health(player,20);player.hurtTime=0;
            position(player,.5,1,.5,true);position(rival,2.5,2,.5,true);player.motionX=player.motionZ=0;
            player.rotationYaw=-90;player.rotationPitch=0;int beforeCover=controller.attacks;
            expect(player.canEntityBeSeen(rival),"cover fixture has visible opponent eyes");
            for(long tick=250;tick<290;tick++) { set(events,"tickNo",tick);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5)); }
            expect(controller.attacks>beforeCover && player.rotationPitch<0,"blocked preferred body point switches to exposed upper body and attacks");
            expect(!nav.jumpInProgress(),"an available exposed melee hit retains grounded combat priority");

            // Eyes hidden by overhead cover, with an exposed lower-body ray.
            rayBoxes=Arrays.asList(new AxisAlignedBB(1.4,2.4,0,1.8,3.3,1));
            nav.reset();focus.reset();health(player,20);player.hurtTime=0;
            position(player,.5,1,.5,true);position(rival,3,1,.5,true);
            player.motionX=player.motionY=player.motionZ=0;player.rotationYaw=-90;player.rotationPitch=0;
            expect(!player.canEntityBeSeen(rival),"overhead cover blocks eye-to-eye visibility");
            int beforeLowerBody=controller.attacks;
            for(long tick=290;tick<294;tick++) { set(events,"tickNo",tick);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5)); }
            expect(controller.attacks>beforeLowerBody && player.rotationPitch>0 && !nav.jumpInProgress(),
                "clear exposed lower body gets normal grounded aim and attacks despite hidden eyes");

            rayBoxes=Collections.emptyList();
            Player extra=(Player)unsafe.allocateInstance(Player.class);extra.worldObj=mc.theWorld;
            position(player,.5,1,.5,true);position(rival,.5,1,3,true);position(extra,.5,1,1.75,true);
            mc.theWorld.playerEntities.add(extra);settings.targetAll=true;focus.reset();
            Method nearest=CombatController.class.getDeclaredMethod("focusedOpponent",PitRegion.class,Vec3Point.class);nearest.setAccessible(true);
            set(events,"tickNo",294L);
            expect(nearest.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5))==extra,"all-target adapter initially focuses the nearest player");
            position(extra,.5,1,3.4,true);set(events,"tickNo",298L);
            expect(nearest.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5))==rival,"all-target adapter reselects the nearer rival after the brief commitment");
            settings.targetAll=false;position(extra,.5,1,1.75,true);
            expect(nearest.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5))==rival,"named mode continues retaining its eligible selected rival");

            // The private-server test behavior is intentional and must stay intact.
            Method strike=CombatController.class.getDeclaredMethod("strike",PitRegion.class,EntityPlayer.class,CombatProfile.class,
                Vec3Point.class,CollisionGeometry.Box.class,float.class,float.class,float.class,float.class);strike.setAccessible(true);
            Vec3Point view=new Vec3Point(.5,2.62,.5);CollisionGeometry.Box rivalBody=new CollisionGeometry.Box(.2,1,2.7,.8,2.8,3.3);
            CombatClicks preservedClicks=(CombatClicks)get(get(events,"combat"),"combatClicks");
            player.rotationYaw=player.rotationPitch=0;mc.objectMouseOver=new MovingObjectPosition(extra,new Vec3(.5,2.62,1.45));
            set(events,"tickNo",300L);preservedClicks.defer(299,1);int intentional=controller.attacks;
            strike.invoke(get(events,"combat"),pit,rival,CombatProfile.BALANCED,view,rivalBody,0f,0f,0f,0f);
            expect(controller.attacks==intentional+1,"intentional direct selected-target attack remains independent of a nearer entity pick");
            player.rotationYaw=13;set(events,"tickNo",301L);preservedClicks.defer(300,1);intentional=controller.attacks;
            strike.invoke(get(events,"combat"),pit,rival,CombatProfile.BASELINE,view,rivalBody,0f,0f,0f,0f);
            expect(controller.attacks==intentional+1 && CombatAim.hit(view,rivalBody,13,0,2.75)==null,
                "intentional baseline angular tolerance still allows a request when the body ray misses");
            mc.theWorld.playerEntities.removeIf(p->p==extra);mc.objectMouseOver=null;focus.reset();
            preservedClicks.defer(293,1);

            rayBoxes=Collections.emptyList();
            set(nav,"geometry",new CollisionGeometry((x,y,z)->y==0 && x<=0?new CollisionGeometry.Cell(true,false,
                Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1))):CollisionGeometry.Cell.AIR));
            nav.reset();focus.reset();position(player,1.25,1,.5,true);position(rival,1.9,1,.5,false);
            player.motionX=.18;player.motionZ=0;player.rotationYaw=-90;player.rotationPitch=0;set(events,"tickNo",295L);
            int beforeBrake=controller.attacks;fight.invoke(get(events,"combat"),pit,new Vec3Point(1.25,1,.5));
            expect(mc.gameSettings.keyBindSneak.isKeyDown() && mc.gameSettings.keyBindBack.isKeyDown() && !player.isSprinting(),"actual combat handler owns sneak and opposite input when retreat alone would carry momentum off a lip");
            expect(controller.attacks>beforeBrake,"ledge braking retains eligible close attacks");
            player.motionX=player.motionZ=0;
            CollisionGeometry flat=new CollisionGeometry((x,y,z)->y==0?new CollisionGeometry.Cell(true,false,
                Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1))):CollisionGeometry.Cell.AIR);
            set(nav,"geometry",flat);nav.reset();position(player,.5,1,.5,true);position(rival,7.5,1,.5,true);
            player.rotationYaw=-90;player.rotationPitch=0;set(get(events,"combat"),"turningTarget",null);set(get(events,"combat"),"detourUntil",320L);set(events,"tickNo",300L);
            fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect((long)get(get(events,"combat"),"detourUntil")==0 && nav.activity.startsWith("Following OfflineRival"),"new target immediately uses its clear direct corridor instead of inheriting the old detour");

            nav.reset();position(player,.5,1,.5,true);position(rival,2.5,1,.5,true);player.rotationYaw=-90;player.rotationPitch=0;set(events,"tickNo",330L);
            Vec3Point sideRoute=new Vec3Point(.5,1,1.5);
            result=navigate.invoke(events,sideRoute,pit,new LobbyNavigator.Step(LobbyNavigator.Status.MOVING,sideRoute,false));
            expect(result==LobbyNavigator.Status.MOVING && player.rotationYaw==-90 && mc.gameSettings.keyBindRight.isKeyDown()
                && !mc.gameSettings.keyBindForward.isKeyDown(),"small combat correction uses D while keeping the camera on the opponent east of the route");
            Method strikeMoving=CombatController.class.getDeclaredMethod("strikeWhileMoving",PitRegion.class,net.minecraft.entity.player.EntityPlayer.class,CombatProfile.class,boolean.class);strikeMoving.setAccessible(true);
            int beforeSide=controller.attacks;strikeMoving.invoke(get(events,"combat"),pit,rival,CombatProfile.BALANCED,false);
            expect(controller.attacks>beforeSide && mc.gameSettings.keyBindRight.isKeyDown(),"eligible attack continues while WASD movement follows a different direction");

            // Exercise the actual START/END handler: a periodic request must preserve
            // held keys, target/turn state, pacing, and eligible attacks while waiting.
            release.invoke(events);focus.reset();nav.reset();position(player,.5,1,.5,true);position(rival,3.3,1,.5,true);
            player.rotationYaw=-90;player.rotationPitch=0;set(events,"tickNo",400L);set(events,"enteredPit",true);
            set(events,"watchedManager",manager);OptimusMod.toggleKey=new KeyBinding("fixture toggle",0,"fixture");
            OptimusMod.stopKey=new KeyBinding("fixture stop",0,"fixture");
            fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect(mc.gameSettings.keyBindForward.isKeyDown(),"refresh fixture starts with owned combat movement");
            CombatClicks clicks=(CombatClicks)get(get(events,"combat"),"combatClicks");long nextAttack=(long)get(clicks,"nextAttack");
            now=System.nanoTime()/1000000L;long confirmed=now-LobbySafety.REFRESH_INTERVAL_MS-100;
            safety.request("mc.hypixel.net",mc.theWorld,confirmed-100);
            safety.confirm("mc.hypixel.net",mc.theWorld,confirmed,"DUELS","duelslobby9","dynamiclobbyTEST",false);
            int requests=player.locationRequests,beforeRefresh=controller.attacks;
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(player.locationRequests==requests+1 && safety.pending() && !safety.blocking(),"minute refresh sends one background locraw request");
            expect(mc.gameSettings.keyBindForward.isKeyDown() && get(get(events,"combat"),"turningTarget")==rival && (long)get(clicks,"nextAttack")==nextAttack,"background request preserves combat keys, target and attack cooldown");
            for(int tick=0;tick<6;tick++) {
                events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
                events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            }
            expect(controller.attacks>beforeRefresh && mc.gameSettings.keyBindForward.isKeyDown(),"combat attacks and movement continue on END ticks while locraw is pending");
            expect(player.locationRequests==requests+1,"pending background request does not repeat every tick");
            now=System.nanoTime()/1000000L;
            expect(safety.confirm("mc.hypixel.net",mc.theWorld,now,"DUELS","duelslobby9","dynamiclobbyTEST",false),"background reply retains the same pinned lobby");
            expect(!safety.refreshDue("mc.hypixel.net",mc.theWorld,now+59000),"reply schedules the next refresh a minute later");

            // Reproduce the repeated combat interruption through the real packet
            // observer and START/END handlers, not a direct fight-only call.
            ItemStack heldSword=player.getHeldItem();int combatSlot=player.inventory.currentItem;
            int beforeCorrections=controller.attacks,correctionRequests=player.locationRequests;
            long correctionSettle=(long)get(events,"settleUntil");
            for(int frame=0;frame<24;frame++) {
                net.minecraft.network.play.server.S08PacketPlayerPosLook correction=new net.minecraft.network.play.server.S08PacketPlayerPosLook(
                    frame%2==0?.03:.5,1,.5,-90,0,frame%2==0?EnumSet.allOf(net.minecraft.network.play.server.S08PacketPlayerPosLook.EnumFlags.class):
                    EnumSet.noneOf(net.minecraft.network.play.server.S08PacketPlayerPosLook.EnumFlags.class));
                channel.writeInbound(correction);
                expect(safety.verified("mc.hypixel.net",mc.theWorld,System.nanoTime()/1000000L),"position correction preserves verification before the client tick");
                long attackDue=(long)get(clicks,"nextAttack");
                events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
                expect(run.phase()==RunSafety.Phase.ACTIVE && player.getHeldItem()==heldSword && player.inventory.currentItem==combatSlot,
                    "combat position corrections never enter recovery or remove the sword");
                expect(player.locationRequests==correctionRequests && (long)get(events,"settleUntil")==correctionSettle
                    && (long)get(clicks,"nextAttack")==attackDue && get(get(events,"combat"),"turningTarget")==rival,
                    "correction START tick adds no verification, settle delay, pacing reset or target loss");
                events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
                expect(run.phase()==RunSafety.Phase.ACTIVE && mc.gameSettings.keyBindForward.isKeyDown(),"movement continues on every correction END tick");
                expect(channel.readInbound()==correction,"combat correction is forwarded unchanged rather than suppressed");
            }
            expect(controller.attacks>beforeCorrections && player.locationRequests==correctionRequests,"eligible attacks continue through repeated position corrections without extra locraw requests");

            // A refresh cannot interrupt an already airborne committed jump either.
            release.invoke(events);set(nav,"geometry",geometry);position(player,.5,1,.5,true);player.rotationYaw=-90;
            pending=nav.preparePlatform(new Vec3Point(1.5,2,.5),pit,500);
            set(nav,"plannedGoal",new Vec3Point(1.5,2,.5));nav.commit(pending,true,500);
            position(player,.67,1.75,.5,false);position(rival,1.5,2,.5,true);player.motionX=.08;player.motionY=.248;
            Method move=MovementInput.class.getDeclaredMethod("move",boolean.class,boolean.class,boolean.class,boolean.class,boolean.class);move.setAccessible(true);
            move.invoke(get(events,"input"),true,false,false,false,true);set(events,"tickNo",502L);
            now=System.nanoTime()/1000000L;confirmed=now-LobbySafety.REFRESH_INTERVAL_MS-100;
            safety.request("mc.hypixel.net",mc.theWorld,confirmed-100);safety.confirm("mc.hypixel.net",mc.theWorld,confirmed,"DUELS","duelslobby9","dynamiclobbyTEST",false);
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(nav.jumpInProgress() && mc.gameSettings.keyBindJump.isKeyDown(),"background refresh preserves airborne jump ownership at START");
            channel.writeInbound(new net.minecraft.network.play.server.S08PacketPlayerPosLook());
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(run.phase()==RunSafety.Phase.ACTIVE && nav.jumpInProgress() && mc.gameSettings.keyBindJump.isKeyDown()
                && CombatEquipment.sword(player.getHeldItem()),"position update during airborne pursuit keeps jump ownership and sword");
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            expect(nav.jumpInProgress() && mc.gameSettings.keyBindForward.isKeyDown(),"checked airborne landing continues while background verification waits");
            safety.request("mc.hypixel.net",mc.theWorld,System.nanoTime()/1000000L);
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            expect(!mc.gameSettings.keyBindForward.isKeyDown() && !mc.gameSettings.keyBindJump.isKeyDown(),"explicit/recovery verification still releases all gameplay input");

            now=System.nanoTime()/1000000L;
            safety.confirm("mc.hypixel.net",mc.theWorld,now,"DUELS","duelslobby9","dynamiclobbyTEST",false);
            set(nav,"geometry",flat);nav.reset();focus.reset();health(player,20);player.hurtTime=0;
            position(player,.5,1,-.28,true);position(rival,2.5,1,-.28,true);player.rotationYaw=-90;player.rotationPitch=0;
            player.motionX=player.motionY=player.motionZ=0;
            ComboEscape escape=(ComboEscape)get(get(events,"combat"),"comboEscape");escape.seed(4);
            set(get(events,"combat"),"turningTarget",rival);
            set(nav,"geometry",new CollisionGeometry((x,y,z)->y==0 && z>=0?new CollisionGeometry.Cell(true,false,
                Arrays.asList(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1))):CollisionGeometry.Cell.AIR));
            set(events,"tickNo",600L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,-.28));
            health(player,19);player.hurtTime=10;set(events,"tickNo",610L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,-.28));
            health(player,18);set(events,"tickNo",620L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,-.28));
            set(escape,"side",1);set(events,"tickNo",623L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,-.28));
            expect(nav.activity.startsWith("Escaping combo") && mc.gameSettings.keyBindRight.isKeyDown()
                && !mc.gameSettings.keyBindLeft.isKeyDown(),"actual combo response rejects the ledge-side preference and takes the safe opposite side");
            expect(nav.safeCombatInput(mc.gameSettings.keyBindBack.isKeyDown()?-1:0,-1,false,false,pit),"chosen escape remains safe with actual ground acceleration and coasting");
            expect(player.motionX==0 && player.motionZ==0 && !mc.gameSettings.keyBindJump.isKeyDown(),"defensive response uses ordinary keys without writing velocity or jumping");

            // Replay a close moving trade with two centimetres of range noise.
            // Inspect actual held keys, not only the spacing helper's decisions.
            release.invoke(events);set(nav,"geometry",flat);nav.reset();focus.reset();escape.reset();
            health(player,20);player.hurtTime=rival.hurtTime=0;set(get(events,"combat"),"turningTarget",rival);
            ((CombatSpacing)get(get(events,"combat"),"combatSpacing")).reset();((CombatStrafe)get(get(events,"combat"),"combatStrafe")).reset();
            ((CombatTap)get(get(events,"combat"),"combatTap")).reset();((MotionProgress)get(get(events,"combat"),"pursuitProgress")).reset();
            position(player,.5,1,.5,true);position(rival,3.2,1,.5,true);
            player.motionX=player.motionY=player.motionZ=0;player.rotationYaw=-90;player.rotationPitch=0;
            preservedClicks.defer(640,55);focus.attacked(640);set(events,"tickNo",640L);
            fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            boolean previousForward=mc.gameSettings.keyBindForward.isKeyDown();int keyChanges=0;
            for(int frame=1;frame<=48;frame++) {
                double oldSelf=player.posX,oldRival=rival.posX,x=.5+frame*.05,distance=frame%2==0?2.19:2.21;
                position(player,x,1,.5,true);position(rival,x+distance,1,.5,true);
                player.prevPosX=oldSelf;rival.prevPosX=oldRival;player.motionX=.0273;
                focus.attacked(640+frame);set(events,"tickNo",640L+frame);
                fight.invoke(get(events,"combat"),pit,new Vec3Point(x,1,.5));
                boolean forwardKey=mc.gameSettings.keyBindForward.isKeyDown();
                if(forwardKey!=previousForward) keyChanges++;previousForward=forwardKey;
                expect(!mc.gameSettings.keyBindBack.isKeyDown(),"native trade replay never reverses into S at ordinary contact range");
            }
            expect(keyChanges<=2,"actual fight adapter retains W through small range noise instead of pulsing every tick");
            preservedClicks.defer(689,1);

            // A weapon swap in the same slot must suppress both attacks and near-miss
            // animation until a real sword is available and its short settle has elapsed.
            release.invoke(events);set(nav,"geometry",flat);nav.reset();focus.reset();health(player,20);player.hurtTime=0;
            position(player,.5,1,.5,true);position(rival,2.5,1,.5,true);player.rotationYaw=-90;player.rotationPitch=0;
            ItemStack sword=player.inventory.mainInventory[0];ItemStack menuItem=new ItemStack(new Item());
            player.inventory.mainInventory[0]=menuItem;player.inventory.currentItem=0;
            int beforeSwap=controller.attacks,beforeSwapSwings=player.swings;
            set(events,"tickNo",700L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect(controller.attacks==beforeSwap && player.swings==beforeSwapSwings,"bot neither attacks nor swings a replacement hotbar menu item even in combat range");
            player.inventory.mainInventory[0]=new ItemStack(sword.getItem()).setStackDisplayName("\u00a7aDuels Menu (Right Click)");
            set(events,"tickNo",701L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect(controller.attacks==beforeSwap && player.swings==beforeSwapSwings,"a sword-shaped menu item is excluded from bot weapon selection and attacks");
            position(rival,3.5,1,.5,true);set(events,"tickNo",702L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect(player.swings==beforeSwapSwings,"near-miss swings also require the combat sword");
            player.inventory.mainInventory[3]=sword;position(rival,2.5,1,.5,true);
            set(events,"tickNo",703L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect(player.inventory.currentItem==3 && controller.attacks==beforeSwap,"bot selects an available sword and waits briefly after the slot change");
            set(events,"tickNo",705L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect(controller.attacks>beforeSwap,"normal guarded combat resumes after the weapon settles");

            OptimusMod.events=events;
            OptimusCommand commands=new OptimusCommand();
            commands.processRemote(player,new String[]{"weapon","fist"});
            expect(!settings.swordEnabled && player.inventory.currentItem==1 && player.getHeldItem()==null
                && (Boolean)get(events,"armed"),"fist command selects empty slot 2 without stopping the active run");
            settings.whitelist.clear();int beforeFist=controller.attacks;
            for(long tick=710;tick<715;tick++) { set(events,"tickNo",tick);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5)); }
            expect(controller.attacks==beforeFist,"named-target mode cannot attack an unlisted rival with fists");
            commands.processRemote(player,new String[]{"target","all"});
            for(long tick=715;tick<725;tick++) { set(events,"tickNo",tick);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5)); }
            expect(controller.attacks>beforeFist && player.inventory.currentItem==1 && player.getHeldItem()==null,
                "all-target mode selects and attacks an unlisted rival using native fist attacks");
            Method allowed=ClientEvents.class.getDeclaredMethod("targetAllowed",PitRegion.class,EntityPlayer.class);allowed.setAccessible(true);
            expect(!(Boolean)allowed.invoke(events,pit,player),"all-target mode still excludes self");
            position(rival,30,1,.5,true);expect(!(Boolean)allowed.invoke(events,pit,rival),"all-target mode still excludes players outside the pit");
            position(rival,2.5,1,.5,true);rival.worldObj=null;
            expect(!(Boolean)allowed.invoke(events,pit,rival),"all-target mode still excludes another world");rival.worldObj=mc.theWorld;
            player.inventory.mainInventory[1]=menuItem;int fistAttacks=controller.attacks,fistSwings=player.swings;
            for(long tick=725;tick<735;tick++) { set(events,"tickNo",tick);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5)); }
            expect(controller.attacks==fistAttacks && player.swings==fistSwings && player.inventory.currentItem==1,
                "fist mode never swings an item placed in slot 2 or silently switches back to a sword");
            player.inventory.mainInventory[1]=null;set(events,"tickNo",735L);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5));
            expect(controller.attacks==fistAttacks,"newly emptied fist slot waits for the ordinary equipment settle");
            for(long tick=737;tick<743;tick++) { set(events,"tickNo",tick);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5)); }
            expect(controller.attacks>fistAttacks,"fist attacks resume once empty slot 2 settles");
            commands.processRemote(player,new String[]{"weapon","sword"});int beforeSword=controller.attacks;
            expect(player.inventory.currentItem==3 && settings.swordEnabled,"sword command reselects the available real sword");
            for(long tick=745;tick<752;tick++) { set(events,"tickNo",tick);fight.invoke(get(events,"combat"),pit,new Vec3Point(.5,1,.5)); }
            expect(controller.attacks>beforeSword,"combat resumes with the selected sword");
            commands.processRemote(player,new String[]{"weapon","fist"});

            // Native queues are independent of bot movement: holding or queueing a
            // click before death used to survive into the lobby's replacement items.
            for(KeyBinding key:Arrays.asList(mc.gameSettings.keyBindAttack,mc.gameSettings.keyBindUseItem)) {
                KeyBinding.setKeyBindState(key.getKeyCode(),true);KeyBinding.onTick(key.getKeyCode());KeyBinding.onTick(key.getKeyCode());
            }
            health(player,0);int beforeDeath=controller.attacks,beforeDeathSwings=player.swings;
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(run.phase()==RunSafety.Phase.RECOVERING && player.getHeldItem()==null,"death releases combat state and selects an empty lobby hotbar slot");
            expect(!mc.gameSettings.keyBindAttack.isKeyDown() && !mc.gameSettings.keyBindUseItem.isKeyDown()
                && !mc.gameSettings.keyBindAttack.isPressed() && !mc.gameSettings.keyBindUseItem.isPressed(),"death drains held and queued attack/use input before vanilla click handling");
            expect(controller.attacks==beforeDeath && player.swings==beforeDeathSwings,"death cannot produce a final lobby swing or attack");
            for(int slot=0;slot<9;slot++) player.inventory.mainInventory[slot]=slot==4?null:menuItem;
            player.inventory.currentItem=3;health(player,20);position(player,-12,1,.5,true);
            channel.writeInbound(new net.minecraft.network.play.server.S07PacketRespawn());
            openMenu(mc,player,7);int closedMenus=player.closedMenus;
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),true);KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));set(events,"settleUntil",0L);
            expect(mc.currentScreen==null && player.closedMenus==closedMenus+1 && player.lastClosedWindow==7
                && player.openContainer==player.inventoryContainer && player.inventory.currentItem==4,
                "respawn START closes an already opened server menu through player.closeScreen and parks lobby items");
            expect(!mc.gameSettings.keyBindUseItem.isKeyDown() && !mc.gameSettings.keyBindUseItem.isPressed()
                && controller.attacks==beforeDeath && player.swings==beforeDeathSwings,"closing a respawn menu leaves no held/queued use or combat swing");
            openMenu(mc,player,8);
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            expect(mc.currentScreen==null && player.closedMenus==closedMenus+2 && player.lastClosedWindow==8
                && run.phase()==RunSafety.Phase.RECOVERING,"a menu arriving between tick phases also closes during recovery");
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(safety.pending() && safety.blocking() && player.inventory.currentItem==4,"respawn parks the new lobby items while requesting strict location verification");
            int respawnRequests=player.locationRequests;
            channel.writeInbound(new net.minecraft.network.play.server.S08PacketPlayerPosLook());
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(safety.pending() && safety.blocking() && player.locationRequests==respawnRequests,"respawn placement update cannot cancel or restart its pending location request");
            now=System.nanoTime()/1000000L;
            expect(safety.confirm("mc.hypixel.net",mc.theWorld,now,"DUELS","duelslobby9","dynamiclobbyTEST",false),"respawn test verifies the original exact lobby before allowing travel");
            boolean walking=false;
            for(int tick=0;tick<30 && !walking;tick++) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(),true);KeyBinding.onTick(mc.gameSettings.keyBindAttack.getKeyCode());
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),true);KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
                events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
                walking=mc.gameSettings.keyBindForward.isKeyDown() || mc.gameSettings.keyBindBack.isKeyDown()
                    || mc.gameSettings.keyBindLeft.isKeyDown() || mc.gameSettings.keyBindRight.isKeyDown();
            }
            expect(run.phase()==RunSafety.Phase.ACTIVE && walking,"same-lobby respawn resumes checked WASD travel toward the pit with a menu-filled hotbar");
            expect(player.getHeldItem()==null && controller.attacks==beforeDeath && player.swings==beforeDeathSwings,"return travel keeps the empty slot and never swings or attacks with lobby items");
            expect(!mc.gameSettings.keyBindUseItem.isKeyDown() && !mc.gameSettings.keyBindUseItem.isPressed(),"queued replacement-item uses stay drained throughout return travel");
            openMenu(mc,player,9);int lateRequests=player.locationRequests;
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            expect(mc.currentScreen==null && player.closedMenus==closedMenus+3 && player.lastClosedWindow==9
                && run.phase()==RunSafety.Phase.ACTIVE && player.locationRequests==lateRequests,
                "late respawn menu closes after verification while returning to the pit without restarting recovery");
            mc.currentScreen=new GuiInventory(player);
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(mc.currentScreen instanceof GuiInventory && player.closedMenus==closedMenus+3,"respawn return preserves the player's ordinary inventory screen");
            mc.currentScreen=new GuiChat();events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(mc.currentScreen instanceof GuiChat && player.closedMenus==closedMenus+3,"respawn return preserves chat editing");
            mc.currentScreen=null;
            player.inventory.mainInventory[4]=menuItem;
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            expect(run.phase()==RunSafety.Phase.ACTIVE && controller.attacks==beforeDeath && player.swings==beforeDeathSwings
                && !mc.gameSettings.keyBindUseItem.isKeyDown(),"a completely full lobby hotbar still permits return navigation without item interactions");
            MouseEvent mouse=(MouseEvent)unsafe.allocateInstance(TestMouseEvent.class);set(mouse,"button",0);set(mouse,"buttonstate",true);
            events.onMouse(mouse);expect(mouse.isCanceled(),"physical attack press is canceled before vanilla can swing a lobby item");
            mouse=(MouseEvent)unsafe.allocateInstance(TestMouseEvent.class);set(mouse,"button",1);set(mouse,"buttonstate",true);
            events.onMouse(mouse);expect(mouse.isCanceled(),"physical item-use press cannot open a lobby menu during bot travel");
            int oldAttackCode=mc.gameSettings.keyBindAttack.getKeyCode();mc.gameSettings.keyBindAttack.setKeyCode(47);KeyBinding.resetKeyBindingArrayAndHash();
            KeyBinding.setKeyBindState(47,true);KeyBinding.onTick(47);events.onKeyInput(new InputEvent.KeyInputEvent());
            expect(!mc.gameSettings.keyBindAttack.isKeyDown() && !mc.gameSettings.keyBindAttack.isPressed(),"keyboard-rebound attacks are drained before native click processing as well");
            mc.gameSettings.keyBindAttack.setKeyCode(oldAttackCode);KeyBinding.resetKeyBindingArrayAndHash();
            position(player,.5,1,.5,true);events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect((Boolean)get(events,"enteredPit") && !(Boolean)get(events,"returningAfterRespawn"),"safe pit re-entry ends automatic respawn menu cleanup");
            openMenu(mc,player,10);events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            expect(mc.currentScreen instanceof GuiChest && player.closedMenus==closedMenus+3,"normal combat menus are not swept closed by an old respawn");
            mc.currentScreen=null;player.openContainer=player.inventoryContainer;position(player,-12,1,.5,true);
            mc.currentScreen=new GuiScreen() { };
            mouse=(MouseEvent)unsafe.allocateInstance(TestMouseEvent.class);set(mouse,"button",0);events.onMouse(mouse);
            expect(!mouse.isCanceled(),"an open user interface still accepts mouse clicks while bot movement is paused");
            mc.currentScreen=null;set(events,"armed",false);
            openMenu(mc,player,11);set(events,"returningAfterRespawn",true);
            events.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            expect(mc.currentScreen instanceof GuiChest && player.closedMenus==closedMenus+3,"a stopped bot leaves server menus alone even with a pending respawn-return flag");
            mc.currentScreen=null;player.openContainer=player.inventoryContainer;
            mouse=(MouseEvent)unsafe.allocateInstance(TestMouseEvent.class);set(mouse,"button",1);events.onMouse(mouse);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),true);KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
            events.onKeyInput(new InputEvent.KeyInputEvent());
            expect(!mouse.isCanceled() && mc.gameSettings.keyBindUseItem.isKeyDown() && mc.gameSettings.keyBindUseItem.isPressed(),"stopped bot leaves normal manual attack/item-use controls available");
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(),false);

            UUID ownerId=UUID.fromString("00000000-0000-4000-8000-000000000001"),helperId=UUID.fromString("00000000-0000-4000-8000-000000000002");
            Map<UUID,net.minecraft.client.network.NetworkPlayerInfo> profiles=new HashMap<>();
            profiles.put(ownerId,new net.minecraft.client.network.NetworkPlayerInfo(new com.mojang.authlib.GameProfile(ownerId,"Main_Owner")));
            profiles.put(helperId,new net.minecraft.client.network.NetworkPlayerInfo(new com.mojang.authlib.GameProfile(helperId,"Helper_One")));
            set(handler,"playerInfoMap",profiles);settings.controllerName="Main_Owner";settings.controllerUuid=ownerId.toString();
            Method remote=ClientEvents.class.getDeclaredMethod("remoteChat",IChatComponent.class,byte.class);remote.setAccessible(true);
            remote.invoke(events,chat("Main_Owner","operator add Helper_One"),(byte)0);
            expect(settings.operatorMatches("Helper_One",helperId.toString()),"authenticated full controller grants operator using current server UUID");
            remote.invoke(events,chat("Helper_One","target add HelperRival"),(byte)0);
            expect(settings.whitelist.contains("HelperRival") && !settings.targetAll,"authenticated operator controls targets through real chat dispatch");
            remote.invoke(events,chat("Helper_One","weapon sword"),(byte)0);
            expect(settings.swordEnabled,"authenticated operator selects sword through real chat dispatch");
            remote.invoke(events,chat("Helper_One","weapon fist"),(byte)0);
            expect(!settings.swordEnabled,"authenticated operator selects fist through real chat dispatch");
            for(String forbidden:new String[]{"visuals on","operator add HelperRival","operator clear","profile baseline","say hello","status"}) {
                remote.invoke(events,chat("Helper_One",forbidden),(byte)0);
                expect(((String)get(events,"lastRemote")).startsWith("rejected:") && !settings.swordEnabled
                    && settings.combatProfile==CombatProfile.BALANCED && settings.operators.size()==1,"real operator dispatch denies "+forbidden);
            }
            remote.invoke(events,chat("Helper_One","startZ"),(byte)0);
            expect((Boolean)get(events,"startRequested"),"operator start alias queues the normal guarded remote start");
            remote.invoke(events,chat("Helper_One","stop"),(byte)0);
            expect(!(Boolean)get(events,"startRequested") && !(Boolean)get(events,"armed") && !(Boolean)get(events,"returningAfterRespawn"),
                "operator stop cancels queued starts, the run and respawn menu cleanup");
            profiles.put(helperId,new net.minecraft.client.network.NetworkPlayerInfo(new com.mojang.authlib.GameProfile(ownerId,"Helper_One")));
            remote.invoke(events,chat("Helper_One","target add SpoofRival"),(byte)0);
            expect(!settings.whitelist.contains("SpoofRival"),"same operator name with mismatched current UUID cannot issue commands");
            profiles.put(helperId,new net.minecraft.client.network.NetworkPlayerInfo(new com.mojang.authlib.GameProfile(helperId,"Helper_One")));
            remote.invoke(events,chat("Main_Owner","operator remove Helper_One"),(byte)0);
            remote.invoke(events,chat("Helper_One","target add RevokedRival"),(byte)0);
            expect(settings.operators.isEmpty() && !settings.whitelist.contains("RevokedRival"),"owner revocation takes effect on the next chat message");
        } finally { if(trace!=null) trace.close();if(channel!=null) channel.finish();singleton.set(null,old);OptimusMod.settings=oldSettings;OptimusMod.events=oldEvents;OptimusMod.toggleKey=oldToggle;OptimusMod.stopKey=oldStop;rayBoxes=Collections.emptyList(); }
        System.out.println("PASS: "+checks+" actual combat/navigation handoff and combo checks");
    }
    private static IChatComponent chat(String name,String command) {
        ChatComponentText sender=new ChatComponentText(name);
        sender.setChatStyle(new ChatStyle().setChatClickEvent(new net.minecraft.event.ClickEvent(
            net.minecraft.event.ClickEvent.Action.RUN_COMMAND,"/viewprofile "+name)));
        return new ChatComponentText("").appendSibling(sender).appendText(": !o "+command);
    }
}