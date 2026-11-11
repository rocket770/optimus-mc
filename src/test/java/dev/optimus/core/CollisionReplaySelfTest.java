package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;
import dev.optimus.core.navigation.LobbyTravel;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import dev.optimus.core.navigation.GridPathfinder.Node;

/** Regression against native collision boxes captured read-only from the user's Duels lobby. */
public final class CollisionReplaySelfTest {
    private static int checks;
    private static void expect(boolean b,String why) { checks++;if(!b) throw new AssertionError(why); }
    private static String key(int x,int y,int z) { return x+":"+y+":"+z; }
    public static CollisionGeometry capture() throws Exception {
        return capture("duels-collision.tsv.gz");
    }
    public static CollisionGeometry capture(String resource) throws Exception {
        InputStream input=CollisionReplaySelfTest.class.getResourceAsStream(resource);
        if(input==null) throw new IOException("Missing captured Duels collision fixture");
        Map<String,List<CollisionGeometry.Box>> boxes=new HashMap<>(); Set<String> hazards=new HashSet<>(),loaded=new HashSet<>();
        int[] bounds=new int[6];
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(input),"UTF-8"))) {
            String[] header=reader.readLine().split(" "); for(int i=0;i<6;i++) bounds[i]=Integer.parseInt(header[i+1]);
            String line;
            while((line=reader.readLine())!=null) {
                String[] p=line.split(" ");
                if(p[0].equals("L")) loaded.add(p[1]+":"+p[2]);
                if(p[0].equals("H")) hazards.add(p[1]+":"+p[2]+":"+p[3]);
                if(p[0].equals("B")) {
                    String k=p[1]+":"+p[2]+":"+p[3];
                    boxes.computeIfAbsent(k,v->new ArrayList<>()).add(new CollisionGeometry.Box(
                        Double.parseDouble(p[5]),Double.parseDouble(p[6]),Double.parseDouble(p[7]),
                        Double.parseDouble(p[8]),Double.parseDouble(p[9]),Double.parseDouble(p[10])));
                }
            }
        }
        return new CollisionGeometry((x,y,z)-> {
            if(x<bounds[0] || x>bounds[1] || y<bounds[2] || y>bounds[3] || z<bounds[4] || z>bounds[5] || !loaded.contains(x+":"+z)) return CollisionGeometry.Cell.UNLOADED;
            String k=key(x,y,z);List<CollisionGeometry.Box> shapes=boxes.get(k);
            return new CollisionGeometry.Cell(true,hazards.contains(k),shapes==null?Collections.emptyList():shapes);
        });
    }
    private static PitRegion pit() { PitRegion p=new PitRegion();p.setCornerA(new Vec3Point(-25,62.5,-127));p.setCornerB(new Vec3Point(25,78,-77));return p; }
    private static GroundRoute terrain(CollisionGeometry geometry,PitRegion area) {
        return new GroundRoute(geometry.cached(),area,pit(),new Vec3Point(-2,78,-78),63,true,true);
    }
    private static List<Node> route(CollisionGeometry geometry,Vec3Point from,Vec3Point to,PitRegion area) {
        GroundRoute route=terrain(geometry,area); Node start=route.start(from);
        expect(start!=null,"actual failed position has a safe grid alignment: "+from.serialize());
        GridPathfinder.Search search=new GridPathfinder.Search(start,route,new GridPathfinder.Goal() {
            public boolean reached(Node n) { Vec3Point p=route.point(n);return p.horizontalDistanceSq(to)<.45*.45 && Math.abs(p.y-to.y)<.6; }
            public double estimate(Node n) { Vec3Point p=route.point(n);return 1.2*Math.sqrt(p.horizontalDistanceSq(to)); }
        },65536);
        int slices=0;
        while(search.state()==GridPathfinder.State.SEARCHING && slices++<65536) search.advance(256,5000000L);
        System.out.println("Captured route "+from.serialize()+" -> "+to.serialize()+": "+search.state()+", "+search.visited()+" cells, "+slices+" slices, "+search.path().size()+" waypoints");
        expect(search.state()==GridPathfinder.State.FOUND,"real captured route must exist, not timeout/no path");
        GroundRoute fresh=terrain(geometry,area);
        for(int i=0;i<search.path().size();i++) {
            Vec3Point p=route.point(search.path().get(i));expect(geometry.standable(area,p),"each captured waypoint has actual floor/body clearance");
            if(i>0) expect(fresh.edgeClear(route.point(search.path().get(i-1)),p),"every actual-map edge revalidates independently");
        }
        return search.path();
    }
    public static void main(String[] args) throws Exception {
        CollisionGeometry geometry=capture(); PitRegion travel=LobbyTravel.area();
        expect(geometry.standable(travel,new Vec3Point(.686,70,25.588)),"failed normal lobby spawn has floor");
        expect(geometry.standable(pit(),new Vec3Point(-.293,69,-78.888)),"failed pit position has floor");
        Vec3Point landing=geometry.landingBelow(new Vec3Point(.5,71,28.5),travel);
        expect(landing!=null && landing.y==70,"server-granted flight at actual lobby spawn resolves safe ground below");
        Vec3Point entry=terrain(geometry,travel).entry(new Vec3Point(-1.5,63,-79.5));
        expect(entry!=null && entry.x==-1.5 && entry.y==69 && entry.z==-79.5,"automatic entry resolver selects actual supported landing");
        route(geometry,new Vec3Point(.686,70,25.588),entry,travel);
        route(geometry,new Vec3Point(.5,70,28.5),entry,travel);
        route(geometry,new Vec3Point(-.293,69,-78.888),new Vec3Point(.5,63,-100.5),pit());
        Vec3Point pitCenter=new Vec3Point(.5,63,-100.5);
        expect(geometry.pursuitClear(pitCenter,new Vec3Point(6.5,63,-100.5),pit()),"actual open pit corridor supports immediate six-block pursuit");
        // The capture contains alternating lower/upper stair treads here, not a wall.
        expect(geometry.pursuitClear(pitCenter,new Vec3Point(.5,63,-103.5),pit()),"actual half-stair dips permit continuous pursuit instead of unnecessary route mode");
        expect(!geometry.pursuitClear(pitCenter,new Vec3Point(3.5,63,-106.5),pit()),"actual solid pit obstacle still rejects direct pursuit between supported endpoints");
        expect(geometry.knockbackControlClear(new Vec3Point(.5,64,-100.5),new Vec3Point(.7,64,-100.5),pit()),"actual pit floor supports checked airborne knockback control");
        // Fractional surfaces and hazards below a floor are separate from actual body contact.
        CollisionGeometry carpet=new CollisionGeometry((x,y,z)-> {
            if(y==0) return new CollisionGeometry.Cell(true,true,Collections.emptyList());
            if(y==1) return new CollisionGeometry.Cell(true,false,Arrays.asList(new CollisionGeometry.Box(x,1,z,x+1,2.0625,z+1)));
            return CollisionGeometry.Cell.AIR;
        });
        Vec3Point fractional=carpet.surface(new Vec3Point(.5,2,.5),travel);
        expect(fractional!=null && fractional.y==2.0625,"carpet top resolved without half-block snapping");
        CollisionGeometry coveredHazard=new CollisionGeometry((x,y,z)-> {
            if(y==0) return new CollisionGeometry.Cell(true,true,Collections.emptyList());
            if(y==1) return new CollisionGeometry.Cell(true,false,Arrays.asList(new CollisionGeometry.Box(x,1,z,x+1,2,z+1)));
            return CollisionGeometry.Cell.AIR;
        });
        expect(coveredHazard.standable(travel,new Vec3Point(.5,2,.5)),"floor probe querying hazard below solid floor ignores its non-overlapping cell");
        System.out.println("PASS: "+checks+" captured-map collision and route checks");
    }
}