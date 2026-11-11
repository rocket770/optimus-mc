package dev.optimus.client;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real isolated loopback requests and terminal script; no Minecraft instance or server connection. */
public final class TerminalBridgeSelfTest {
    private static int checks;
    private static void expect(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void rejected(String command) {
        boolean refused=false;
        try { TerminalBridge.parse(command,"Bot_One"); } catch(IllegalArgumentException error) { refused=true; }
        expect(refused,"reject "+command);
    }
    private static JsonObject exchange(JsonObject info,String command,String token) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",info.get("port").getAsInt())) {
            socket.setSoTimeout(5000);
            JsonObject input=new JsonObject(); input.addProperty("token",token); input.addProperty("command",command);
            Writer writer=new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8);
            writer.write(input.toString()+"\n"); writer.flush();
            BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            return new JsonParser().parse(reader.readLine()).getAsJsonObject();
        }
    }
    private static void awaitPending(TerminalBridge bridge) throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(bridge.pendingCount()==0 && System.nanoTime()<end) Thread.sleep(2);
        expect(bridge.pendingCount()>0,"request enters client-thread queue");
    }
    private static List<String> success(String[] args) { return Collections.singletonList("processed "+Arrays.toString(args)); }
    public static void main(String[] args) throws Exception {
        for(String prefix:new String[]{"","!o ","/o ","o "}) {
            expect(TerminalBridge.parse(prefix+"startZ","Bot_One")[0].equals("start"),"start alias "+prefix);
            expect(TerminalBridge.parse(prefix+"target add Rival_2","Bot_One")[0].equals("add"),"target edit "+prefix);
            expect(TerminalBridge.parse(prefix+"target all","Bot_One")[0].equals("all"),"all-target command "+prefix);
            expect(TerminalBridge.parse(prefix+"weapon fist","Bot_One")[0].equals("weapon"),"fist command "+prefix);
            expect(TerminalBridge.parse(prefix+"operator add Helper_One","Bot_One")[0].equals("operator"),"operator management "+prefix);
            expect(TerminalBridge.parse(prefix+"say hello  world","Bot_One")[1].equals("hello  world"),"preserved speech spacing "+prefix);
        }
        expect(TerminalBridge.parse("!o @bot_one stop","Bot_One")[0].equals("stop"),"case-insensitive exact route");
        expect(TerminalBridge.parse("all status","Bot_One")[0].equals("status"),"all route");
        expect(TerminalBridge.parse("","Bot_One")[0].equals("help"),"empty input help");
        for(String value:new String[]{"@Other start","!x start","/x start","x start","/lobby duels","locraw","controller off","preset supplied","pit a",
            "start unexpected","say bad\nstart","say bad\u00a7a","\tstop",new String(new char[257]).replace('\0','a')}) rejected(value);
        Path directory=Files.createTempDirectory("optimus-terminal-test-");
        ExecutorService clients=Executors.newFixedThreadPool(2);
        File descriptor;
        try(TerminalBridge bridge=new TerminalBridge(directory.toFile(),"Bot_One",directory.toFile(),"test")) {
            descriptor=bridge.descriptor();
            JsonObject info=new JsonParser().parse(new String(Files.readAllBytes(descriptor.toPath()),StandardCharsets.UTF_8)).getAsJsonObject();
            String token=info.get("token").getAsString();
            expect(info.get("host").getAsString().equals("127.0.0.1"),"loopback only discovery address");
            expect(info.get("protocol").getAsInt()==1 && token.matches("[a-f0-9]{64}"),"session secret and protocol");
            expect(!exchange(info,"start","wrong-secret").get("ok").getAsBoolean(),"wrong token cannot enqueue a start");
            expect(bridge.pendingCount()==0,"authentication rejection has no client-side command");
            expect(!exchange(info,"controller off",token).get("ok").getAsBoolean(),"restricted vocabulary before dispatch");
            expect(bridge.pendingCount()==0,"unsupported command has no queued effect");
            Future<JsonObject> reply=clients.submit(() -> exchange(info,"target add Rival_2",token));
            awaitPending(bridge);
            final Thread clientThread=Thread.currentThread();
            final int[] calls={0};
            bridge.pump(command -> { expect(Thread.currentThread()==clientThread,"handler executes only in client pump"); calls[0]++; return success(command); });
            JsonObject result=reply.get(2,TimeUnit.SECONDS);
            expect(result.get("ok").getAsBoolean() && result.get("bot").getAsString().equals("Bot_One"),"identity-tagged reply");
            expect(result.getAsJsonArray("messages").get(0).getAsString().contains("Rival_2"),"reply reaches terminal");
            bridge.pump(TerminalBridgeSelfTest::success); expect(calls[0]==1,"exactly one dispatch");
            Future<JsonObject> failed=clients.submit(() -> exchange(info,"start",token)); awaitPending(bridge);
            bridge.pump(command -> { throw new IllegalArgumentException("Start blocked while F9 is held."); });
            expect(!failed.get(2,TimeUnit.SECONDS).get("ok").getAsBoolean(),"handler safety denial returned");
            Future<JsonObject> expired=clients.submit(() -> exchange(info,"start",token)); awaitPending(bridge);
            Thread.sleep(2100);
            bridge.pump(command -> { throw new AssertionError("Expired start ran"); });
            expect(!expired.get(2,TimeUnit.SECONDS).get("ok").getAsBoolean(),"stale paused-client start never executes");
            Future<JsonObject> beforeEmergency=clients.submit(() -> exchange(info,"startZ",token));awaitPending(bridge);
            bridge.emergencyStop();
            bridge.pump(command -> { throw new AssertionError("Pre-F9 start rearmed after release"); });
            expect(!beforeEmergency.get(2,TimeUnit.SECONDS).get("ok").getAsBoolean(),"F9 invalidates a previously accepted start even after the key is released");
            Future<JsonObject> afterEmergency=clients.submit(() -> exchange(info,"start",token));awaitPending(bridge);
            bridge.pump(TerminalBridgeSelfTest::success);
            expect(afterEmergency.get(2,TimeUnit.SECONDS).get("ok").getAsBoolean(),"a new explicit start after F9 remains available");

            if(System.getProperty("os.name").startsWith("Windows")) {
                Process process=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-ExecutionPolicy","Bypass","-File",
                    "terminal/Optimus-Terminal.ps1","-RegistryDirectory",directory.toString(),"-Command","@Bot_One say hello  world")
                    .redirectErrorStream(true).start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                boolean scriptDispatched=false;
                while(process.isAlive() && System.nanoTime()<deadline) {
                    bridge.pump(command -> {
                        expect(command[0].equals("say") && command[1].equals("hello  world"),"PowerShell routes and preserves command payload");
                        return Collections.singletonList("script command accepted");
                    });
                    Thread.sleep(5);
                }
                if(process.isAlive()) { process.destroyForcibly(); throw new AssertionError("Terminal script hung"); }
                StringBuilder output=new StringBuilder();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                    String line; while((line=reader.readLine())!=null) output.append(line).append('\n');
                }
                scriptDispatched=output.toString().contains("[Bot_One] script command accepted");
                expect(process.exitValue()==0 && scriptDispatched,"PowerShell end-to-end command/reply: "+output);
            }
        } finally { clients.shutdownNow(); }
        expect(!descriptor.exists(),"closed client removes discoverable session");
        shutdownChecks(directory);
        Files.delete(directory);
        System.out.println("PASS: "+checks+" terminal parsing, authentication, client-thread dispatch, expiry and PowerShell round-trip checks");
    }

    private static void shutdownChecks(Path directory) throws Exception {
        List<Socket> sockets = new ArrayList<>();
        try (TerminalBridge bridge = new TerminalBridge(directory.toFile(), "Shutdown_Bot", directory.toFile(), "test")) {
            JsonObject info = new JsonParser().parse(new String(Files.readAllBytes(bridge.descriptor().toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            java.lang.reflect.Field field = TerminalBridge.class.getDeclaredField("connections");
            field.setAccessible(true);
            Set<?> accepted = (Set<?>) field.get(bridge);
            // Four workers block reading; four more connections wait in the executor queue.
            for (int i = 0; i < 8; i++) {
                Socket socket = new Socket("127.0.0.1", info.get("port").getAsInt());
                socket.setSoTimeout(500);
                sockets.add(socket);
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (accepted.size() < sockets.size() && System.nanoTime() < deadline) Thread.sleep(2);
            expect(accepted.size() == sockets.size(), "shutdown fixture includes queued and running connections");
            bridge.close();
            for (Socket socket : sockets) {
                boolean closed;
                try { closed = socket.getInputStream().read() == -1; }
                catch (SocketException reset) { closed = true; }
                expect(closed, "shutdown closes every accepted connection, including discarded workers");
            }
            expect(!bridge.descriptor().exists(), "busy shutdown removes the discovery descriptor");
        } finally {
            for (Socket socket : sockets) socket.close();
        }
    }
}