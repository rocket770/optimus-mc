package dev.optimus.client;

import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
import dev.optimus.core.control.RemoteCommand;
import java.util.Arrays;

/** Actual vanilla component trees: server sender metadata, never text-only identity. */
public final class RemoteChatSelfTest {
    private static int checks;
    private static void expect(boolean condition,String why) { checks++; if(!condition) throw new AssertionError(why); }
    private static ChatComponentText tagged(String visible,ClickEvent.Action action,String value) {
        ChatComponentText name=new ChatComponentText(visible);
        name.setChatStyle(new ChatStyle().setChatClickEvent(new ClickEvent(action,value)));
        return name;
    }
    private static IChatComponent message(String prefix,String visible,ClickEvent.Action action,String value,String suffix) {
        ChatComponentText root=new ChatComponentText(prefix);
        root.appendSibling(tagged(visible,action,value)); root.appendText(suffix); return root;
    }
    private static void accepted(IChatComponent message,String sender,String body) {
        RemoteChat.Envelope e=RemoteChat.extract(message);
        expect(e!=null && e.sender.equals(sender) && e.body.equals(body),"metadata-authenticated sender/body");
    }
    private static void ignored(IChatComponent message,String why) { expect(RemoteChat.extract(message)==null,why); }
    public static void main(String[] args) throws Exception {
        try(InputStream fixture=RemoteChatSelfTest.class.getResourceAsStream("captured-controller-chat.json")) {
            expect(fixture!=null,"captured live Hypixel command fixture packaged");
            String json=new JsonParser().parse(new InputStreamReader(fixture,StandardCharsets.UTF_8)).toString();
            IChatComponent captured=IChatComponent.Serializer.jsonToComponent(json);
            accepted(captured,"FixtureUser","!o say hi");
            expect("FixtureUser".equals(RemoteChat.headerHint(captured)),"actual ranked formatted header has correct diagnostic owner hint");
            RemoteChat.Envelope envelope=RemoteChat.extract(captured);
            expect(Arrays.equals(RemoteCommand.parse(envelope.body,"Bot_One"),new String[]{"say","hi"}),"captured message reaches executable speech arguments");
            String ownerId="00000000-0000-4000-8000-000000000001";
            expect(RemoteCommand.ownerMatches("FixtureUser",ownerId,envelope.sender,ownerId),"captured sender passes bound owner identity");
            expect(!RemoteCommand.ownerMatches("Other_Main",ownerId,envelope.sender,ownerId),"captured sender cannot control another main's bot");
        }
        accepted(message("(S7) NoDebuff Division 1 [MVP+] ","FixtureUser",ClickEvent.Action.RUN_COMMAND,"/viewprofile FixtureUser",": !o say hi"),"FixtureUser","!o say hi");
        ignored(message("", "Main_Name",ClickEvent.Action.SUGGEST_COMMAND,"/viewprofile Main_Name",": !o start"),"profile suggestion cannot authenticate sender");
        ignored(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/viewprofile Main_Name extra",": !o start"),"unknown profile command parameters rejected");
        ignored(message("", "Evil_Name",ClickEvent.Action.RUN_COMMAND,"/viewprofile Main_Name",": !o start"),"profile metadata must match visible header name");
        ChatComponentText profileBody=new ChatComponentText("Main_Name: ");
        profileBody.appendSibling(tagged("!o say Main_Name",ClickEvent.Action.RUN_COMMAND,"/viewprofile Main_Name"));
        ignored(profileBody,"profile action in body cannot authenticate sender");
        accepted(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name",": !o start"),"Main_Name","!o start");
        accepted(message("\u00a7b[MVP+] ","\u00a7bMain_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name", "\u00a7f: !O @Bot_One say Hello  there"),"Main_Name","!O @Bot_One say Hello  there");
        accepted(message("Party > [MVP+] ","Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions sb Main_Name extra",": !o stop"),"Main_Name","!o stop");
        accepted(message("From ","Main_Name",ClickEvent.Action.SUGGEST_COMMAND,"/msg Main_Name",": !o target add Example_Bot"),"Main_Name","!o target add Example_Bot");
        accepted(message("[VIP] ","Main_Name",ClickEvent.Action.SUGGEST_COMMAND,"/msg Main_Name ",": !o"),"Main_Name","!o");
        ChatComponentText nested=new ChatComponentText("[MVP+] ");
        ChatComponentText wrapper=tagged("",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name");
        wrapper.appendText("Main_Name"); nested.appendSibling(wrapper).appendText(": !o status");
        accepted(nested,"Main_Name","!o status");
        ignored(new ChatComponentText("Main_Name: !o stop"),"raw name prefix is insufficient");
        ignored(message("", "Evil_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Evil_Name",": Main_Name: !o start"),"player body cannot forge header");
        accepted(message("", "Evil_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Evil_Name",": !o say Main_Name: !o start"),"Evil_Name","!o say Main_Name: !o start");
        ignored(message("", "Evil_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name",": !o start"),"metadata name must be visible in header");
        ignored(message("", "Main_NameExtra",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name",": !o start"),"partial username match forbidden");
        ignored(message("", "Main_Name",ClickEvent.Action.OPEN_URL,"/socialoptions Main_Name",": !o start"),"wrong click action rejected");
        ignored(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/msg Main_Name",": !o start"),"wrong msg action rejected");
        accepted(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name extra",": !o start"),"Main_Name","!o start");
        accepted(message("(S7) NoDebuff Division 1 [MVP+] ", "FixtureUser",ClickEvent.Action.RUN_COMMAND,"/socialoptions FixtureUser message context",": !o say hi"),"FixtureUser","!o say hi");
        expect(RemoteChat.chatType((byte)0) && RemoteChat.chatType((byte)1),"ordinary and system-channel server chat supported");
        expect(!RemoteChat.chatType((byte)2) && !RemoteChat.chatType((byte)3),"actionbar/unknown channel cannot control bot");
        IChatComponent noMetadata=new ChatComponentText("(S7) NoDebuff Division 1 [MVP+] FixtureUser: !o say hi");
        expect("FixtureUser".equals(RemoteChat.headerHint(noMetadata)),"diagnostic identifies terminal header name");
        ignored(noMetadata,"diagnostic name hint never authenticates a message");
        expect("Evil_Name".equals(RemoteChat.headerHint(new ChatComponentText("Evil_Name: !o say FixtureUser: !o start"))),"diagnostic does not use forged name in body");
        expect(RemoteChat.headerHint(new ChatComponentText("FixtureUser: ordinary message"))==null,"ordinary messages excluded from diagnostics");
        expect(RemoteChat.diagnosticJson(noMetadata).contains("FixtureUser"),"component metadata serialized for local diagnostics");
        ignored(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name",": hello !o start"),"command must lead body");
        ignored(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name",": !ouard start"),"exact command prefix required");
        ignored(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name"," !o start"),"header delimiter required");
        ignored(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name",": !o say hi\nthere"),"multiline message rejected");
        ignored(message("", "Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name",": !o say hi\rthere"),"carriage return rejected");
        ChatComponentText bodyMetadata=new ChatComponentText("Main_Name: ");
        bodyMetadata.appendSibling(tagged("!o say Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name"));
        ignored(bodyMetadata,"metadata inside body cannot authenticate sender");
        ChatComponentText ambiguous=tagged("Main_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Main_Name");
        ambiguous.appendText(" ").appendSibling(tagged("Evil_Name",ClickEvent.Action.RUN_COMMAND,"/socialoptions Evil_Name")).appendText(": !o start");
        ignored(ambiguous,"ambiguous multiple header senders rejected");
        ignored(null,"null message ignored");
        System.out.println("PASS: "+checks+" vanilla chat-component sender and spoofing checks");
    }
}