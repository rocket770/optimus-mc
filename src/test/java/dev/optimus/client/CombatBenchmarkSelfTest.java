package dev.optimus.client;

import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.combat.CombatFocus;
import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatSpacing;
import dev.optimus.core.combat.CombatStrafe;
import dev.optimus.core.combat.CombatTap;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GroundMotion;
import dev.optimus.core.navigation.NearbySteering;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.*;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.*;
import net.minecraft.world.EnumDifficulty;
import sun.misc.Unsafe;

/** Disconnected matched combat benchmark. Native damage resistance, damage,
 * knockback and collision; explicit tick/ground-air control and transport model.
 * No running game, network, or production settings changes.
 */
public final class CombatBenchmarkSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static Field field(Class<?> type,String name) throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField(name);f.setAccessible(true);return f; }
        catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static final class Floor extends Block { Floor() { super(Material.rock); } }
    private static final class World extends WorldClient {
        private static final IBlockState FLOOR=new Floor().getDefaultState();
        List<AxisAlignedBB> solids;double edge;
        private World() { super(null,null,0,EnumDifficulty.NORMAL,null); }
        @Override public boolean isBlockLoaded(BlockPos p) { return true; }
        @Override public IBlockState getBlockState(BlockPos p) { return FLOOR; }
        @Override public boolean isFlammableWithin(AxisAlignedBB box) { return false; }
        @Override public void setEntityState(Entity e,byte state) { }
        @Override public void playSoundAtEntity(Entity e,String sound,float volume,float pitch) { }
        @Override public List<AxisAlignedBB> getCollidingBoundingBoxes(Entity e,AxisAlignedBB query) {
            List<AxisAlignedBB> hits=new ArrayList<>();for(AxisAlignedBB b:solids) if(b.intersectsWith(query)) hits.add(b);return hits;
        }
        boolean clear(Vec3Point a,Vec3Point b) {
            for(int i=1;i<solids.size();i++) if(solids.get(i).calculateIntercept(new Vec3(a.x,a.y,a.z),new Vec3(b.x,b.y,b.z))!=null) return false;
            return true;
        }
        CollisionGeometry geometry() {
            return new CollisionGeometry((x,y,z)-> {
                List<CollisionGeometry.Box> boxes=new ArrayList<>();
                for(AxisAlignedBB b:solids) if(b.maxX>x && b.minX<x+1 && b.maxY>y && b.minY<y+1 && b.maxZ>z && b.minZ<z+1)
                    boxes.add(new CollisionGeometry.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ));
                return new CollisionGeometry.Cell(true,false,boxes);
            });
        }
    }
    private static final class Fighter extends EntityLivingBase {
        final CombatSpacing spacing=new CombatSpacing();final CombatStrafe strafe=new CombatStrafe();
        final CombatFocus<Object> focus=new CombatFocus<>();final CombatTap tap=new CombatTap();
        final CombatTurn turn=new CombatTurn();final CombatClicks clicks=new CombatClicks();
        final CollisionGeometry terrain;
        final Random pacing;final List<Frame> history=new ArrayList<>();
        int requests,hits,sprintHits,unsafe,falls;double damage;boolean first;
        int forwardChanges,sideChanges,shortInputRuns,lastForwardChange=-10,lastSideChange=-10;
        NearbySteering.Input previousInput;
        Fighter(World world,long seed) { this(world,seed,20); }
        Fighter(World world,long seed,int health) { super(world);terrain=world.geometry().cached();pacing=new Random(seed);tap.seed(seed);turn.seed(seed);rand.setSeed(seed);
            getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(health);setHealth(health);setSize(.6f,1.8f);onGround=true; }
        @Override public ItemStack getHeldItem() { return null; }
        @Override public ItemStack getEquipmentInSlot(int slot) { return null; }
        @Override public ItemStack getCurrentArmor(int slot) { return null; }
        @Override public ItemStack[] getInventory() { return new ItemStack[5]; }
        @Override public void setCurrentItemOrArmor(int slot,ItemStack item) { }
        @Override public boolean isPotionActive(Potion potion) { return false; }
        @Override public boolean isInWater() { return false; }
        @Override public boolean isWet() { return false; }
        @Override protected boolean canTriggerWalking() { return false; }
        @Override protected void doBlockCollisions() { }
        @Override public void onDeath(DamageSource source) { setDead(); } // No loot/world lifecycle in this fixture.
        @Override protected void updateFallState(double dy,boolean ground,Block block,BlockPos pos) {
            if(ground) fallDistance=0;else if(dy<0) fallDistance-=(float)dy;
        }
        Vec3Point at() { return new Vec3Point(posX,posY,posZ); }
        Vec3Point eye() { return new Vec3Point(posX,posY+1.62,posZ); }
        void capture() { history.add(new Frame(at(),hurtTime)); }
        Frame observed(int tick,int delay) { return history.get(Math.max(0,tick-delay)); }
        void physics(NearbySteering.Input input,boolean sprint,boolean sneak) {
            int tick=history.size()-1;
            if(previousInput!=null) {
                if(input.forward!=previousInput.forward) {
                    forwardChanges++;if(tick-lastForwardChange<=2) shortInputRuns++;lastForwardChange=tick;
                }
                if(input.side!=previousInput.side) {
                    sideChanges++;if(tick-lastSideChange<=2) shortInputRuns++;lastSideChange=tick;
                }
            }
            previousInput=input;
            setSprinting(sprint && input.forward>0 && !sneak);
            Vec3Point delta=GroundMotion.delta(motionX,motionZ,rotationYaw,input.forward,input.side,onGround?(isSprinting()?.13:.1):(isSprinting()?.026:.02),sneak?.3:1);
            motionX=delta.x;motionZ=delta.z;
            // EntityLivingBase dummies lack EntityPlayer's native sneak edge
            // branch. Use the shared cross-checked projection for that branch.
            Vec3Point clipped=onGround && sneak?terrain.sneakStep(at(),motionX,motionZ):null;
            moveEntity(clipped==null?motionX:clipped.x-posX,motionY,clipped==null?motionZ:clipped.z-posZ);
            motionX*=onGround?.546:.91;motionZ*=onGround?.546:.91;motionY=(motionY-.08)*.98;
            if(Math.abs(motionX)<.005) motionX=0;if(Math.abs(motionZ)<.005) motionZ=0;
            if(hurtTime>0) hurtTime--;if(hurtResistantTime>0) hurtResistantTime--;
            if(posY<0 && falls==0) falls++;
        }
    }
    private static final class Frame { final Vec3Point at;final int hurt;Frame(Vec3Point at,int hurt) { this.at=at;this.hurt=hurt; } }
    private static final class Action { NearbySteering.Input input;boolean sprint,attack,sneak;Action(NearbySteering.Input i,boolean s,boolean a,boolean k) { input=i;sprint=s;attack=a;sneak=k; } }
    private static final class Hit { final int due;final Fighter a,b;final float yaw;final boolean sprint,critical;
        Hit(int due,Fighter a,Fighter b) { this.due=due;this.a=a;this.b=b;yaw=a.rotationYaw;sprint=a.isSprinting();critical=!a.onGround && a.fallDistance>0; } }
    private static CollisionGeometry.Box box(Vec3Point p) { return new CollisionGeometry.Box(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3); }
    private static NearbySteering.Input input(int forward,int side) {
        for(NearbySteering.Input i:NearbySteering.Input.values()) if(i.forward==forward && i.side==side) return i;return NearbySteering.Input.NONE;
    }
    private static boolean safe(Fighter f,NearbySteering.Input i,World world,CollisionGeometry geometry,PitRegion area) {
        return safe(f,i,world,geometry,area,false);
    }
    private static boolean safe(Fighter f,NearbySteering.Input i,World world,CollisionGeometry geometry,PitRegion area,boolean sneak) {
        if(!f.onGround) return Math.abs(f.posX)<world.edge-.6 && Math.abs(f.posZ)<15;
        return GroundMotion.safe(geometry,area,f.at(),f.motionX,f.motionZ,f.rotationYaw,i.forward,i.side,sneak?.1:.13,1,sneak,p->.546);
    }
    private static Action decide(Fighter f,Fighter other,int tick,int lag,boolean revised,boolean scripted,String scenario,
                                 World world,CollisionGeometry geometry,PitRegion area) {
        Frame observed=other.observed(tick,lag),previous=other.observed(Math.max(0,tick-1),lag);
        Vec3Point self=f.at(),eye=f.eye(),target=observed.at;CollisionGeometry.Box b=box(target);
        boolean incoming=f.focus.observeDamage(f.getHealth(),f.hurtTime,tick),reacting=f.focus.reacting(tick);
        Vec3Point aim=revised?CombatAim.clearPoint(eye,b,3.5,p->world.clear(eye,p)):null;
        boolean visible=aim!=null || world.clear(eye,new Vec3Point(target.x,target.y+1.62,target.z));
        if(aim==null) aim=CombatAim.point(eye,b);
        float yaw=(float)Math.toDegrees(Math.atan2(self.x-aim.x,aim.z-self.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(aim.y-eye.y,Math.hypot(aim.x-self.x,aim.z-self.z)));
        f.rotationYaw+=f.turn.yaw(yaw-f.rotationYaw,CombatProfile.BALANCED,reacting);
        f.rotationPitch+=f.turn.pitch(pitch-f.rotationPitch,CombatProfile.BALANCED,reacting);
        double dx=target.x-self.x,dz=target.z-self.z,distance=Math.hypot(dx,dz);
        double toward=(f.motionX*dx+f.motionZ*dz)/Math.max(.01,distance);
        double separation=((target.x-previous.at.x)*dx+(target.z-previous.at.z)*dz)/Math.max(.01,distance)-toward;
        boolean pullingAway=revised && !scripted?f.spacing.separating(separation):separation>.05;
        boolean reachable=CombatAim.clearPoint(eye,b,2.75,p->world.clear(eye,p))!=null;
        CombatSpacing.Direction direction=revised?f.spacing.direction(distance,separation,toward,Math.sqrt(CombatAim.distanceSquared(eye,b)),reachable,f.focus.recentCombat(tick)):f.spacing.direction(distance,reacting);
        int forward=direction==CombatSpacing.Direction.FORWARD?1:direction==CombatSpacing.Direction.BACK?-1:0,side=0;
        if(scripted) {
            forward=distance>2.65?1:0;
            if(scenario.equals("trade")) forward=distance>1.8?1:0;
            if(scenario.equals("retreat") && distance<3.2) forward=-1;
            if(scenario.equals("circle")) side=1;
            if(scenario.equals("reverse")) side=tick%40<20?1:-1;
        } else if(revised) {
            NearbySteering.Input choice=f.strafe.choose(tick,self,target,new Vec3Point(target.x-previous.at.x,0,target.z-previous.at.z),
                f.rotationYaw,f.motionX,f.motionZ,forward,distance<=3.2 && !pullingAway,
                i->safe(f,i,world,geometry,area));
            forward=choice.forward;side=choice.side;
        } else if(!reacting && forward==0 && distance<2.5) side=(tick/22)%2==0?1:-1;
        if(!scripted) {
            f.tap.observe(observed.hurt,tick,visible && f.onGround && (revised || !reacting),distance,pullingAway,reacting);
            CombatTap.Input tap=f.tap.input(tick,visible && f.onGround && (revised || !reacting),distance,pullingAway,reacting);
            if(tap==CombatTap.Input.W_RELEASE) { forward=0;if(!revised) side=0; }
            if(tap==CombatTap.Input.S_BACK) { forward=-1;if(!revised) side=0; }
        }
        NearbySteering.Input move=input(forward,side);
        boolean sneak=false;
        if(!safe(f,move,world,geometry,area)) {
            move=NearbySteering.Input.NONE;f.unsafe++;sneak=f.onGround;
            double best=Double.POSITIVE_INFINITY,angle=Math.toRadians(f.rotationYaw);
            for(NearbySteering.Input candidate:NearbySteering.Input.values()) {
                double length=Math.max(1,Math.hypot(candidate.forward,candidate.side));
                double opposition=((-Math.sin(angle)*candidate.forward+Math.cos(angle)*candidate.side)*f.motionX
                    +(Math.cos(angle)*candidate.forward+Math.sin(angle)*candidate.side)*f.motionZ)/length;
                if(opposition<best && safe(f,candidate,world,geometry,area,sneak)) { move=candidate;best=opposition; }
            }
        }
        boolean sprint=f.spacing.sprint(distance,move.forward>0 && !sneak,pullingAway,revised && !scripted && f.focus.recentCombat(tick));
        Vec3Point ray=CombatAim.hit(eye,b,f.rotationYaw,f.rotationPitch,2.75);
        boolean hit=visible && ray!=null && world.clear(eye,ray);
        boolean attack=f.clicks.choose(tick,true,hit,false)==CombatClicks.Action.ATTACK;
        if(attack) { f.requests++;f.clicks.performed(CombatClicks.Action.ATTACK,tick,CombatProfile.BALANCED.attackDelay(f.pacing));f.tap.attacked(tick,sprint);f.focus.attacked(tick); }
        return new Action(move,sprint,attack,sneak);
    }
    private static void accept(Hit h,World world) {
        if(!h.a.isEntityAlive() || !h.b.isEntityAlive()) return;
        Vec3Point eye=h.a.eye();
        if(CombatAim.clearPoint(eye,box(h.b.at()),3,p->world.clear(eye,p))==null) return;
        float before=h.b.getHealth();boolean accepted=h.b.attackEntityFrom(DamageSource.causeMobDamage(h.a),h.critical?9:6);
        if(!accepted) return;
        h.a.damage+=before-h.b.getHealth();h.a.hits++;
        if(h.sprint) {
            double angle=Math.toRadians(h.yaw);h.b.addVelocity(-Math.sin(angle)*.5,.1,Math.cos(angle)*.5);
            h.a.motionX*=.6;h.a.motionZ*=.6;h.a.setSprinting(false);h.a.sprintHits++;
        }
    }
    public static void main(String[] args) throws Exception {
        Unsafe unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        // Language/bootstrap needs FML's crash-info singleton, but a disconnected
        // test JVM has no LaunchClassLoader or active mods. Bypass only Loader's
        // launch constructor; native combat methods below remain unmodified.
        Class<?> loader=Class.forName("net.minecraftforge.fml.common.Loader");
        field(loader,"instance").set(null,unsafe.allocateInstance(loader));
        net.minecraft.init.Bootstrap.register();
        World world=(World)unsafe.allocateInstance(World.class);field(net.minecraft.world.World.class,"theProfiler").set(world,new Profiler());
        field(net.minecraft.world.World.class,"provider").set(world,new net.minecraft.world.WorldProviderSurface());
        world.edge=16;world.solids=Arrays.asList(new AxisAlignedBB(-16,0,-16,16,1,16));
        Fighter a=new Fighter(world,1),b=new Fighter(world,2);a.setPosition(0,1,-1);b.setPosition(0,1,1);
        expect(b.attackEntityFrom(DamageSource.causeMobDamage(a),6) && b.getHealth()==14,"native damage is applied");
        expect(!b.attackEntityFrom(DamageSource.causeMobDamage(a),6) && b.getHealth()==14,"native repeated equal damage is rejected during resistance");
        expect(b.motionZ>0 && b.motionY>0,"native accepted damage applies directional and vertical knockback");
        expect(b.attackEntityFrom(DamageSource.causeMobDamage(a),9) && b.getHealth()==11,"native stronger damage applies only its excess during resistance");
        int healthBudget=Integer.getInteger("optimus.benchmarkHealth",20);
        if(healthBudget<20 || healthBudget>1000) throw new IllegalArgumentException("Benchmark health must be 20-1000");
        List<String> csv=new ArrayList<>();csv.add("scenario,seed,observation_and_transport_ticks,side,controller,damage_dealt,damage_taken,requests,accepted_hits,sprint_hits,first_hit,unsafe_inputs_rejected,falls,opponent_falls,ticks,defeated,opponent_defeated,health_budget,forward_changes,side_changes,short_input_runs");
        int matches=0,totalHits=0;double[] dealt=new double[2],taken=new double[2];
        for(String scenario:new String[]{"trade","retreat","circle","reverse","cover","ledge"})
        for(int lag:new int[]{0,2,4}) for(int seed=0;seed<4;seed++) for(int side:new int[]{-1,1}) for(int variant=0;variant<2;variant++) {
            world.edge=scenario.equals("ledge")?4.5:16;
            world.solids=new ArrayList<>();world.solids.add(new AxisAlignedBB(-world.edge,0,-16,world.edge,1,16));
            if(scenario.equals("cover")) world.solids.add(new AxisAlignedBB(-.8,2.4,-.15,.8,3.3,.15));
            CollisionGeometry geometry=world.geometry().cached();PitRegion area=new PitRegion();area.setCornerA(new Vec3Point(-20,-10,-20));area.setCornerB(new Vec3Point(20,20,20));
            Fighter bot=new Fighter(world,seed+42,healthBudget),opponent=new Fighter(world,seed+142,healthBudget);
            double x=scenario.equals("ledge")?3.7:0;
            bot.setPosition(x,1,-side*2.3);opponent.setPosition(x,1,side*2.3);bot.rotationYaw=side==1?0:180;opponent.rotationYaw=side==1?180:0;
            List<Hit> pending=new ArrayList<>();boolean first=false;int elapsed=0;
            for(int tick=0;tick<200;tick++) {
                bot.capture();opponent.capture();
                Action ours=decide(bot,opponent,tick,lag,variant==1,false,scenario,world,geometry,area);
                Action theirs=decide(opponent,bot,tick,lag,true,true,scenario,world,geometry,area);
                bot.setSprinting(ours.sprint);opponent.setSprinting(theirs.sprint);
                if(ours.attack) pending.add(new Hit(tick+lag,bot,opponent));
                if(theirs.attack) pending.add(new Hit(tick+lag,opponent,bot));
                if((seed+tick)%2==0) Collections.reverse(pending);
                for(Iterator<Hit> it=pending.iterator();it.hasNext();) { Hit h=it.next();if(h.due<=tick) { int hits=bot.hits+opponent.hits;accept(h,world);
                    if(!first && bot.hits+opponent.hits>hits) { bot.first=h.a==bot;first=true; }it.remove(); } }
                bot.physics(ours.input,ours.sprint,ours.sneak);opponent.physics(theirs.input,theirs.sprint,theirs.sneak);
                elapsed=tick+1;if(bot.falls>0 || opponent.falls>0 || !bot.isEntityAlive() || !opponent.isEntityAlive()) break;
            }
            expect(Double.isFinite(bot.damage) && Double.isFinite(opponent.damage) && bot.hits<=bot.requests,"accepted native damage remains bounded by requests");
            // Native incoming knockback can force a fall despite checked keys.
            // Record that outcome rather than granting the controller immunity.
            csv.add(scenario+","+seed+","+lag+","+side+","+(variant==0?"distance_baseline":"revised")+","+bot.damage+","+opponent.damage+","+bot.requests+","+bot.hits+","+bot.sprintHits+","+bot.first+","+bot.unsafe+","+bot.falls+","+opponent.falls+","+elapsed+","+!bot.isEntityAlive()+","+!opponent.isEntityAlive()+","+healthBudget+","+bot.forwardChanges+","+bot.sideChanges+","+bot.shortInputRuns);
            dealt[variant]+=bot.damage;taken[variant]+=opponent.damage;totalHits+=bot.hits;matches++;
        }
        expect(totalHits>0,"benchmark measures native accepted hits, not only requests");
        Path report=Paths.get("diagnostics/"+(healthBudget==20?"combat-benchmark.csv":"combat-benchmark-stress-"+healthBudget+".csv"));Files.createDirectories(report.getParent());Files.write(report,csv,StandardCharsets.UTF_8);
        System.out.println("Combat benchmark: "+matches+" matched runs, health="+healthBudget+"; baseline damage="+dealt[0]+" taken="+taken[0]+"; revised damage="+dealt[1]+" taken="+taken[1]);
        System.out.println("PASS: "+checks+" native damage/knockback benchmark checks; results="+report+" (scripted model, not a live-server win rate)");
    }
}