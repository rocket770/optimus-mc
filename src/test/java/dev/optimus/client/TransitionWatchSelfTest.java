package dev.optimus.client;

import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.control.ChatMessage;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S40PacketDisconnect;

/** Real Netty pipeline and mapped vanilla packets; no running client required. */
public final class TransitionWatchSelfTest {
    private static int checks;
    private static void expect(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static void verify(LobbySafety safety, Object world) {
        safety.request("mc.hypixel.net", world, 1000);
        expect(safety.confirm("mc.hypixel.net", world, 1100, "DUELS", "duelslobby28", "dynamiclobby14F", false),
            "fixture confirms lobby");
    }
    public static void main(String[] args) {
        LobbySafety safety = new LobbySafety();
        Object world = new Object();
        TransitionWatch observer = new TransitionWatch(safety, null);
        EmbeddedChannel channel = new EmbeddedChannel(observer);
        Object[] transitions = { new S01PacketJoinGame(), new S07PacketRespawn(), new S40PacketDisconnect() };
        for (Object packet : transitions) {
            long before = observer.serial(), hardBefore = observer.hardSerial(), fatalBefore = observer.fatalSerial();
            verify(safety, world);
            channel.writeInbound(packet);
            expect(observer.serial() == before + 1, "every transition changes observer serial");
            expect(observer.hardSerial() == hardBefore + ((packet instanceof S01PacketJoinGame || packet instanceof S40PacketDisconnect) ? 1 : 0),
                "same-world respawn distinguished from new server join");
            expect(observer.fatalSerial() == fatalBefore + (packet instanceof S40PacketDisconnect ? 1 : 0),
                "disconnect cannot be recovered");
            expect(!safety.verified("mc.hypixel.net", world, 1200), "packet invalidates same-world evidence before forwarding");
            expect(channel.readInbound() == packet, "incoming transition is passed through unchanged");
            safety.request("mc.hypixel.net", world, 1300);
            channel.writeInbound(packet);
            expect(!safety.confirm("mc.hypixel.net", world, 1400, "DUELS", "duelslobby28", "dynamiclobby14F", false),
                "transition also cancels pending locraw reply");
            expect(channel.readInbound() == packet, "pending request transition passes through unchanged");
            verify(safety,world);
            expect(safety.refresh("mc.hypixel.net",world,61100) && safety.verified("mc.hypixel.net",world,61101),"background refresh permits control before a transition");
            channel.writeInbound(packet);
            expect(!safety.verified("mc.hypixel.net",world,61102) && !safety.pending(),"real transition packet immediately cancels nonblocking refresh evidence");
            expect(!safety.confirm("mc.hypixel.net",world,61103,"DUELS","duelslobby28","dynamiclobby14F",false),"stale background reply cannot reauthorize after a packet transition");
            expect(channel.readInbound()==packet,"background-refresh transition packet passes through unchanged");
        }
        // Position/look updates are not lifecycle transitions. Cover every
        // relative-flag combination, without a distance-based teleport heuristic.
        verify(safety,world);
        long correctionSerial=observer.serial(),correctionHard=observer.hardSerial(),correctionFatal=observer.fatalSerial();
        S08PacketPlayerPosLook.EnumFlags[] flags=S08PacketPlayerPosLook.EnumFlags.values();
        for(int mask=0;mask<(1<<flags.length);mask++) {
            java.util.Set<S08PacketPlayerPosLook.EnumFlags> relative=java.util.EnumSet.noneOf(S08PacketPlayerPosLook.EnumFlags.class);
            for(int bit=0;bit<flags.length;bit++) if((mask&(1<<bit))!=0) relative.add(flags[bit]);
            S08PacketPlayerPosLook packet=new S08PacketPlayerPosLook(mask%2==0?.1:100,70,-100,90,-15,relative);
            channel.writeInbound(packet);
            expect(observer.serial()==correctionSerial && observer.hardSerial()==correctionHard && observer.fatalSerial()==correctionFatal,
                "position/look packet does not trigger teleport verification");
            expect(safety.verified("mc.hypixel.net",world,1200) && !safety.pending(),"position update retains active lobby evidence");
            expect(channel.readInbound()==packet,"all position/look fields and flags pass to vanilla unchanged");
        }
        safety.request("mc.hypixel.net",world,1300);
        S08PacketPlayerPosLook correction=new S08PacketPlayerPosLook();channel.writeInbound(correction);
        expect(safety.pending() && safety.blocking(),"position update cannot cancel an initial or respawn location reply");
        expect(safety.confirm("mc.hypixel.net",world,1400,"DUELS","duelslobby28","dynamiclobby14F",false),"initial/respawn reply remains acceptable after position update");
        expect(channel.readInbound()==correction,"position update during initial verification still forwards");
        expect(safety.refresh("mc.hypixel.net",world,61400),"background correction fixture starts minute refresh");
        correction=new S08PacketPlayerPosLook();channel.writeInbound(correction);
        expect(safety.pending() && !safety.blocking() && safety.verified("mc.hypixel.net",world,61401),"position update preserves nonblocking background refresh");
        expect(safety.confirm("mc.hypixel.net",world,61402,"DUELS","duelslobby28","dynamiclobby14F",false),"background reply remains acceptable after position update");
        expect(channel.readInbound()==correction,"background position update forwards unchanged");
        String[] authorized = { "/lobby duels", "/swaplobby 1" };
        for (String command : authorized) {
            verify(safety, world);
            long fatalBefore = observer.fatalSerial();
            expect(observer.authorizeLobbyCommand(command), "controller can authorize exact lobby-only command");
            expect(!observer.authorizeLobbyCommand(command), "cannot overwrite a pending command token");
            C01PacketChatMessage packet = new C01PacketChatMessage(command);
            channel.writeOutbound(packet);
            expect(observer.fatalSerial() == fatalBefore, "owned lobby join preserves run intent only");
            expect(!safety.verified("mc.hypixel.net", world, 1200), "owned command still invalidates location before send");
            expect(channel.readOutbound() == packet, "owned command passed through unchanged");
            channel.writeOutbound(packet);
            expect(observer.fatalSerial() == fatalBefore + 1, "one-shot token cannot exempt later manual command");
            expect(channel.readOutbound() == packet, "later manual command passed through unchanged");
        }
        expect(!observer.authorizeLobbyCommand("/play duels_classic_duel"), "queue commands never get an exemption");
        expect(!observer.authorizeLobbyCommand("/swaplobby 2"), "other lobby number cannot get an exemption");
        expect(!observer.authorizeLobbyCommand("/lobby bedwars"), "other game cannot get an exemption");
        verify(safety, world);
        Object ordinary = new Object();
        channel.writeInbound(ordinary);
        expect(safety.verified("mc.hypixel.net", world, 1200), "unrelated traffic does not alter verification");
        expect(channel.readInbound() == ordinary, "ordinary traffic unchanged");
        C01PacketChatMessage locraw = new C01PacketChatMessage("/locraw");
        channel.writeOutbound(locraw);
        expect(safety.verified("mc.hypixel.net", world, 1200), "location request preserves evidence for refresh");
        expect(channel.readOutbound() == locraw, "location request unchanged");
        long plainSerial=observer.serial(), plainFatal=observer.fatalSerial();
        for(String text:new String[]{"Hello from the bot", "!o stop", "DG: Stopped; automation is off."}) {
            C01PacketChatMessage plain=new C01PacketChatMessage(ChatMessage.validate(text));
            channel.writeOutbound(plain);
            expect(safety.verified("mc.hypixel.net",world,1200),"plain speech/replies preserve evidence");
            expect(observer.serial()==plainSerial && observer.fatalSerial()==plainFatal,"plain chat cannot trigger a server-command transition");
            expect(channel.readOutbound()==plain,"plain message passed through unchanged");
        }
        String[] commands = { "/play duels_classic_duel", "/lobby duels", "/duel Example_Bot", "/rejoin" };
        for (String command : commands) {
            verify(safety, world);
            C01PacketChatMessage packet = new C01PacketChatMessage(command);
            channel.writeOutbound(packet);
            expect(!safety.verified("mc.hypixel.net", world, 1200), "slash command invalidates before sending: " + command);
            expect(channel.readOutbound() == packet, "manual command is passed through unchanged");
        }
        verify(safety, world);
        channel.close();
        expect(!safety.verified("mc.hypixel.net", world, 1200), "disconnect invalidates evidence");
        channel.finish();
        System.out.println("PASS: " + checks + " real packet/pipeline transition checks");
    }
}