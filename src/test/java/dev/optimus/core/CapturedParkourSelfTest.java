package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.PlatformJump;
import dev.optimus.core.navigation.SprintGap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Native jump arcs and complete routes between actual captured Duels map coordinates. */
public final class CapturedParkourSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++;if(!value) throw new AssertionError(why); }
    private static String key(int x,int z,double y) { return x+":"+z+":"+Math.round(y*16); }
    private static final class Surface {
        final int x,z,id;final Vec3Point point;
        Surface(int x,int z,int id,double y) { this.x=x;this.z=z;this.id=id;point=new Vec3Point(x+.5,y,z+.5); }
        boolean slab() { return id==44 || id==126 || id==182; }
    }
    public static void main(String[] args) throws Exception {
        CollisionGeometry geometry=CollisionReplaySelfTest.capture().cached();PitRegion pit=new PitRegion();
        pit.setCornerA(new Vec3Point(-25,62.5,-127));pit.setCornerB(new Vec3Point(25,78,-77));
        Map<String,Surface> surfaces=new LinkedHashMap<>();
        InputStream input=CollisionReplaySelfTest.class.getResourceAsStream("duels-collision.tsv.gz");
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(input),StandardCharsets.UTF_8))) {
            String line;while((line=reader.readLine())!=null) {
                if(!line.startsWith("B ")) continue;String[] p=line.split(" ");
                int x=Integer.parseInt(p[1]),z=Integer.parseInt(p[3]),id=Integer.parseInt(p[4]);double top=Double.parseDouble(p[9]);
                if(x<-24 || x>24 || z<-126 || z>-78 || top<63 || top>74) continue;
                Surface surface=new Surface(x,z,id,top);
                if(geometry.standable(pit,surface.point)) surfaces.put(key(x,z,top),surface);
            }
        }
        expect(surfaces.size()>100,"captured pit has real supported surfaces for jump replay");
        Map<String,Integer> count=new LinkedHashMap<>(),attempts=new HashMap<>();
        for(String category:new String[]{"cardinal_climb","diagonal_climb","diagonal_gap","slab_jump","slab_walk"}) count.put(category,0);
        List<String> report=new ArrayList<>();report.add("kind,from_x,from_y,from_z,to_x,to_y,to_z,block_id,mode,arc_frames,end_x,end_y,end_z,route_nodes");
        GroundRoute terrain=new GroundRoute(geometry,pit,pit,null,63,true);
        for(Surface from:surfaces.values()) {
            if(count.values().stream().allMatch(value->value>=2)) break;
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
                if(dx==0 && dz==0) continue;
                for(double rise:new double[]{1,.5,0}) {
                    Surface to=surfaces.get(key(from.x+dx,from.z+dz,from.point.y+rise));if(to==null) continue;
                    boolean walk=geometry.autoStepClear(from.point,to.point,pit),diagonal=dx!=0 && dz!=0;
                    String kind=to.slab()?(walk?"slab_walk":"slab_jump"):!walk?(diagonal?(rise>.6?"diagonal_climb":"diagonal_gap"):rise>.6?"cardinal_climb":null):null;
                    if(kind==null || count.get(kind)>=2 || attempts.getOrDefault(kind,0)>=100) continue;
                    if(!walk && !geometry.free(CollisionGeometry.body(new Vec3Point(from.point.x,from.point.y+1.25,from.point.z))
                        .union(CollisionGeometry.body(new Vec3Point(to.point.x,from.point.y+1.25,to.point.z))))) continue;
                    attempts.put(kind,attempts.getOrDefault(kind,0)+1);
                    if(!terrain.edgeClear(from.point,to.point)) continue;
                    double length=Math.sqrt(from.point.horizontalDistanceSq(to.point)),ux=dx/length,uz=dz/length;
                    List<Vec3Point> arc=Collections.emptyList();String mode="walk";
                    if(!walk) {
                        for(double speed:new double[]{.118,0,.06}) {
                            PlatformJump.Plan plan=PlatformJump.plan(geometry,pit,from.point,to.point,ux*speed,uz*speed,.098,.0196,.546);
                            if(plan!=null) { arc=PlatformJump.samples(geometry,pit,plan,ux*speed,uz*speed,.098,.0196,.546,0);mode="normal_jump";break; }
                        }
                        if(arc.isEmpty() && diagonal) {
                            SprintGap.Candidate candidate=SprintGap.diagonalLanding(geometry,pit,from.point,to.point);
                            if(candidate!=null) { arc=SprintGap.samples(candidate.takeoff,to.point.y,candidate.dx,candidate.dz,.153,.1274,.026,.546);mode="sprint_jump"; }
                        }
                        if(arc.isEmpty()) continue;
                        for(Vec3Point point:arc) expect(pit.containsBody(point,.3,1.8) && geometry.free(CollisionGeometry.body(point)),"captured jump arc uses loaded clear pit-contained body space");
                        Vec3Point end=arc.get(arc.size()-1);
                        expect(geometry.standable(pit,end) && Math.abs(end.y-to.point.y)<.06,"captured jump lands on the actual collision-box top");
                    }
                    final Vec3Point goal=to.point;
                    GridPathfinder.Search search=new GridPathfinder.Search(terrain.start(from.point),terrain,new GridPathfinder.Goal() {
                        public boolean reached(GridPathfinder.Node n) { Vec3Point p=terrain.point(n);return p.horizontalDistanceSq(goal)<.01 && Math.abs(p.y-goal.y)<.01; }
                        public double estimate(GridPathfinder.Node n) { Vec3Point p=terrain.point(n);return Math.sqrt(p.horizontalDistanceSq(goal))+Math.abs(p.y-goal.y)*.2; }
                    },1024);
                    search.advance(1024,0);
                    expect(search.state()==GridPathfinder.State.FOUND,"actual captured jump pair is reachable by the full planner");
                    GroundRoute fresh=new GroundRoute(geometry,pit,pit,null,63,true);
                    for(int i=1;i<search.path().size();i++) expect(fresh.traversable(search.path().get(i-1),search.path().get(i)),"captured route edges independently revalidate");
                    count.put(kind,count.get(kind)+1);
                    Vec3Point end=arc.isEmpty()?to.point:arc.get(arc.size()-1);
                    report.add(kind+","+from.point.x+","+from.point.y+","+from.point.z+","+to.point.x+","+to.point.y+","+to.point.z+","+to.id+","+mode+","+arc.size()+","+end.x+","+end.y+","+end.z+","+search.path().size());
                }
            }
        }
        expect(count.get("cardinal_climb")>=2 && count.get("diagonal_climb")>=2 && count.get("slab_jump")>=2,
            "captured map exercises real cardinal climbs, diagonal climbs and jumps onto slabs: "+count);
        expect(count.get("slab_walk")>=2,"actual captured slab tops support walking transitions");
        expect(count.get("diagonal_gap")>=2,"captured diagonal gap category must not silently disappear");
        Path artifact=Paths.get("diagnostics","captured-parkour-0.4.23.csv");Files.createDirectories(artifact.getParent());Files.write(artifact,report,StandardCharsets.UTF_8);
        System.out.println("Captured parkour pairs: "+count+"; report="+artifact);
        System.out.println("PASS: "+checks+" actual captured-map jump arcs, slab tops and independent route checks");
    }
}