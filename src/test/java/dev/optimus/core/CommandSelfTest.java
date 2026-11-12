package dev.optimus.core;

import dev.optimus.client.ClientSettings;
import dev.optimus.core.control.ChatMessage;
import dev.optimus.core.control.CommandInput;
import dev.optimus.core.control.RemoteCommand;
import dev.optimus.core.safety.ControlPolicy;
import dev.optimus.core.safety.LobbySafety;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/** Plain-chat limits, remote routing, identity binding and persisted pairing. */
public final class CommandSelfTest {
    private static int checks;

    private static void expect(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }

    private static void rejects(Runnable action, String why) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        expect(rejected, why);
    }

    private static void parsed(String text, String... expected) {
        expect(Arrays.equals(RemoteCommand.parse(text, "Bot_One"), expected), "parse " + text);
    }

    public static void main(String[] args) throws Exception {
        expect(
                ChatMessage.validate(" Hello  friends ").equals("Hello  friends"),
                "plain payload preserves interior spaces");
        expect(
                ChatMessage.fromArguments(new String[] {"say", "Hello", "", "world"}, 1)
                        .equals("Hello  world"),
                "local argument joining");
        expect(
                ChatMessage.validate("a /play mention").equals("a /play mention"),
                "internal slash is ordinary text");
        String hundred = new String(new char[100]).replace('\0', 'a');
        expect(
                ChatMessage.validate(hundred).length() == 100,
                "vanilla 100 character limit accepted");
        rejects(
                () -> ChatMessage.validate(hundred + "a"),
                "overlength cannot be silently truncated");
        for (String bad :
                new String[] {
                    "",
                    "  ",
                    "/play duels_classic_duel",
                    " /duel Name",
                    "hello\nthere",
                    "\rhello",
                    "\thello",
                    "hello\u007f",
                    "\u00a7chello",
                    "hi\0"
                }) rejects(() -> ChatMessage.validate(bad), "reject unsafe/empty chat");
        rejects(() -> ChatMessage.validate(null), "null payload rejected");
        rejects(
                () -> ChatMessage.fromArguments(new String[] {"say"}, 1),
                "missing message rejected");
        expect(
                RemoteCommand.parse("!x start", "Bot_One") == null,
                "unregistered prefixes are ignored");
        expect(
                RemoteCommand.parse("!other start", "Bot_One") == null,
                "prefix boundaries are exact");
        parsed("!o", "help");
        parsed("!O STARTZ", "start");
        parsed("!o stopZ", "stop");
        parsed("!o all on", "start");
        parsed("!o @bot_one off", "stop");
        parsed("!o @Bot_One say Hello  world", "say", "Hello  world");
        parsed("!o all chat Hello   everyone", "say", "Hello   everyone");
        parsed("!o say !o stop", "say", "!o stop");
        parsed("!o all", "help");
        parsed("!o @Bot_One", "help");
        parsed("!o target add Example_Bot", "add", "Example_Bot");
        parsed("!o targets remove Example_Bot", "remove", "Example_Bot");
        parsed("!o target", "list");
        parsed("!o target list", "list");
        parsed("!o target clear", "clear");
        parsed("!o profile balanced", "profile", "balanced");
        rejects(
                () -> RemoteCommand.parse("!o parkour off", "Bot_One"),
                "removed travel command is rejected");
        parsed("!o seed -42", "seed", "-42");
        parsed("!o status", "status");
        parsed("!o commands", "help");
        parsed("!o target all", "all");
        parsed("!o @Bot_One targets listed", "listed");
        parsed("!o all weapon fist", "weapon", "fist");
        parsed("!o weapon sword", "weapon", "sword");
        parsed("!o operator add Helper_One", "operator", "add", "Helper_One");
        for (String allowed :
                new String[] {
                    "start",
                    "startZ",
                    "on",
                    "stop",
                    "stopZ",
                    "off",
                    "target add Rival_2",
                    "targets remove Rival_2",
                    "target list",
                    "target clear",
                    "target all",
                    "target listed",
                    "add Rival_2",
                    "remove Rival_2",
                    "list",
                    "clear",
                    "weapon fist",
                    "weapon sword"
                })
            expect(
                    RemoteCommand.parse("!o @bot_one " + allowed, "Bot_One", true) != null,
                    "operator permits " + allowed);
        for (String denied :
                new String[] {
                    "say hello",
                    "chat hello",
                    "status",
                    "help",
                    "commands",
                    "visuals on",
                    "operator add Evil_Main",
                    "operator remove Helper_One",
                    "operator clear",
                    "operator list",
                    "controller Evil_Main",
                    "profile baseline",
                    "seed 1",
                    "routes off",
                    "pit a",
                    "locraw",
                    "target weapon fist",
                    "target all extra"
                })
            rejects(
                    () -> RemoteCommand.parse("!o all " + denied, "Bot_One", true),
                    "operator denies " + denied);
        expect(
                RemoteCommand.parse("!o @Other_Bot weapon fist", "Bot_One", true) == null,
                "other-bot routing is ignored before role rejection");
        for (String ignored :
                new String[] {
                    "hello",
                    "!ouard start",
                    "x !o stop",
                    "!o @Other_Bot start",
                    "!o @Bot_OneExtra stop"
                })
            expect(
                    RemoteCommand.parse(ignored, "Bot_One") == null,
                    "unrelated or differently addressed command ignored");
        expect(RemoteCommand.parse(null, "Bot_One") == null, "null message ignored");
        for (String bad :
                new String[] {
                    "!o controller Evil_Main",
                    "!o preset supplied",
                    "!o pit a",
                    "!o nav b",
                    "!o entrance",
                    "!o locraw",
                    "!o /play duels_classic_duel",
                    "!o start extra",
                    "!o target start",
                    "!o clear extra",
                    "!o say a\nb",
                    "!o say \u00a7chello"
                })
            rejects(
                    () -> RemoteCommand.parse(bad, "Bot_One"),
                    "restricted remote vocabulary/control characters");
        String[] original = {"STARTZ"};
        CommandInput.normalize(original);
        expect(original[0].equals("STARTZ"), "normalization does not mutate caller arguments");
        String owner = "12345678-1234-1234-1234-123456789012",
                other = "87654321-4321-4321-4321-210987654321";
        expect(
                RemoteCommand.ownerMatches("Main_Name", owner, "main_name", owner),
                "name and bound UUID match");
        expect(
                !RemoteCommand.ownerMatches("Main_Name", owner, "Main_Name", other),
                "same visible name with different UUID rejected");
        expect(
                !RemoteCommand.ownerMatches("Main_Name", owner, "Other_Name", owner),
                "wrong name with same UUID rejected");
        expect(
                !RemoteCommand.ownerMatches("Main_Name", owner, "Main_Name", null),
                "missing current tab profile rejected");
        expect(
                !RemoteCommand.ownerMatches("Main_Name", "bad", "Main_Name", owner),
                "invalid saved UUID rejected");
        expect(
                !RemoteCommand.ownerMatches("", owner, "Main_Name", owner),
                "empty pairing rejected");
        Object world = new Object();
        LobbySafety safety = new LobbySafety();
        safety.request("mc.hypixel.net", world, 1000);
        expect(
                safety.confirm(
                        "mc.hypixel.net",
                        world,
                        1100,
                        "DUELS",
                        "duelslobby9",
                        "dynamiclobby33C",
                        false),
                "lobby 9 can be verified for speech");
        boolean verified = safety.verified("mc.hypixel.net", world, 1200);
        expect(ControlPolicy.allowSpeech(verified, true), "speech allowed in verified lobby 9");
        expect(
                ControlPolicy.allowNavigation(true, verified, true, true, true, false),
                "verified lobby 9 also permits guarded navigation");
        expect(!ControlPolicy.allowSpeech(verified, false), "speech requires a player");
        safety.request("mc.hypixel.net", world, 1300);
        expect(
                !ControlPolicy.allowSpeech(safety.verified("mc.hypixel.net", world, 1400), true),
                "pending refresh blocks speech");
        expect(
                !safety.confirm(
                        "mc.hypixel.net",
                        world,
                        1500,
                        "DUELS",
                        "duelslobby9",
                        "dynamiclobby33C",
                        true),
                "proper-game fields invalidate even a lobby-shaped context");
        expect(
                !ControlPolicy.allowSpeech(safety.verified("mc.hypixel.net", world, 1600), true),
                "proper game cannot authorize speech");
        expect(
                !ControlPolicy.allowSpeech(false, true),
                "unverified/private/world-changing context cannot authorize speech");
        Path directory = Files.createTempDirectory("optimus-command-test");
        Path file = directory.resolve("optimus.properties");
        try {
            ClientSettings config = new ClientSettings(directory.toFile());
            expect(
                    config.controllerName.equals(ClientSettings.DEFAULT_CONTROLLER_NAME)
                            && config.controllerUuid.equals(ClientSettings.DEFAULT_CONTROLLER_UUID),
                    "fresh controller defaults to the requested pinned account");
            expect(
                    config.swordEnabled
                            && !config.targetAll
                            && !config.debugVisuals
                            && config.operators.isEmpty(),
                    "old configs keep sword, named targets, visuals off and no operators");
            config.controllerName = "Main_Name";
            config.controllerUuid = owner;
            config.operators.put("Helper_One", other);
            config.swordEnabled = false;
            config.targetAll = true;
            config.debugVisuals = true;
            config.whitelist.add("Saved_Rival");
            config.applySuppliedArena("duelslobby1", "dynamiclobbyAB");
            config.save();
            ClientSettings loaded = new ClientSettings(directory.toFile());
            expect(
                    RemoteCommand.ownerMatches(
                            loaded.controllerName, loaded.controllerUuid, "Main_Name", owner),
                    "pairing survives save/reload and preset application");
            expect(
                    !loaded.swordEnabled
                            && loaded.targetAll
                            && loaded.debugVisuals
                            && loaded.targets("Unlisted_Rival")
                            && loaded.whitelist.contains("Saved_Rival"),
                    "fist, all-target and visual mode persist without erasing the saved list");
            expect(
                    loaded.operatorMatches("helper_one", other)
                            && !loaded.operatorMatches("Helper_One", owner)
                            && !loaded.operatorMatches("Other_Helper", other)
                            && !loaded.operatorMatches("Helper_One", null),
                    "operator binds both exact name and UUID");
            loaded.targetAll = false;
            expect(
                    !loaded.targets("Unlisted_Rival") && loaded.targets("saved_rival"),
                    "listed mode restores exact-name targeting");
            Properties malformed = new Properties();
            malformed.setProperty("control.owner.name", "Main_Name");
            malformed.setProperty("control.owner.uuid", "bad");
            malformed.setProperty("control.operator.Bad_Helper", "invalid-uuid");
            malformed.setProperty("control.operator.Bad Name", other);
            try (OutputStream out = Files.newOutputStream(file)) {
                malformed.store(out, "fixture");
            }
            ClientSettings invalid = new ClientSettings(directory.toFile());
            expect(
                    invalid.controllerName.isEmpty() && invalid.controllerUuid.isEmpty(),
                    "corrupt pairing disables control");
            expect(
                    invalid.operators.isEmpty(),
                    "corrupt operator names and UUIDs cannot authorize control");
            loaded.controllerName = loaded.controllerUuid = "";
            loaded.save();
            expect(
                    new ClientSettings(directory.toFile()).controllerName.isEmpty(),
                    "disabled pairing persists");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
        expect(
                new ClientSettings(new java.io.File("examples/pit")).arenaConfigured(),
                "the example pit configuration loads without account data");
        boolean bindingDenied = false;
        try {
            RemoteCommand.parse("!o guard keys bind", "OfflineBot");
        } catch (IllegalArgumentException expected) {
            bindingDenied = true;
        }
        expect(bindingDenied, "a chat command cannot bind a local controller window");
        System.out.println("PASS: " + checks + " chat, routing, owner identity and pairing checks");
    }
}