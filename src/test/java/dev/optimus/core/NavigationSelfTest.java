package dev.optimus.core;

import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.LobbyTravel;
import dev.optimus.core.safety.ControlPolicy;
import dev.optimus.core.safety.RunSafety;

import dev.optimus.client.ClientSettings;
import dev.optimus.core.navigation.GridPathfinder.Node;
import java.nio.file.Files;
import java.util.*;

public final class NavigationSelfTest {
    private static int checks;
    private static void expect(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
    private static final class MapTerrain implements GridPathfinder.Terrain {
        final Set<Node> floor = new HashSet<>();
        final Set<Node> blocked = new HashSet<>();
        public boolean standable(Node n) { return floor.contains(n); }
        public boolean traversable(Node a, Node b) { return !blocked.contains(b); }
        void flat(int minX, int maxX, int minZ, int maxZ, int y2) {
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) floor.add(new Node(x,y2,z));
        }
    }
    private static GridPathfinder.Goal goal(final Node target) {
        return new GridPathfinder.Goal() {
            public boolean reached(Node n) { return target.equals(n); }
            public double estimate(Node n) { return Math.abs(n.x-target.x) + Math.abs(n.z-target.z); }
        };
    }
    private static List<Node> find(MapTerrain map, Node from, Node to) { return new GridPathfinder().find(from,map,goal(to),1000); }
    private static void validate(MapTerrain map, List<Node> path) {
        for (int i = 0; i < path.size(); i++) {
            expect(map.standable(path.get(i)), "every path cell has safe floor");
            if (i == 0) continue;
            Node a = path.get(i-1), b = path.get(i);
            expect(Math.abs(a.x-b.x)+Math.abs(a.z-b.z)==1, "cardinal moves prevent corner cutting/gap jumps");
            expect(b.y2-a.y2 <= 2 && b.y2-a.y2 >= -6 && map.traversable(a,b), "path steps respect jump, drop and clearance");
        }
    }
    public static void main(String[] args) throws Exception {
        MapTerrain map = new MapTerrain(); map.flat(0,6,0,4,128);
        for (int z=0;z<4;z++) map.floor.remove(new Node(3,128,z));
        List<Node> detour = find(map,new Node(0,128,0),new Node(6,128,0));
        expect(!detour.isEmpty() && detour.stream().anyMatch(n->n.z==4), "A* detours around a wall instead of steering through it"); validate(map,detour);
        map.floor.remove(new Node(3,128,4));
        expect(find(map,new Node(0,128,0),new Node(6,128,0)).isEmpty(), "sealed wall has no partial unsafe route");
        MapTerrain stairs = new MapTerrain();
        for(int x=0;x<5;x++) stairs.floor.add(new Node(x,128+x,0));
        List<Node> stairPath=find(stairs,new Node(0,128,0),new Node(4,132,0));
        expect(stairPath.size()==5,"half-block stairs planned"); validate(stairs,stairPath);
        MapTerrain jump = new MapTerrain(); jump.floor.add(new Node(0,128,0)); jump.floor.add(new Node(1,130,0));
        expect(find(jump,new Node(0,128,0),new Node(1,130,0)).size()==2,"one-block jump permitted");
        jump.blocked.add(new Node(1,130,0));
        expect(find(jump,new Node(0,128,0),new Node(1,130,0)).isEmpty(),"low ceiling/blocked sweep rejects jump");
        MapTerrain drop = new MapTerrain(); drop.floor.add(new Node(0,128,0)); drop.floor.add(new Node(1,122,0));
        expect(find(drop,new Node(0,128,0),new Node(1,122,0)).size()==2,"three-block landing permitted");
        drop.floor.clear(); drop.floor.add(new Node(0,128,0)); drop.floor.add(new Node(1,120,0));
        expect(find(drop,new Node(0,128,0),new Node(1,120,0)).isEmpty(),"four-block drop rejected");
        MapTerrain gap = new MapTerrain(); gap.floor.add(new Node(0,128,0)); gap.floor.add(new Node(2,128,0));
        expect(find(gap,new Node(0,128,0),new Node(2,128,0)).isEmpty(),"never jump unknown floor/void gaps");
        expect(new GridPathfinder().find(new Node(0,128,0),stairs,goal(new Node(4,132,0)),1).isEmpty(),"budget exhaustion returns no partial path");
        GridPathfinder.Search incremental=new GridPathfinder.Search(new Node(0,128,0),stairs,goal(new Node(4,132,0)),1000);
        expect(incremental.advance(1000,1)==GridPathfinder.State.SEARCHING && incremental.visited()==1,"tiny time slice retains frontier after one expansion");
        expect(incremental.path().isEmpty(),"unfinished search never exposes a movement path");
        int slices=0;
        while(incremental.state()==GridPathfinder.State.SEARCHING && slices++<20) incremental.advance(1,1);
        expect(incremental.state()==GridPathfinder.State.FOUND && incremental.path().size()==5,"subsequent tick slices finish previously timed-out route");
        int visited=incremental.visited();incremental.advance(1000,0);
        expect(incremental.visited()==visited,"completed route does not consume further search budget");
        GridPathfinder.Search impossible=new GridPathfinder.Search(new Node(0,128,0),gap,goal(new Node(2,128,0)),1000);
        expect(impossible.advance(1000,0)==GridPathfinder.State.NO_PATH && impossible.path().isEmpty(),"exhausted frontier explicitly differs from slice timeout");
        GridPathfinder.Search limited=new GridPathfinder.Search(new Node(0,128,0),stairs,goal(new Node(4,132,0)),1);
        expect(limited.advance(1000,0)==GridPathfinder.State.NODE_LIMIT && limited.path().isEmpty(),"total node cap is explicit and never exposes partial routes");

        // The diagonal corridor costs sqrt(2)*4; the cardinal detour costs six.
        // Manhattan edge costs incorrectly prefer the detour (six versus eight).
        final List<Node> diagonal=Arrays.asList(new Node(0,2,0),new Node(1,2,1),new Node(2,2,2),new Node(3,2,1),new Node(4,2,0));
        final List<Node> cardinal=Arrays.asList(new Node(0,2,0),new Node(0,2,-1),new Node(1,2,-1),new Node(2,2,-1),new Node(3,2,-1),new Node(4,2,-1),new Node(4,2,0));
        GridPathfinder.Terrain corridors=new GridPathfinder.Terrain() {
            public boolean standable(Node n) { return diagonal.contains(n) || cardinal.contains(n); }
            public boolean traversable(Node a,Node b) {
                for(List<Node> lane:Arrays.asList(diagonal,cardinal)) for(int i=1;i<lane.size();i++)
                    if(lane.get(i-1).equals(a) && lane.get(i).equals(b)) return true;
                return false;
            }
            public Iterable<Node> extraNeighbors(Node n) {
                int i=diagonal.indexOf(n);return i>=0 && i+1<diagonal.size()?Arrays.asList(diagonal.get(i+1)):Collections.emptyList();
            }
        };
        List<Node> shortest=new GridPathfinder().find(diagonal.get(0),corridors,new GridPathfinder.Goal() {
            public boolean reached(Node n) { return n.equals(diagonal.get(4)); }
            public double estimate(Node n) { return Math.hypot(n.x-4,n.z); }
        },100);
        expect(shortest.equals(diagonal),"physical-distance cost prefers the shorter diagonal corridor over the cardinal detour");
        for(int i=1;i<shortest.size();i++) expect(corridors.traversable(shortest.get(i-1),shortest.get(i)),"diagonal route uses only permitted complete edges");

        GridPathfinder.Terrain optional=new GridPathfinder.Terrain() {
            public boolean standable(Node n) { return n.y2==2 && n.z==0 && n.x>=0 && n.x<=8; }
            public boolean traversable(Node a,Node b) {
                return a.x==0 && b.x==4 || a.x==4 && b.x==8
                    || Math.abs(a.x-b.x)==1 && !(a.x==2 && b.x==3);
            }
            public Iterable<Node> extraNeighbors(Node n) {
                return n.x==0?Arrays.asList(new Node(4,2,0)):n.x==4?Arrays.asList(new Node(8,2,0)):Collections.emptyList();
            }
            public double cost(Node a,Node b) { return a.x==4 && b.x==8?20:Math.abs(a.x-b.x); }
            public List<Node> segment(Node a,Node b) {
                if(a.x==0 && b.x==4) return Arrays.asList(a,new Node(1,2,0),new Node(2,2,0),new Node(3,2,0),b);
                return Arrays.asList(a,b);
            }
        };
        List<Node> mixed=new GridPathfinder().find(new Node(0,2,0),optional,goal(new Node(6,2,0)),100);
        expect(mixed.size()==7 && mixed.get(6).x==6,"one cached connection can be followed by ordinary steps without taking the next connection");
        for(int i=0;i<mixed.size();i++) expect(mixed.get(i).x==i,"cached connection expands to its checked execution points");
        List<Node> noHint=new GridPathfinder().find(new Node(4,2,0),optional,goal(new Node(6,2,0)),100);
        expect(noHint.equals(Arrays.asList(new Node(4,2,0),new Node(5,2,0),new Node(6,2,0))),"a cheaper ordinary route beats the remaining cached chain");

        RunSafety run=new RunSafety(); run.start(1000);
        expect(!run.mayControl(), "join phase never allows movement or attacks");
        expect(run.joining() && run.phase()==RunSafety.Phase.JOIN_DUELS,"manual start begins command-only join");
        run.worldTransfer(); expect(run.joining(),"expected joining world transition preserves join intent");
        run.joiningOne(); expect(run.phase()==RunSafety.Phase.JOIN_ONE,"join lobby one only after first stage");
        Object world=new Object();
        expect(run.activate("duelslobby2","dynamiclobbyX",world) && run.running(),"verified Duels fallback lobby can activate");
        run.start(1000);run.joiningOne(); expect(!run.activate("bedwarslobby2","dynamiclobbyX",world),"non-Duels lobby cannot activate");
        run.start(1000); run.joiningOne(); expect(run.activate("duelslobby1","dynamiclobbyX",world),"exact lobby one activates");
        run.respawn(2000); expect(run.phase()==RunSafety.Phase.RECOVERING,"actual pit death pauses run");
        expect(!run.mayControl(), "recovery phase never allows input before verification");
        expect(run.recover("duelslobby1","dynamiclobbyX",world),"same-world exact-instance verification permits pit return");
        expect(run.mayControl(), "only active state permits further guarded input");
        run.respawn(3000); expect(!run.recover("duelslobby1","dynamiclobbyY",world),"respawn into another server instance stops");
        run.start(1000);run.joiningOne();run.activate("duelslobby1","dynamiclobbyX",world);run.respawn(2000);
        expect(!run.recover("duelslobby1","dynamiclobbyX",new Object()),"matching coordinates/lobby in another world stops");
        run.start(1000);run.joiningOne();run.activate("duelslobby1","dynamiclobbyX",world);run.worldTransfer();
        expect(!run.running(),"hard transfer after activation never auto-rearms");
        run.start(1000);expect(run.expired(31001),"join deadline prevents endless retries");run.stop();
        expect(!run.running(),"F9/manual stop cancels join and recovery intent");
        expect(!run.mayControl(), "stopped run cannot navigate or attack");
        run.start(1000);run.joiningOne();run.activate("duelslobby1","dynamiclobbyX",world);run.respawn(2000);run.respawn(9000);
        expect(run.expired(10001),"repeated respawn events cannot extend recovery forever");

        ClientSettings config=new ClientSettings(Files.createTempDirectory("optimus-navigation").toFile());
        config.hypixelPit.setCornerA(new Vec3Point(10,60,10));config.hypixelPit.setCornerB(new Vec3Point(20,65,20));
        config.pitLobby="duelslobby1";config.pitInstance="dynamiclobbyX";
        expect(!config.navigationConfigured(),"legacy pit alone cannot authorize automatic travel");
        config.navigationArea.setCornerA(new Vec3Point(0,55,0));config.navigationArea.setCornerB(new Vec3Point(25,75,25));
        config.pitEntrance=new Vec3Point(11.5,61,11.5);expect(config.navigationConfigured(),"bounded spawn-to-pit configuration valid");
        config.save();ClientSettings loaded=new ClientSettings(config.traceDirectory().getParentFile());
        expect(loaded.navigationConfigured() && loaded.pitEntrance.x==11.5,"navigation area and entrance persisted");
        config.pitLobby="duelslobby2";expect(!config.navigationConfigured(),"legacy rectangle compatibility helper retains its old lobby-one rule");
        config.pitLobby="duelslobby1";config.pitEntrance=new Vec3Point(5,61,5);
        expect(!config.navigationConfigured(),"entrance outside pit rejected");
        config.pitEntrance=new Vec3Point(11.5,61,11.5);config.navigationArea.setCornerB(new Vec3Point(15,75,25));
        expect(!config.navigationConfigured(),"navigation region must include entire pit");
        PitRegion bodyBounds=new PitRegion();bodyBounds.setCornerA(new Vec3Point(0,60,0));bodyBounds.setCornerB(new Vec3Point(5,65,5));
        expect(bodyBounds.containsBody(new Vec3Point(2.5,60,2.5),0.3,1.8),"full body fits in pit interior");
        expect(!bodyBounds.containsBody(new Vec3Point(0.1,60,2.5),0.3,1.8),"entering with feet alone does not switch to combat prematurely");
        expect(!bodyBounds.containsBody(new Vec3Point(2.5,64,2.5),0.3,1.8),"head must remain in vertical bounds");
        PitRegion travel=LobbyTravel.area();
        Vec3Point normalSpawnExample=new Vec3Point(0.5,69,0.5); // Test coordinate, not a measured Hypixel spawn.
        ClientSettings supplied=new ClientSettings(Files.createTempDirectory("optimus-full-lobby").toFile());
        supplied.applySuppliedArena("duelslobby1","dynamiclobbyX");
        expect(!supplied.navigationArea.containsBody(normalSpawnExample,0.3,1.8),"synthetic normal spawn is outside old inferred nav rectangle");
        expect(travel.containsBody(normalSpawnExample,0.3,1.8),"full-lobby travel admits a supported spawn beyond old rectangle");
        expect(travel.containsBody(new Vec3Point(120.5,100,-180.5),0.3,1.8),"other loaded lobby locations are not restricted to pit/spawn rectangle");
        expect(!travel.containsBody(new Vec3Point(0,255,0),0.3,1.8),"full-lobby movement still requires a body within world height");
        expect(!travel.containsBody(new Vec3Point(30000000,69,0),0.3,1.8),"world-edge full-body limit remains enforced");
        MapTerrain wholeLobby=new MapTerrain(); wholeLobby.flat(-2,2,-80,0,138);
        List<Node> fromNormalSpawn=find(wholeLobby,new Node(0,138,0),new Node(0,138,-80));
        expect(fromNormalSpawn.size()==81 && fromNormalSpawn.stream().allMatch(n->travel.containsBody(n.point(),0.3,1.8)),"supported route from outside old rectangle to pit-side destination remains world-contained");
        expect(ControlPolicy.allowNavigation(true,true,true,travel.containsBody(normalSpawnExample,0.3,1.8),true,false),"verified active lobby-one navigation can start outside legacy rectangle");
        expect(!ControlPolicy.allowNavigation(true,false,true,true,true,false),"world-wide coordinate envelope cannot authorize a match or unverified lobby");
        expect(!ControlPolicy.allowNavigation(true,true,false,true,true,false),"changed lobby instance still blocks travel everywhere");
        expect(!ControlPolicy.allowNavigation(false,true,true,true,true,false),"full-lobby navigation does not arm itself");
        expect(!ControlPolicy.allowNavigation(true,true,true,true,true,true),"full-lobby travel still stops on open GUI");
        expect(!ControlPolicy.allowAttack(true,true,false,true,true,true,true,false),"being in lobby travel area outside pit cannot authorize attack");
        supplied.navigationArea.clear();
        expect(!supplied.navigationConfigured() && supplied.arenaConfigured(),"runtime arena configuration works without legacy nav corners");
        supplied.save();
        expect(new ClientSettings(supplied.traceDirectory().getParentFile()).arenaConfigured(),"pit/entry-only full-lobby config persists");
        supplied.pitInstance=""; expect(!supplied.arenaConfigured(),"missing dynamic instance prevents arena authorization");
        supplied.pitInstance="dynamiclobbyX"; supplied.pitLobby="duelslobby2";
        expect(supplied.arenaConfigured(),"arena geometry can originate in any Duels lobby number");
        supplied.pitLobby="bedwarslobby2"; expect(!supplied.arenaConfigured(),"another game's calibration cannot authorize a Duels arena");
        supplied.pitLobby="duelslobby1"; supplied.pitEntrance=new Vec3Point(50,63,-100);
        expect(!supplied.arenaConfigured(),"full-lobby travel cannot use an entrance outside combat boundaries");
        expect(ControlPolicy.allowPitEntry(true,true,false),"safe ground inside pit enters combat without detour to entry marker");
        expect(!ControlPolicy.allowPitEntry(true,false,false),"midair entry does not begin combat during drop");
        expect(!ControlPolicy.allowPitEntry(false,true,false),"standing outside pit cannot begin combat");
        expect(!ControlPolicy.allowPitEntry(true,true,true),"committed jump must finish landing validation before pit activation");
        run.startChecking(1000);
        expect(run.checking() && !run.joining() && !run.mayControl(),"initial current-lobby verification gives no gameplay input or world-transfer permission");
        expect(run.activate("duelslobby9","dynamiclobby33C",world),"checked current lobby 9 activates without joining lobby 1");
        run.respawn(2000); expect(run.recover("duelslobby9","dynamiclobby33C",world),"lobby 9 recovers its own exact instance/world");
        run.respawn(3000); expect(!run.recover("duelslobby1","dynamiclobby33C",world),"recovery cannot silently switch lobby number");
        run.startChecking(1000);run.joinDuels();
        expect(run.joining() && run.phase()==RunSafety.Phase.JOIN_DUELS,"non-Duels start may enter command-only fallback join");
        run.startChecking(1000);run.worldTransfer(); expect(!run.running(),"unexpected transfer during initial verification stops");
        run.startChecking(1000); expect(run.expired(31001),"initial verification has a bounded deadline");
        run.startChecking(1000); expect(!run.activate("duelslobby9","mini123A",world),"match server cannot activate even with lobby-shaped name");
        System.out.println("PASS: "+checks+" full-lobby pathfinding, all-Duels activation, respawn recovery and config checks");
    }
}