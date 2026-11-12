package dev.optimus.core;

import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.GridPathfinder;
import dev.optimus.core.navigation.GroundRoute;

import dev.optimus.core.navigation.GridPathfinder.Node;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Produce a local editor map and directed, collision-model-checked navigation graph. */
public final class WaypointMapExporter {
    public static void main(String[] args) throws Exception {
        String resource="duels-pit-2026-10-15.tsv.gz";
        byte[] capture;
        try(InputStream in=CollisionReplaySelfTest.class.getResourceAsStream(resource);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(in==null) throw new IOException("Missing pit collision capture");
            byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1) out.write(buffer,0,count);capture=out.toByteArray();
        }
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(capture)) hash.append(String.format(Locale.ROOT,"%02x",b&255));
        StringBuilder boxes=new StringBuilder();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(capture)),StandardCharsets.UTF_8))) {
            String line;while((line=in.readLine())!=null) if(line.startsWith("B ")) {
                String[] p=line.split(" ");if(boxes.length()>0) boxes.append(',');boxes.append('[');
                for(int i=5;i<=10;i++) { if(i>5) boxes.append(',');boxes.append(p[i]); }
                boxes.append(',').append(p[4]).append(']');
            }
        }
        PitRegion pit=new PitRegion();pit.setCornerA(new Vec3Point(-25,62.5,-127));pit.setCornerB(new Vec3Point(25,78,-77));
        CollisionGeometry geometry=CollisionReplaySelfTest.capture(resource).cached();
        GroundRoute route=new GroundRoute(geometry,pit,pit,null,63,true);
        Map<Node,Integer> ids=new HashMap<>();Map<String,Integer> unique=new HashMap<>();
        List<Node> nodes=new ArrayList<>();List<Vec3Point> points=new ArrayList<>();
        for(int x=-25;x<=25;x++) for(int z=-127;z<=-77;z++) for(int y2=125;y2<=153;y2++) {
            Node n=new Node(x,y2,z);Vec3Point p=route.point(n);if(p==null) continue;
            Integer id=unique.get(p.serialize());
            if(id==null) { id=nodes.size();unique.put(p.serialize(),id);nodes.add(n);points.add(p); }
            ids.put(n,id);
        }
        StringBuilder edges=new StringBuilder(),chains=new StringBuilder(),precision=new StringBuilder();int edgeCount=0,chainCount=0,precisionCount=0;
        int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
        for(int i=0;i<nodes.size();i++) {
            Node n=nodes.get(i);Vec3Point from=points.get(i);Set<Integer> neighbors=new TreeSet<>();
            for(int[] d:directions) for(int h:new int[]{0,1,2,-1,-2,-3,-4,-5,-6}) {
                Integer id=ids.get(new Node(n.x+d[0],n.y2+h,n.z+d[1]));if(id!=null) neighbors.add(id);
            }
            for(Node other:route.extraNeighbors(n)) { Integer id=ids.get(other);if(id!=null) neighbors.add(id); }
            for(int j:neighbors) {
                if(i==j) continue;Vec3Point to=points.get(j);
                if(!route.edgeClear(from,to)) continue;
                int kind=geometry.autoStepClear(from,to,pit)?0:route.drop(from,to)?3:from.horizontalDistanceSq(to)<=2.01 && to.y>from.y+.6?1:2;
                double cost=Math.sqrt(from.horizontalDistanceSq(to))+Math.abs(from.y-to.y)*.2+(kind==1?.8:kind==2?2:kind==3?.6:0);
                if(edges.length()>0) edges.append(',');edges.append('[').append(i).append(',').append(j).append(',').append(kind).append(',').append(round(cost)).append(']');edgeCount++;
            }
            if(i%500==0) System.out.println("Waypoint map: "+i+"/"+nodes.size()+" surfaces, "+edgeCount+" directed connections");
        }
        for(int i=0;i<nodes.size();i++) {
            Node n=nodes.get(i);Vec3Point from=points.get(i);
            for(int[] d:directions) for(int side=-1;side<=1;side++) {
                Integer j=ids.get(new Node(n.x+d[0]*6+d[1]*side,n.y2-2,n.z+d[1]*6+d[0]*side));
                if(j==null) continue;
                Vec3Point to=points.get(j);EditorMomentumJump.Plan plan=EditorMomentumJump.find(geometry,pit,from,to);
                if(plan==null) continue;
                edges.append(',').append('[').append(i).append(',').append(j).append(",4,12.0]");edgeCount++;
                if(chains.length()>0) chains.append(',');
                chains.append("{\"from\":").append(i).append(",\"to\":").append(j).append(",\"yaw\":").append(plan.yaw).append(",\"points\":[");
                point(chains,from);chains.append(',');
                for(Vec3Point p:plan.points) { point(chains,p);chains.append(','); }
                point(chains,to);chains.append("]}");chainCount++;
            }
        }
        for(int i=0;i<nodes.size();i++) {
            Node n=nodes.get(i);Vec3Point from=points.get(i);
            for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++) {
                if(Math.max(Math.abs(dx),Math.abs(dz))!=4 || Math.min(Math.abs(dx),Math.abs(dz))<2) continue;
                // Interior cells can walk to a corner first. Search the expensive
                // fractional launch family at exposed/obstructed platform corners.
                if(geometry.standable(pit,new Vec3Point(from.x+Math.signum(dx)*.9,from.y,from.z))
                    || geometry.standable(pit,new Vec3Point(from.x,from.y,from.z+Math.signum(dz)*.9))) continue;
                for(int height:new int[]{-2,2}) {
                    Integer j=ids.get(new Node(n.x+dx,n.y2+height,n.z+dz));if(j==null) continue;
                    Vec3Point to=points.get(j);if(route.edgeClear(from,to)) continue;
                    EditorPrecisionJump.Plan plan=EditorPrecisionJump.find(geometry,pit,from,to);if(plan==null) continue;
                    edges.append(',').append('[').append(i).append(',').append(j).append(",5,").append(round(Math.sqrt(from.horizontalDistanceSq(to))+4)).append(']');edgeCount++;
                    if(precision.length()>0) precision.append(',');
                    precision.append("{\"from\":").append(i).append(",\"to\":").append(j).append(",\"yaw\":").append(plan.yaw)
                        .append(",\"speed\":").append(plan.speed).append(",\"runUp\":");point(precision,plan.runUp);precision.append(",\"points\":[");
                    point(precision,from);precision.append(',');point(precision,plan.runUp);precision.append(',');
                    for(Vec3Point p:plan.points) { point(precision,p);precision.append(','); }
                    point(precision,to);precision.append("]}");precisionCount++;
                }
            }
            if(i%500==0) System.out.println("Precision map: "+i+"/"+nodes.size()+" surfaces, "+precisionCount+" checked launches");
        }
        StringBuilder json=new StringBuilder("window.PIT_MAP={\"schema\":1,\"editorBuild\":\"workshop-r4\",\"mapHash\":\"").append(hash)
            .append("\",\"bounds\":[-25,62.5,-127,25,78,-77],\"boxes\":[").append(boxes).append("],\"nodes\":[");
        for(int i=0;i<points.size();i++) { if(i>0) json.append(',');Vec3Point p=points.get(i);json.append('[').append(p.x).append(',').append(p.y).append(',').append(p.z).append(']'); }
        json.append("],\"edges\":[").append(edges).append("],\"momentumChains\":[").append(chains).append("],\"precisionJumps\":[").append(precision).append("]};\n");
        Path destination=Paths.get(args.length==0?"editor/pit-map.js":args[0]);Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.write(destination,json.toString().getBytes(StandardCharsets.UTF_8));
        if(nodes.size()<1000 || edgeCount<nodes.size()) throw new AssertionError("Incomplete waypoint map export");
        System.out.println("PASS: waypoint editor map exported "+nodes.size()+" surfaces and "+edgeCount+" directed model connections ("+chainCount+" momentum chains, "+precisionCount+" precision jumps)");
    }
    private static void point(StringBuilder out,Vec3Point p) { out.append('[').append(p.x).append(',').append(p.y).append(',').append(p.z).append(']'); }
    private static double round(double value) { return Math.round(value*1000)/1000.0; }
}