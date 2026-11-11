package dev.optimus.core;

import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.geometry.ArenaPreset;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.DropGate;
import dev.optimus.core.navigation.FallTrajectory;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.JumpArc;

import dev.optimus.client.ClientSettings;
import dev.optimus.core.navigation.GridPathfinder.Node;
import java.nio.file.Files;
import java.util.*;

/** Supplied arena, constrained entry falls, and bounded parkour model checks. */
public final class ParkourSelfTest {
    private static int checks;
    private static void expect(boolean condition,String why) { checks++; if (!condition) throw new AssertionError(why); }
    private static final class RimTerrain implements FallTrajectory.Terrain {
        final PitRegion bounds;
        boolean obstructed, missingFloor;
        RimTerrain(PitRegion bounds) { this.bounds=bounds; }
        public boolean inside(Vec3Point point) { return bounds.containsBody(point,0.3,1.8); }
        public boolean supported(Vec3Point point) {
            return (Math.abs(point.y-78)<0.06 && point.z+0.3>=-78)
                || (!missingFloor && Math.abs(point.y-63)<0.06 && point.z<-78);
        }
        public boolean clear(Vec3Point a,Vec3Point b) {
            double bottom=Math.min(a.y,b.y)+0.01, top=Math.max(a.y,b.y)+1.8;
            if (bottom<78 && Math.max(a.z,b.z)+0.3>=-78) return false;
            if (bottom<63) return false;
            return !obstructed || bottom>=70 || top<=68;
        }
    }
    private static GridPathfinder.Goal goal(final Node target) {
        return new GridPathfinder.Goal() {
            public boolean reached(Node n) { return target.equals(n); }
            public double estimate(Node n) { return Math.abs(n.x-target.x)+Math.abs(n.z-target.z); }
        };
    }
    public static void main(String[] args) throws Exception {
        ClientSettings settings=new ClientSettings(Files.createTempDirectory("optimus-parkour").toFile());
        expect(settings.parkourEnabled && settings.dropRim==null && !settings.arenaConfigured(),"parkour is the default while unconfigured clients remain unable to start");
        java.io.File legacyDirectory=Files.createTempDirectory("optimus-parkour-default").toFile();
        Properties legacy=new Properties();legacy.setProperty("navigation.parkour","false");
        legacy.setProperty("combat.profile","paced");legacy.setProperty("combat.seed","73");
        legacy.setProperty("target.names","FixtureUser");legacy.setProperty("hypixel.a",new Vec3Point(-12,63,-120).serialize());
        legacy.setProperty("hypixel.b",new Vec3Point(12,76,-80).serialize());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(legacyDirectory,"optimus.properties"))) { legacy.store(out,"Legacy settings"); }
        ClientSettings defaultUpgrade=new ClientSettings(legacyDirectory);
        expect(!defaultUpgrade.parkourEnabled,"saved parkour off is respected without rewriting the config");
        expect(defaultUpgrade.combatProfile==CombatProfile.PACED && defaultUpgrade.combatSeed==73
            && defaultUpgrade.whitelist.contains("FixtureUser") && defaultUpgrade.hypixelPit.cornerA().x==-12,
            "default migration preserves unrelated profile, seed, targets and custom geometry");
        expect(!new ClientSettings(legacyDirectory).parkourEnabled,"saved parkour off survives restart");
        defaultUpgrade.parkourEnabled=false;defaultUpgrade.save();
        expect(!new ClientSettings(legacyDirectory).parkourEnabled,"a subsequent optional off override survives restart");
        defaultUpgrade.parkourEnabled=true;defaultUpgrade.save();
        expect(new ClientSettings(legacyDirectory).parkourEnabled,"optional on override also persists");
        java.nio.file.Path malformed=Files.createTempDirectory("optimus-corrupt-properties");
        String corrupt="broken="+'\\'+"uZZZZ\n";
        Files.write(malformed.resolve("optimus.properties"),corrupt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ClientSettings corruptSettings=new ClientSettings(malformed.toFile());
        expect(!corruptSettings.arenaConfigured() && corruptSettings.controllerName.isEmpty(),"malformed Properties escape cannot crash initialization or authorize gameplay");
        expect(new String(Files.readAllBytes(malformed.resolve("optimus.properties")),java.nio.charset.StandardCharsets.UTF_8).equals(corrupt),
            "unreadable configuration is preserved rather than overwritten by migration");
        try { settings.applySuppliedArena("bedwarslobby2","dynamiclobbyX"); throw new AssertionError("wrong game lobby accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { settings.applySuppliedArena("duelslobby1",""); throw new AssertionError("missing instance accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        settings.applySuppliedArena("duelslobby1","dynamiclobbyX");
        expect(settings.navigationConfigured(),"supplied preset has complete bounded geometry");
        expect(settings.spawnPoint.x==0.5 && settings.spawnPoint.y==69.5 && settings.spawnPoint.z==-50.5,"exact supplied spawn preserved");
        expect(settings.hypixelPit.cornerA().x==-25 && settings.hypixelPit.cornerB().x==25
            && settings.hypixelPit.cornerA().z==-127 && settings.hypixelPit.cornerB().z==-77,"requested one-block horizontal expansion");
        expect(settings.hypixelPit.containsBody(new Vec3Point(0,63,-100),0.3,1.8),"floor-level body allowed");
        expect(!settings.hypixelPit.containsBody(ArenaPreset.DROP_RIM,0.3,1.8),"rim is not a combat location");
        expect(!settings.hypixelPit.containsBody(settings.spawnPoint,0.3,1.8),"spawn is not a combat location");
        expect(settings.navigationArea.containsBody(settings.spawnPoint,0.3,1.8)
            && settings.navigationArea.containsBody(settings.dropRim,0.3,1.8),"inferred nav envelope covers spawn and entry");
        expect(settings.pitEntrance.z<settings.spawnPoint.z,"entrance direction is negative Z, not assumed spawn facing");
        expect(settings.parkourEnabled && settings.dropFloor==63,"supplied preset explicitly enables bounded parkour and named drop");
        settings.save();ClientSettings loaded=new ClientSettings(settings.traceDirectory().getParentFile());
        expect(loaded.navigationConfigured() && loaded.parkourEnabled && loaded.dropRim.y==78
            && loaded.spawnPoint.y==69.5 && loaded.dropFloor==63,"drop, parkour, spawn and lobby binding persist");
        for(Vec3Point edge:new Vec3Point[]{new Vec3Point(-24.5,63,-100),new Vec3Point(24.5,63,-100),new Vec3Point(0,63,-126.5),new Vec3Point(0,63,-77.5)})
            expect(loaded.hypixelPit.containsBody(edge,0.3,1.8),"added edge block includes full player body");
        expect(!loaded.hypixelPit.containsBody(new Vec3Point(25,63,-100),0.3,1.8),"expanded outer boundary still requires entire body inside");
        loaded.controllerName="FixtureUser"; loaded.controllerUuid="00000000-0000-4000-8000-000000000001";
        loaded.whitelist.add("FixtureUser"); loaded.combatSeed=42; loaded.combatProfile=CombatProfile.PACED;
        loaded.hypixelPit.setCornerA(new Vec3Point(-24,62.5,-126)); loaded.hypixelPit.setCornerB(new Vec3Point(24,78,-78)); loaded.save();
        ClientSettings upgraded=new ClientSettings(loaded.traceDirectory().getParentFile());
        expect(upgraded.hypixelPit.cornerA().x==-24 && upgraded.hypixelPit.cornerB().x==24,"loading preserves explicitly saved bounds");
        expect(upgraded.navigationConfigured() && upgraded.controllerName.equals("FixtureUser") && upgraded.whitelist.contains("FixtureUser")
            && upgraded.combatSeed==42 && upgraded.combatProfile==CombatProfile.PACED && upgraded.pitInstance.equals("dynamiclobbyX"),"upgrade preserves controller, targets, settings and instance binding");
        expect(new ClientSettings(upgraded.traceDirectory().getParentFile()).hypixelPit.cornerA().x==-24,"saved custom bounds persist unchanged");

        upgraded.hypixelPit.setCornerA(new Vec3Point(-23,62.5,-126)); upgraded.save();
        expect(new ClientSettings(upgraded.traceDirectory().getParentFile()).hypixelPit.cornerA().x==-23,"custom pit remains as explicitly configured");

        Vec3Point from=new Vec3Point(-1.5,78,-77.5), to=new Vec3Point(-1.5,63,-79.5);
        expect(DropGate.allows(settings.dropRim,63,from,to,settings.hypixelPit),"15-block entry permitted only at named lip");
        expect(DropGate.allows(settings.dropRim,63,from,new Vec3Point(-1.5,64,-79.5),settings.hypixelPit),"live floor surface may be one block higher than supplied floor coordinate");
        expect(!DropGate.allows(settings.dropRim,63,new Vec3Point(10,78,-77.5),new Vec3Point(10,63,-79.5),settings.hypixelPit),"another cliff cannot inherit deep-drop permission");
        expect(!DropGate.allows(settings.dropRim,63,from,new Vec3Point(-1.5,62,-79.5),settings.hypixelPit),"cannot drop below supplied floor");
        expect(!DropGate.allows(settings.dropRim,63,from,new Vec3Point(-1.5,63,-76.5),settings.hypixelPit),"landing outside expanded combat pit rejected");
        expect(!DropGate.allows(null,63,from,to,settings.hypixelPit),"no rim marker means no deep-drop authorization");
        expect(!DropGate.allows(settings.dropRim,Double.NaN,from,to,settings.hypixelPit),"malformed floor fails closed");
        expect(!DropGate.allows(settings.dropRim,63,from,new Vec3Point(-1.5,63,-90),settings.hypixelPit),"far-away landing cannot inherit entry exception");
        expect(DropGate.insideFallCorridor(settings.dropRim,63,new Vec3Point(-1.5,70,-79.5)),"entry descent stays in narrow authorized corridor");
        expect(!DropGate.insideFallCorridor(settings.dropRim,63,new Vec3Point(5,70,-79.5)),"airborne sideways drift cannot broaden drop exception");
        expect(!DropGate.insideFallCorridor(settings.dropRim,63,new Vec3Point(-1.5,62,-79.5)),"unexpected fall below floor rejected");
        RimTerrain rim=new RimTerrain(settings.navigationArea);
        List<Vec3Point> fall=FallTrajectory.samples(from,to,rim);
        expect(!fall.isEmpty() && fall.get(fall.size()-1).y==63,"walk-off model reaches supported supplied floor");
        for(Vec3Point p:fall) expect(rim.inside(p) && p.y>=63 && p.y<=78,"every entry-fall sample remains in nav bounds");
        rim.obstructed=true;expect(FallTrajectory.samples(from,to,rim).isEmpty(),"obstruction along fall column rejects entry");
        rim.obstructed=false;rim.missingFloor=true;expect(FallTrajectory.samples(from,to,rim).isEmpty(),"no confirmed landing floor rejects entry");
        rim.missingFloor=false;

        Vec3Point a=new Vec3Point(0.5,63,-100.5), b=new Vec3Point(2.5,63,-100.5);
        for(double speed:new double[]{0.14,0.20,0.28}) {
            List<Vec3Point> arc=JumpArc.samples(a,b,speed);
            expect(!arc.isEmpty(),"bounded walk-speed jump reaches one-cell-gap landing");
            for(Vec3Point p:arc) expect(settings.hypixelPit.containsBody(p,0.3,1.8),"whole jump arc stays inside pit");
            Vec3Point landing=arc.get(arc.size()-1);
            expect(Math.abs(landing.x-b.x)<=0.35 && landing.y==b.y,"braked model lands near platform center");
            expect(arc.stream().mapToDouble(p->p.y-a.y).max().getAsDouble()<1.3,"normal jump apex is bounded");
        }
        expect(JumpArc.samples(a,new Vec3Point(2.5,62,-100.5)).size()>0,"one-block lower parkour landing supported");
        expect(!JumpArc.candidate(a,new Vec3Point(2.5,64,-100.5)),"raised gap jumps require more than the bounded model");
        expect(!JumpArc.candidate(a,new Vec3Point(3.5,63,-100.5)),"two-empty-cell gaps are not invented");
        expect(!JumpArc.candidate(a,new Vec3Point(2.5,63,-99.5)),"no diagonal gap jumps or corner cutting");
        expect(JumpArc.samples(a,b,0.40).isEmpty() && JumpArc.samples(a,b,Double.NaN).isEmpty(),"unsupported launch speeds rejected");

        final Node start=new Node(0,126,-101), end=new Node(2,126,-101);
        GridPathfinder.Terrain platforms=new GridPathfinder.Terrain() {
            public boolean standable(Node n) { return n.equals(start)||n.equals(end); }
            public boolean traversable(Node x,Node y) { return JumpArc.candidate(x.point(),y.point()); }
            public Iterable<Node> extraNeighbors(Node n) { return n.equals(start)?Collections.singleton(end):Collections.emptyList(); }
        };
        expect(new GridPathfinder().find(start,platforms,goal(end),100).size()==2,"A* can use an explicitly validated parkour edge");
        GridPathfinder.Terrain noArc=new GridPathfinder.Terrain() {
            public boolean standable(Node n) { return platforms.standable(n); }
            public boolean traversable(Node x,Node y) { return false; }
            public Iterable<Node> extraNeighbors(Node n) { return platforms.extraNeighbors(n); }
        };
        expect(new GridPathfinder().find(start,noArc,goal(end),100).isEmpty(),"a platform without clear trajectory is not a route");
        final Node lip=new Node(-2,156,-78), landing=new Node(-2,126,-80);
        GridPathfinder.Terrain entry=new GridPathfinder.Terrain() {
            public boolean standable(Node n) { return n.equals(lip)||n.equals(landing); }
            public boolean traversable(Node x,Node y) { return DropGate.allows(settings.dropRim,63,x.point(),y.point(),settings.hypixelPit)
                && !FallTrajectory.samples(x.point(),y.point(),rim).isEmpty(); }
            public Iterable<Node> extraNeighbors(Node n) { return n.equals(lip)?Collections.singleton(landing):Collections.emptyList(); }
        };
        expect(new GridPathfinder().find(lip,entry,goal(landing),100).size()==2,"A* accepts separately authorized 15-block entry fall");
        rim.obstructed=true;
        expect(new GridPathfinder().find(lip,entry,goal(landing),100).isEmpty(),"authorized coordinates do not bypass collision checks");
        System.out.println("PASS: "+checks+" supplied-coordinate, named-drop and bounded-parkour checks");
    }
}