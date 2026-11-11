package dev.optimus.client;

import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTap;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.NearbySteering;
import dev.optimus.core.navigation.PlatformJump;
import dev.optimus.core.navigation.SprintGap;
import dev.optimus.core.navigation.SprintJump;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.world.EnumDifficulty;
import sun.misc.Unsafe;

/** Runs the actual navigator/key ownership offline. Uninitialized vanilla shells open no window/network. */
public final class LobbyMovementSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static final class Player extends EntityPlayerSP {
        boolean sprint;
        int swings;
        private Player() { super(null,null,null,null); }
        @Override public boolean isPotionActive(Potion potion) { return false; }
        @Override public float getEyeHeight() { return 1.62f; }
        @Override public float getAIMoveSpeed() { return sprint?.13f:.1f; }
        @Override public boolean isSprinting() { return sprint; }
        @Override public void setSprinting(boolean value) { sprint=value; }
        @Override public void swingItem() { swings++; }
    }
    private static final class Controller extends net.minecraft.client.multiplayer.PlayerControllerMP {
        int attacks;
        private Controller() { super(null,null); }
        @Override public void attackEntity(net.minecraft.entity.player.EntityPlayer attacker,net.minecraft.entity.Entity target) {
            attacks++;attacker.setSprinting(false);
        }
    }
    private static final class World extends WorldClient {
        private static final IBlockState FLOOR=new FloorBlock().getDefaultState();
        private World() { super(null,null,0,EnumDifficulty.NORMAL,null); }
        @Override public MovingObjectPosition rayTraceBlocks(Vec3 from,Vec3 to) { return null; }
        @Override public IBlockState getBlockState(BlockPos pos) { return FLOOR; }
        @Override public boolean isBlockLoaded(BlockPos pos) { return true; }
    }
    private static final class FloorBlock extends Block { private FloorBlock() { super(Material.rock); } }
    private static void set(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static Object get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static CollisionGeometry floor(boolean block) {
        return new CollisionGeometry((x,y,z)-> {
            List<CollisionGeometry.Box> boxes=new ArrayList<>();
            if(y==0 || (block && x==1 && z==0 && y==1)) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
            return new CollisionGeometry.Cell(true,false,boxes);
        });
    }
    private static void position(Player player,double x,double y,double z,boolean ground) {
        player.posX=x;player.posY=y;player.posZ=z;player.onGround=ground;
    }
    public static void main(String[] args) throws Exception {
        Field singleton=Minecraft.class.getDeclaredField("theMinecraft");singleton.setAccessible(true);
        Object oldSingleton=singleton.get(null);ClientSettings oldSettings=OptimusMod.settings;ClientEvents oldEvents=OptimusMod.events;
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);Unsafe unsafe=(Unsafe)field.get(null);
        Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);
        Player player=(Player)unsafe.allocateInstance(Player.class);mc.thePlayer=player;
        player.jumpMovementFactor=.02f;
        mc.theWorld=(World)unsafe.allocateInstance(World.class);
        mc.gameSettings=new GameSettings();singleton.set(null,mc);
        OptimusMod.settings=new ClientSettings(Files.createTempDirectory("optimus-movement-test").toFile());
        PitRegion area=OptimusMod.settings.hypixelPit;area.setCornerA(new Vec3Point(-5,0,-5));area.setCornerB(new Vec3Point(20,10,10));
        OptimusMod.settings.parkourEnabled=true;
        try {
            LobbyNavigator nav=new LobbyNavigator(mc);CollisionGeometry clear=floor(false);set(nav,"geometry",clear);
            position(player,.5,1,.5,true);Vec3Point goal=new Vec3Point(12.5,1,.5);
            LobbyNavigator.Step initial=nav.step(goal,area,false,1);
            for(int tick=2;initial.status==LobbyNavigator.Status.WAITING && tick<40;tick++) initial=nav.step(goal,area,false,tick);
            expect(initial.status==LobbyNavigator.Status.MOVING && initial.sprint,"actual navigator starts a safe sprint route: "+initial.status+" / "+nav.activity);
            SprintJump.Plan plan=SprintJump.plan(clear,area,new Vec3Point(.5,1,.5),goal,.153,.1274,.026,.546,false);
            expect(plan!=null,"ordinary native sprint motion can start a flat-ground jump");
            nav.commit(new LobbyNavigator.Step(LobbyNavigator.Status.MOVING,new Vec3Point(3.5,1,.5),true,false,true,plan),true,100);
            LobbyNavigator.Step takeoff=nav.step(goal,area,false,101);
            expect(takeoff.status==LobbyNavigator.Status.MOVING && takeoff.jump && takeoff.sprint,"takeoff wait preserves forward/sprint/jump intent");
            List<Vec3Point> arc=SprintJump.samples(new Vec3Point(.5,1,.5),1,0,.153,.1274,.026,.546);
            Vec3Point p=arc.get(2);position(player,p.x,p.y,p.z,false);
            player.motionX=.23;player.motionY=.2;
            LobbyNavigator.Step flight=nav.step(goal,area,false,102);
            expect(flight.status==LobbyNavigator.Status.MOVING && flight.sprint,"actual airborne continuation retains sprint");
            p=arc.get(arc.size()-1);position(player,p.x,1,p.z,true);player.motionX=.15;player.motionY=0;
            LobbyNavigator.Step landing=nav.step(goal,area,false,112);
            expect(!nav.jumpInProgress() && landing.status==LobbyNavigator.Status.MOVING && landing.sprint,
                "landing resumes the retained checked route without a sprint-release search wait");
            GridPathfinder.Terrain synthetic=new GridPathfinder.Terrain() {
                public boolean standable(GridPathfinder.Node n) { return n.y2==2; }
                public boolean traversable(GridPathfinder.Node a,GridPathfinder.Node b) { return true; }
            };
            GridPathfinder.Goal unreachable=new GridPathfinder.Goal() {
                public boolean reached(GridPathfinder.Node n) { return false; }
                public double estimate(GridPathfinder.Node n) { return 0; }
            };
            set(nav,"search",new GridPathfinder.Search(new GridPathfinder.Node(0,2,0),synthetic,unreachable,65536));
            LobbyNavigator.Step replacing=nav.step(goal,area,false,113);
            expect(get(nav,"search")!=null && replacing.status==LobbyNavigator.Status.MOVING,
                "actual pending replacement search follows its already completed safe route");
            nav.reset();set(nav,"geometry",floor(true));position(player,.5,1,.5,true);
            player.rotationYaw=-90;player.motionX=player.motionY=player.motionZ=0;
            Vec3Point blockGoal=new Vec3Point(1.5,2,.5);LobbyNavigator.Step climb=nav.step(blockGoal,area,false,200);
            for(int tick=201;climb.status==LobbyNavigator.Status.WAITING && tick<220;tick++) climb=nav.step(blockGoal,area,false,tick);
            expect(climb.status==LobbyNavigator.Status.MOVING && climb.jump && climb.normalClimb && climb.platformLeap==null,
                "actual adjacent full-block route requests a normal hop without a full parkour plan");
            nav.commit(climb,true,200);expect(nav.jumpInProgress(),"full-block jump retains its landing commitment");
            expect(nav.step(blockGoal,area,false,201).jump,"unstarted block jump briefly retains takeoff input");
            position(player,.7,1.75,.5,false);
            expect(nav.step(blockGoal,area,false,202).status==LobbyNavigator.Status.MOVING,"block-side contact does not discard the rise target");
            position(player,1.5,2,.5,true);
            player.motionX=player.motionY=player.motionZ=0;
            expect(nav.step(blockGoal,area,false,210).status==LobbyNavigator.Status.ARRIVED && !nav.jumpInProgress(),
                "actual supported full-block landing completes its commitment");
            nav.reset();position(player,.5,1,.5,true);
            // Excess inherited motion still needs the checked platform preparation.
            // An ordinary slow adjacent climb can now jump without aligning first.
            player.rotationYaw=0;player.motionX=.21;
            for(int tick=1000;tick<=1024;tick++) {
                LobbyNavigator.Step stalled=nav.nearbyRise(blockGoal,area,tick);
                expect(stalled!=null,"repeated nearby selection preserves the same preparation progress window until launch is actually committed");
            }
            expect(nav.nearbyRise(blockGoal,area,1025)==null && nav.activity.contains("stalled"),"actual repeated climb selection exits a stalled alignment");
            expect(nav.nearbyRise(blockGoal,area,1026)==null,"replanning cannot immediately select the same failed climb edge");
            nav.reset();player.rotationYaw=-90;player.motionX=0;
            LobbyNavigator.Step fastRise=nav.nearbyRise(blockGoal,area,220);
            expect(fastRise!=null && fastRise.jump && get(nav,"search")==null,"nearby full-block rise is selected without starting A*");
            nav.commit(fastRise,true,220);
            expect(nav.jumpInProgress(),"fast nearby rise uses the same checked landing commitment");
            nav.reset();set(nav,"geometry",clear);position(player,.5,1,.5,true);player.rotationYaw=-90;
            expect(nav.simplePursuit(new NearbySteering(),new Vec3Point(4.5,1,.5),area,230)==NearbySteering.Input.FORWARD
                && get(nav,"search")==null,"actual close-pursuit adapter provides input without a search");
            for(int tick=300;tick<=310;tick++) nav.attemptedMovement(true,tick);
            LobbyNavigator.Step escape=nav.step(goal,area,false,311);
            expect(escape.status==LobbyNavigator.Status.MOVING && escape.input!=null && escape.input.forward<0,
                "actual stationary navigation recovery requests a checked back-out input before searching");
            expect(get(nav,"search")==null,"corner back-out does not wait for a full route search");
            CollisionGeometry gapGeometry=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(y==0 && z==0 && (x<=0 || x==3)) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            nav.reset();set(nav,"geometry",gapGeometry);position(player,.5,1,.5,true);player.sprint=false;player.hurtTime=0;
            Vec3Point gapGoal=new Vec3Point(3.5,1,.5);
            LobbyNavigator.Step lineup=nav.nearbyGap(gapGoal,area,400);
            expect(lineup!=null && lineup.brake && nav.preparingGap(),"actual nearby gap selection begins a checked backwards run-up alignment");
            expect(get(nav,"search")==null,"nearby gap lining-up has no full search wait");
            SprintGap.Candidate gapCandidate=(SprintGap.Candidate)get(nav,"gapPreparing");
            set(nav,"gapRunReady",true);position(player,gapCandidate.takeoff.x,1,.5,true);player.sprint=true;player.motionX=.153;player.motionZ=0;
            player.rotationYaw=-90;player.jumpMovementFactor=(float)(.026/.98);
            LobbyNavigator.Step launch=nav.nearbyGap(gapGoal,area,401);
            expect(launch!=null && launch.jump && launch.sprint && launch.gapLeap!=null,"actual native speed/friction preparation returns a sprint-gap launch");
            nav.commit(launch,true,401);expect(nav.jumpInProgress(),"sprint-gap takeoff commits its own arc and landing");
            List<Vec3Point> gapArc=SprintGap.samples(gapCandidate.takeoff,1,1,0,.153,.1274,.026,.546);
            Vec3Point gapMid=gapArc.get(2);position(player,gapMid.x,gapMid.y,gapMid.z,false);
            player.motionX=gapArc.get(3).x-gapMid.x-.026;player.motionY=gapArc.get(3).y-gapMid.y;
            LobbyNavigator.Step gapFlight=nav.step(gapGoal,area,true,403);
            expect(gapFlight.status==LobbyNavigator.Status.MOVING && gapFlight.sprint && gapFlight.jump && nav.holdGapJump(),
                "actual flight keeps normal sprint/forward/jump intent over missing ground");
            Vec3Point gapEnd=gapArc.get(gapArc.size()-1);position(player,gapEnd.x,1,gapEnd.z,true);player.motionX=.2;player.motionY=0;
            LobbyNavigator.Step gapBrake=nav.step(gapGoal,area,true,414);
            expect(gapBrake.status==LobbyNavigator.Status.MOVING && gapBrake.brake && !gapBrake.jump && !nav.holdGapJump(),
                "supported landing releases jump and brakes excess forward momentum");
            player.motionX=.04;nav.step(gapGoal,area,true,415);
            expect(!nav.jumpInProgress(),"safe slowed landing ends sprint-gap commitment");
            double velocity=player.motionX;nav.reset();expect(player.motionX==velocity,"jump cancellation never rewrites ordinary knockback velocity");
            set(nav,"geometry",gapGeometry);position(player,.5,1,.5,true);player.sprint=false;player.motionX=player.motionZ=0;
            for(int tick=900;tick<=924;tick++) expect(nav.nearbyGap(gapGoal,area,tick)!=null,"gap run-up keeps its bounded preparation history");
            expect(nav.nearbyGap(gapGoal,area,925)==null && nav.activity.contains("stalled"),"stuck sprint-gap run-up exits instead of silently selecting the same runway again");
            expect(nav.nearbyGap(gapGoal,area,926)==null,"failed gap landing stays excluded during immediate replanning");
            CollisionGeometry elevated=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(z==0 && ((y==0 && x<=2) || (y==1 && x>=3))) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            nav.reset();set(nav,"geometry",elevated);position(player,.5,1,.5,true);player.motionX=player.motionY=player.motionZ=0;player.sprint=false;
            Vec3Point elevatedGoal=new Vec3Point(3.9,2,.5);
            expect(!nav.simplePursuitAllowed(elevatedGoal,area),"3.4-block chase to a raised platform cannot enter ground-only side-step mode");
            position(player,2.5,1,.5,true);
            expect(nav.step(elevatedGoal,area,true,500).status!=LobbyNavigator.Status.ARRIVED,"one block below a close opponent is not route arrival");
            nav.reset();position(player,.5,1,.5,true);
            int elevationJumps=0;LobbyNavigator.Step elevatedStep=null;
            for(int tick=501;tick<550;tick++) {
                elevatedStep=nav.step(elevatedGoal,area,true,tick);
                if(elevatedStep.status==LobbyNavigator.Status.ARRIVED) break;
                expect(elevatedStep.status!=LobbyNavigator.Status.FAILED,"raised chase route remains checked and usable");
                if(elevatedStep.status!=LobbyNavigator.Status.MOVING) continue;
                Vec3Point testLanding=elevatedStep.platformLeap==null?elevatedStep.waypoint:elevatedStep.platformLeap.landing;
                if(elevatedStep.jump) {
                    elevationJumps++;nav.commit(elevatedStep,true,tick);
                    position(player,(player.posX+testLanding.x)/2,player.posY+1.1,.5,false);
                    expect(nav.step(elevatedGoal,area,true,++tick).status!=LobbyNavigator.Status.FAILED,"raised block chase continues through flight");
                }
                position(player,testLanding.x,testLanding.y,testLanding.z,true);
            }
            expect(elevationJumps==1 && player.posY==2 && elevatedStep.status==LobbyNavigator.Status.ARRIVED,
                "actual navigator walks to and jumps onto the one-block-high target platform");
            CollisionGeometry stairBlocks=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();int top=x<=0?0:Math.min(3,x);
                if(z==0 && y==top) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            nav.reset();set(nav,"geometry",stairBlocks);position(player,.5,1,.5,true);
            Vec3Point chainGoal=new Vec3Point(3.9,4,.5);int blockJumps=0;
            LobbyNavigator.Step chain=nav.nearbyRise(chainGoal,area,600);
            expect(chain!=null && chain.jump,"local climb helper can make a checked first rise toward a higher multi-step goal");
            for(int tick=600;tick<650;tick++) {
                if(chain.status==LobbyNavigator.Status.ARRIVED) break;
                expect(chain.status!=LobbyNavigator.Status.FAILED,"successive climb retains a checked route");
                if(chain.status==LobbyNavigator.Status.MOVING) {
                    Vec3Point testLanding=chain.platformLeap==null?chain.waypoint:chain.platformLeap.landing;
                    if(chain.jump) {
                        blockJumps++;nav.commit(chain,true,tick);
                        position(player,(player.posX+testLanding.x)/2,player.posY+1.1,.5,false);
                        nav.step(chainGoal,area,true,++tick);
                    }
                    position(player,testLanding.x,testLanding.y,testLanding.z,true);
                }
                chain=nav.step(chainGoal,area,true,++tick);
                if(chain.status==LobbyNavigator.Status.MOVING && chain.jump)
                    expect(!nav.simplePursuitAllowed(chainGoal,area),"near target cannot steal a retained climbing route");
            }
            expect(blockJumps==3 && player.posY==4 && chain.status==LobbyNavigator.Status.ARRIVED,"actual navigator chains three supported full-block climbs");
            CollisionGeometry gapChain=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(z==0 && ((x<=0 && y==0) || ((x==2 || x==3) && y==1) || (x>=6 && y==2)))
                    boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            nav.reset();set(nav,"geometry",gapChain);position(player,.5,1,.5,true);player.motionX=0;
            Vec3Point gapChainGoal=new Vec3Point(6.5,3,.5);
            expect(!nav.simplePursuitAllowed(gapChainGoal,area),"raised gap sequence requires navigation");
            for(int hop=0;hop<2;hop++) {
                int tick=700+hop*20;
                LobbyNavigator.Step prepare=nav.nearbyGap(gapChainGoal,area,tick);
                for(int align=0;prepare==null && align<5;align++) {
                    LobbyNavigator.Step connector=nav.step(gapChainGoal,area,true,++tick);
                    expect(connector.status==LobbyNavigator.Status.MOVING && connector.waypoint!=null
                        && nav.autoStep(new Vec3Point(player.posX,player.posY,player.posZ),connector.waypoint,area),
                        "chain follows its checked intermediate-platform walk to the next takeoff");
                    position(player,connector.waypoint.x,connector.waypoint.y,connector.waypoint.z,true);
                    prepare=nav.nearbyGap(gapChainGoal,area,++tick);
                }
                expect(prepare!=null && nav.preparingGap(),"next raised gap can be prepared after the previous supported landing: hop="+hop+" pos="+player.posX+":"+player.posY+" activity="+nav.activity);
                SprintGap.Candidate candidate=(SprintGap.Candidate)get(nav,"gapPreparing");
                double speed=candidate.emptyBlocks==1?.128:.153;
                position(player,candidate.takeoff.x,candidate.takeoff.y,candidate.takeoff.z,true);set(nav,"gapRunReady",true);
                player.sprint=true;player.motionX=speed;player.motionY=player.motionZ=0;player.rotationYaw=-90;
                LobbyNavigator.Step jump=nav.nearbyGap(gapChainGoal,area,tick+1);
                expect(jump!=null && jump.gapLeap!=null && jump.jump,"native takeoff parameters commit the next upward gap");
                nav.commit(jump,true,tick+1);
                List<Vec3Point> chainArc=SprintGap.samples(candidate.takeoff,candidate.landing.y,1,0,speed,.1274,.026,.546);
                Vec3Point mid=chainArc.get(3);position(player,mid.x,mid.y,mid.z,false);
                player.motionX=chainArc.get(4).x-mid.x-.026;player.motionY=chainArc.get(4).y-mid.y;
                expect(nav.step(gapChainGoal,area,true,tick+4).status==LobbyNavigator.Status.MOVING,"raised chained gap holds its flight inputs");
                Vec3Point end=chainArc.get(chainArc.size()-1);position(player,end.x,end.y,end.z,true);player.motionX=.04;player.motionY=0;
                nav.step(gapChainGoal,area,true,tick+12);
                expect(!nav.jumpInProgress(),"supported raised testLanding releases the old commitment for the following hop");
            }
            expect(player.posY==3,"actual navigator can commit and land a pair of upward gap jumps");
            nav.reset();set(nav,"geometry",clear);position(player,.5,1,.5,true);
            nav.step(new Vec3Point(6.5,1,.5),area,true,800);
            position(player,5.5,1.03,.5,false);
            expect(nav.step(new Vec3Point(6.5,1,.5),area,true,801).status!=LobbyNavigator.Status.ARRIVED
                && !((List<?>)get(nav,"path")).isEmpty(),"near-floor airborne pursuit retains its checked landing instead of claiming arrival");
            CollisionGeometry airControlBoundary=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(y==0) boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                if(x==2 && y==2 && z==0) boxes.add(new CollisionGeometry.Box(2.035,2,0,2.06,4,1));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            nav.reset();set(nav,"geometry",airControlBoundary);position(player,.5,1,.5,true);
            nav.commit(new LobbyNavigator.Step(LobbyNavigator.Status.MOVING,new Vec3Point(3.5,1,.5),true,false,true,plan),true,802);
            position(player,1.71,2,.5,false);player.motionX=player.motionY=player.motionZ=0;player.jumpMovementFactor=.02f;
            expect(nav.step(goal,area,false,803).status==LobbyNavigator.Status.MOVING,
                "flight projects actual native air input instead of falsely intersecting a nearby wall with a hard-coded increment");
            player.jumpMovementFactor=(float)(.026/.98);
            CollisionGeometry changingGoal=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if((x<=0 && z<=0 && y==0) || (x==3 && z==0 && y==0) || (x==0 && z==3 && y==1))
                    boxes.add(new CollisionGeometry.Box(x,y,z,x+1,y+1,z+1));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            nav.reset();set(nav,"geometry",changingGoal);position(player,.5,1,.5,true);player.motionX=0;player.sprint=false;
            expect(nav.nearbyGap(new Vec3Point(3.5,1,3.5),area,810)!=null,"first target height produces a checked gap preparation");
            SprintGap.Candidate firstHeight=(SprintGap.Candidate)get(nav,"gapPreparing");
            expect(firstHeight.dx==1 && firstHeight.dz==0,"lower destination prefers the level gap");
            expect(nav.nearbyGap(new Vec3Point(3.5,2,3.5),area,811)!=null,"vertical target update reselects a checked gap");
            SprintGap.Candidate nextHeight=(SprintGap.Candidate)get(nav,"gapPreparing");
            expect(nextHeight.dx==0 && nextHeight.dz==1,"height-only target change refreshes the preparation toward the raised landing");
            CollisionGeometry diagonalSlabs=new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                if(x<=0 && z<=0 && y==0) boxes.add(new CollisionGeometry.Box(x,0,z,x+1,.5,z+1));
                if(x==1 && z==1 && y==1) boxes.add(new CollisionGeometry.Box(1,1,1,2,1.5,2));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
            nav.reset();set(nav,"geometry",diagonalSlabs);position(player,.5,.5,.5,true);player.sprint=false;
            player.motionX=player.motionY=player.motionZ=0;player.jumpMovementFactor=.02f;player.rotationYaw=0;
            Vec3Point slabLanding=new Vec3Point(1.5,1.5,1.5);
            LobbyNavigator.Step align=nav.preparePlatform(slabLanding,area,850);
            expect(align!=null && align.platformLeap!=null && align.jump && player.rotationYaw==0,"diagonal takeoff uses W+A without turning the camera to face its landing");
            player.rotationYaw=-45;
            LobbyNavigator.Step slabLaunch=nav.preparePlatform(slabLanding,area,851);
            expect(slabLaunch!=null && slabLaunch.platformLeap!=null && slabLaunch.jump,"actual navigator selects a normal diagonal jump from a bottom slab");
            PlatformJump.Plan slabPlan=slabLaunch.platformLeap;
            List<Vec3Point> slabArc=PlatformJump.samples(diagonalSlabs,area,slabPlan,0,0,.098,.0196,.546,0);
            nav.commit(slabLaunch,true,850);
            for(int frame=1;frame<slabArc.size()-1;frame++) {
                Vec3Point here=slabArc.get(frame),after=slabArc.get(frame+1);position(player,here.x,here.y,here.z,false);
                double input=frame<slabPlan.forwardTicks?.0196:-.0196;
                player.motionX=after.x-here.x-slabPlan.dx*input;player.motionZ=after.z-here.z-slabPlan.dz*input;player.motionY=after.y-here.y;
                LobbyNavigator.Step flying=nav.step(slabLanding,area,true,850+frame);
                expect(flying.status==LobbyNavigator.Status.MOVING && flying.brake==(frame>=slabPlan.forwardTicks),"diagonal flight retains its checked forward/back phase");
                expect(Math.abs((flying.waypoint.x-here.x)/3-slabPlan.dx)<1e-7
                    && Math.abs((flying.waypoint.z-here.z)/3-slabPlan.dz)<1e-7,"diagonal flight heading remains aimed along both axes toward the landing");
            }
            Vec3Point slabEnd=slabArc.get(slabArc.size()-1);position(player,slabEnd.x,slabEnd.y,slabEnd.z,true);player.motionX=player.motionY=player.motionZ=0;
            expect(nav.step(slabLanding,area,true,865).status==LobbyNavigator.Status.ARRIVED && !nav.jumpInProgress(),"actual diagonal jump completes on the target slab's half-height top");
            ClientEvents events=new ClientEvents();Method sprint=MovementInput.class.getDeclaredMethod("sprint",boolean.class);sprint.setAccessible(true);
            Controller controller=(Controller)unsafe.allocateInstance(Controller.class);mc.playerController=controller;
            CombatTap actualTap=(CombatTap)get(get(events,"combat"),"combatTap");actualTap.seed(42);actualTap.observe(0,900,true,2,false,false);
            set(events,"tickNo",900L);player.sprint=true;
            Method attack=CombatController.class.getDeclaredMethod("requestAttack",net.minecraft.entity.player.EntityPlayer.class,CombatProfile.class);attack.setAccessible(true);
            attack.invoke(get(events,"combat"),player,CombatProfile.BALANCED);
            expect(controller.attacks==0 && player.swings==0,"unarmed/self-target request is rejected before the native attack adapter");
            actualTap.observe(10,901,true,2,false,false);boolean tapObserved=false;
            for(int tick=901;tick<=904;tick++) tapObserved|=actualTap.input(tick,true,2,false,false)!=CombatTap.Input.NONE;
            expect(!tapObserved,"a denied attack cannot schedule an outgoing sprint tap");
            sprint.invoke(get(events,"input"),true);
            expect(mc.gameSettings.keyBindSprint.isKeyDown() && player.isSprinting(),"controller owns the native sprint key while running");
            sprint.invoke(get(events,"input"),false);
            expect(!mc.gameSettings.keyBindSprint.isKeyDown() && !player.isSprinting(),"W/S release drops the native sprint key/state");
            sprint.invoke(get(events,"input"),true);Method release=ClientEvents.class.getDeclaredMethod("releaseKeys");release.setAccessible(true);release.invoke(events);
            expect(!mc.gameSettings.keyBindSprint.isKeyDown() && !player.isSprinting(),"stop/pause release cannot leave sprint held");
            OptimusMod.events=events;
            List<String> terminalAdd=OptimusMod.terminalCommand(new String[]{"add","TerminalRival"},false);
            expect(OptimusMod.settings.whitelist.contains("TerminalRival") && terminalAdd.contains("Added TerminalRival"),
                "terminal uses the actual target command and captures its response without server chat");
            ClientSettings config=OptimusMod.settings;
            OptimusMod.terminalCommand(TerminalBridge.parse("target all","Bot_One"),false);
            expect(config.targetAll && config.targets("UnlistedRival") && config.whitelist.contains("TerminalRival"),"target-all command retains the named list");
            OptimusMod.terminalCommand(TerminalBridge.parse("target listed","Bot_One"),false);
            expect(!config.targetAll && !config.targets("UnlistedRival") && config.targets("TerminalRival"),"listed command restores named targets");
            OptimusMod.terminalCommand(new String[]{"target","all"},false);
            OptimusMod.terminalCommand(new String[]{"target","add","TerminalRival"},false);
            expect(!config.targetAll,"adding an existing saved target exits all-target mode");
            OptimusMod.terminalCommand(new String[]{"target","all"},false);
            OptimusMod.terminalCommand(new String[]{"target","clear"},false);
            expect(!config.targetAll && config.whitelist.all().isEmpty(),"clear exits all-target mode and clears combat targets");
            OptimusMod.terminalCommand(TerminalBridge.parse("weapon fist","Bot_One"),false);
            expect(!config.swordEnabled,"terminal weapon command selects fist mode");
            OptimusMod.terminalCommand(TerminalBridge.parse("weapon sword","Bot_One"),false);
            expect(config.swordEnabled,"terminal weapon command restores sword mode");
            List<String> terminalStatus=OptimusMod.terminalCommand(new String[]{"status"},false);
            expect(terminalStatus.size()>=8 && terminalStatus.stream().anyMatch(line -> line.contains("Bot: Off")),"terminal captures all actual status notices");
            List<String> deniedStart=OptimusMod.terminalCommand(new String[]{"start"},false);
            expect(deniedStart.toString().contains("Denied:") && !(Boolean)get(events,"armed") && !(Boolean)get(events,"startRequested"),
                "terminal start runs the existing safety denial immediately and leaves no queued retry");
            expect(OptimusMod.terminalCommand(new String[]{"say","hello"},false).toString().contains("Say blocked:"),
                "terminal speech retains fresh Duels verification gate");
            boolean emergencyRejected=false;
            try { OptimusMod.terminalCommand(new String[]{"start"},true); } catch(IllegalArgumentException error) { emergencyRejected=true; }
            expect(emergencyRejected && !(Boolean)get(events,"startRequested"),"F9 blocks a terminal start before command handling");
            sprint.invoke(get(events,"input"),true);
            expect(OptimusMod.terminalCommand(new String[]{"stop"},true).contains("Stopped.")
                && !mc.gameSettings.keyBindSprint.isKeyDown() && !player.isSprinting(),"terminal stop releases native keys even during F9");
            Field captured=OptimusMod.class.getDeclaredField("terminalNotices");captured.setAccessible(true);
            expect(((ThreadLocal<?>)captured.get(null)).get()==null,"terminal capture is removed so normal chat command replies remain local");
        } finally { singleton.set(null,oldSingleton);OptimusMod.settings=oldSettings;OptimusMod.events=oldEvents; }
        System.out.println("PASS: "+checks+" actual navigator jump/search continuity and native sprint-key ownership checks");
    }
}