package dev.optimus.client;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.NavigationView;
import dev.optimus.core.navigation.SprintJump;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.util.glu.GLU;
import sun.misc.Unsafe;

/** Snapshot/trail integration without a game; optional hidden OpenGL render QA. */
public final class NavigationOverlaySelfTest {
    private static final class ShapedBlock extends net.minecraft.block.Block {
        ShapedBlock() { super(net.minecraft.block.material.Material.rock); }
        @Override public void setBlockBoundsBasedOnState(net.minecraft.world.IBlockAccess world,net.minecraft.util.BlockPos pos) {
            setBlockBounds(0,0,0,1,pos.getX()%2==0?.5f:1,1);
        }
    }
    private static final class ShapedWorld extends WorldClient {
        private static final net.minecraft.block.state.IBlockState SHAPE=new ShapedBlock().getDefaultState();
        private ShapedWorld() { super(null,null,0,net.minecraft.world.EnumDifficulty.NORMAL,null); }
        @Override public boolean isBlockLoaded(net.minecraft.util.BlockPos p) { return true; }
        @Override public net.minecraft.block.state.IBlockState getBlockState(net.minecraft.util.BlockPos p) { return SHAPE; }
    }
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static Field field(Class<?> type,String name) throws Exception {
        for(Class<?> c=type;c!=null;c=c.getSuperclass()) try { Field f=c.getDeclaredField(name);f.setAccessible(true);return f; }
        catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object o,String name,Object value) throws Exception { field(o.getClass(),name).set(o,value); }
    public static void main(String[] args) throws Exception {
        NavigationOverlay overlay=new NavigationOverlay();Object worldId=new Object();
        for(int tick=0;tick<120;tick++) overlay.observe(worldId,new Vec3Point(.5,1,.5+tick*.02),tick);
        expect(overlay.trail().size()==80,"movement trail is bounded");
        overlay.observe(worldId,new Vec3Point(9,1,9),119);
        expect(overlay.trail().size()==80 && overlay.trail().get(79).x==.5,"render frames cannot add multiple samples per tick");
        overlay.observe(new Object(),new Vec3Point(.5,1,.5),120);
        expect(overlay.trail().size()==1,"world replacement clears the old movement trail");
        overlay.clear();expect(overlay.trail().isEmpty(),"off/stop clears the trail");
        Unsafe unsafe=(Unsafe)field(Unsafe.class,"theUnsafe").get(null);
        WorldClient shaped=(WorldClient)unsafe.allocateInstance(ShapedWorld.class);
        net.minecraft.util.AxisAlignedBB half=NavigationOverlay.supportBounds(shaped,new Vec3Point(2.5,1.5,.5));
        net.minecraft.util.AxisAlignedBB full=NavigationOverlay.supportBounds(shaped,new Vec3Point(3.5,2,.5));
        net.minecraft.util.AxisAlignedBB halfAgain=NavigationOverlay.supportBounds(shaped,new Vec3Point(2.5,1.5,.5));
        expect(half.maxY-half.minY==.5 && full.maxY-full.minY==1 && halfAgain.maxY-halfAgain.minY==.5,
            "each highlight refreshes state-dependent slab bounds rather than reusing the previous block shape");
        Field singleton=field(Minecraft.class,"theMinecraft");Object old=singleton.get(null);ClientSettings oldSettings=OptimusMod.settings;
        ClientEvents oldEvents=OptimusMod.events;
        try {
            Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);singleton.set(null,mc);mc.gameSettings=new GameSettings();
            mc.theWorld=(WorldClient)unsafe.allocateInstance(Class.forName("dev.optimus.client.CombatHandoffSelfTest$World"));
            EntityPlayerSP actor=(EntityPlayerSP)unsafe.allocateInstance(Class.forName("dev.optimus.client.CombatHandoffSelfTest$Player"));
            mc.thePlayer=actor;actor.worldObj=mc.theWorld;actor.posX=.5;actor.posY=1;actor.posZ=.5;actor.onGround=true;
            actor.motionZ=.15;actor.jumpMovementFactor=.026f;set(actor,"sprint",true);
            OptimusMod.settings=new ClientSettings(Files.createTempDirectory("optimus-visuals").toFile());
            ClientSettings settings=OptimusMod.settings;PitRegion pit=settings.hypixelPit;
            pit.setCornerA(new Vec3Point(-10,0,-10));pit.setCornerB(new Vec3Point(15,10,15));settings.debugVisuals=true;
            CollisionGeometry geometry=new CollisionGeometry((x,y,z)->y==0?new CollisionGeometry.Cell(true,false,
                Arrays.asList(new CollisionGeometry.Box(x,0,z,x+1,1,z+1))):CollisionGeometry.Cell.AIR);
            LobbyNavigator nav=new LobbyNavigator(mc);set(nav,"geometry",geometry);
            Vec3Point start=new Vec3Point(.5,1,.5),goal=new Vec3Point(.5,1,8.5);
            SprintJump.Plan plan=SprintJump.plan(geometry,pit,start,goal,.15,.1274,.02548,.546);
            expect(plan!=null,"flat-floor fixture supplies a checked sprint jump");
            nav.commit(new LobbyNavigator.Step(LobbyNavigator.Status.MOVING,goal,true,false,true,plan),true,1);
            set(nav,"plannedTerrain",new GroundRoute(geometry,pit,pit,null,1,true));
            set(nav,"path",Arrays.asList(new GridPathfinder.Node(0,2,0),new GridPathfinder.Node(0,2,2),new GridPathfinder.Node(0,2,8)));set(nav,"index",1);
            NavigationView view=nav.debugView();
            expect(view.route.size()==3 && view.trajectory.size()>5,"snapshot exposes the completed route and nominal jump samples");
            expect(view.current==view.trajectory.get(view.trajectory.size()-1) && view.goal==goal,"next landing and final destination remain distinct");
            Object active=field(LobbyNavigator.class,"sprintLeap").get(nav);nav.debugView();
            expect(field(LobbyNavigator.class,"sprintLeap").get(nav)==active && field(LobbyNavigator.class,"index").getInt(nav)==1,
                "snapshot reads cannot advance or replace the movement plan");
            ClientEvents events=new ClientEvents();OptimusMod.events=events;set(events,"navigator",nav);set(events,"armed",true);
            net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(),true);
            new OptimusCommand().processRemote(actor,new String[]{"visuals","off"});
            expect(!settings.debugVisuals && field(ClientEvents.class,"armed").getBoolean(events)
                && field(LobbyNavigator.class,"sprintLeap").get(nav)==active && mc.gameSettings.keyBindForward.isKeyDown(),
                "visual toggle keeps the run, committed jump and held movement intact");
            new OptimusCommand().processRemote(actor,new String[]{"visuals","on"});
            expect(settings.debugVisuals,"visuals can be enabled without restarting the bot");
            net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(),false);
            for(int tick=0;tick<30;tick++) overlay.observe(mc.theWorld,new Vec3Point(-.5,1,-1+tick*.05),tick);
            if(Boolean.getBoolean("optimus.renderVisuals")) render(mc,view,overlay,unsafe);
            nav.reset();expect(nav.debugView().route.isEmpty() && nav.debugView().trajectory.isEmpty(),"route reset removes stale display plans");
        } finally { singleton.set(null,old);OptimusMod.settings=oldSettings;OptimusMod.events=oldEvents; }
        System.out.println("PASS: "+checks+" navigation overlay snapshot, launch, lifecycle and state checks");
    }
    private static void render(Minecraft mc,NavigationView view,NavigationOverlay overlay,Unsafe unsafe) throws Exception {
        System.setProperty("org.lwjgl.librarypath",Paths.get(".gradle-user/caches/minecraft/net/minecraft/natives/1.8.9").toAbsolutePath().toString());
        int width=960,height=640;Pbuffer buffer=new Pbuffer(width,height,new PixelFormat(),null,null);
        try {
            buffer.makeCurrent();
            GL11.glViewport(0,0,width,height);GL11.glClearColor(.035f,.045f,.065f,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glLoadIdentity();GLU.gluPerspective(48,(float)width/height,.1f,100);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glLoadIdentity();GLU.gluLookAt(0,0,0,-8.5f,-7,11,0,1,0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glPushMatrix();GL11.glTranslated(-9,-8,7);
            GL11.glBegin(GL11.GL_QUADS);
            for(int x=-3;x<5;x++) for(int z=-2;z<11;z++) {
                float shade=(x+z)%2==0?.14f:.18f;GL11.glColor3f(shade,shade+.025f,shade+.045f);
                GL11.glVertex3d(x,1,z);GL11.glVertex3d(x,1,z+1);GL11.glVertex3d(x+1,1,z+1);GL11.glVertex3d(x+1,1,z);
            }
            GL11.glEnd();GL11.glPopMatrix();
            EntityPlayerSP camera=(EntityPlayerSP)unsafe.allocateInstance(Class.forName("dev.optimus.client.CombatHandoffSelfTest$Player"));
            camera.posX=camera.lastTickPosX=9;camera.posY=camera.lastTickPosY=8;camera.posZ=camera.lastTickPosZ=-7;
            set(mc,"renderViewEntity",camera);
            GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glDepthMask(true);GL11.glLineWidth(1.25f);
            overlay.render(mc,view,1);
            expect(GL11.glIsEnabled(GL11.GL_TEXTURE_2D) && GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                && Math.abs(GL11.glGetFloat(GL11.GL_LINE_WIDTH)-1.25)<.01,"overlay restores texture, depth writes and line width");
            expect(GL11.glGetError()==GL11.GL_NO_ERROR,"actual overlay renders without OpenGL errors");
            ByteBuffer pixels=BufferUtils.createByteBuffer(width*height*4);GL11.glReadPixels(0,0,width,height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) { int i=(y*width+x)*4;image.setRGB(x,height-y-1,((pixels.get(i)&255)<<16)|((pixels.get(i+1)&255)<<8)|(pixels.get(i+2)&255)); }
            Path path=Paths.get("diagnostics/navigation-overlay-preview.png");Files.createDirectories(path.getParent());ImageIO.write(image,"png",path.toFile());
            System.out.println("Rendered actual navigation overlay: "+path);
        } finally { buffer.destroy(); }
    }
}