package dev.optimus.core;

import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.combat.CombatTurn;
import dev.optimus.core.control.TargetWhitelist;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.RouteFollower;
import dev.optimus.core.safety.ControlPolicy;
import dev.optimus.core.safety.LobbySafety;
import dev.optimus.core.safety.ServerSafety;

import dev.optimus.client.ClientSettings;
import dev.optimus.client.CombatTrace;
import java.io.File;
import java.nio.file.Files;

/** Dependency-free tests runnable without Minecraft or downloaded Forge tools. */
public final class CoreSelfTest {
    private static int checks;

    private static void expect(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        lobbySafetyChecks();
        TargetWhitelist names = new TargetWhitelist();
        expect(names.add("Bot_Xyz"), "add username");
        expect(names.contains("bot_xyz"), "case-insensitive exact username");
        expect(!names.contains("Bot_Xyz1"), "no prefix/substring match");
        expect(!names.add("bad-name"), "reject invalid name");
        expect(!names.contains("Victim123"), "never attack unknown person");
        expect(names.remove("BOT_XYZ"), "case-insensitive removal");

        PitRegion pit = new PitRegion();
        pit.setCornerA(new Vec3Point(20, 60, 20));
        expect(!pit.isDefined() && !pit.contains(new Vec3Point(20, 60, 20)), "not active with one corner");
        pit.setCornerB(new Vec3Point(10, 65, 10));
        expect(pit.contains(new Vec3Point(15, 62, 15)), "unordered bounds are fine");
        expect(!pit.contains(new Vec3Point(15, 66, 15)), "vertical pit boundary");
        expect(!pit.contains(new Vec3Point(9, 62, 15)), "horizontal pit boundary");

        RouteFollower route = new RouteFollower();
        route.add(new Vec3Point(0, 64, 0));
        route.add(new Vec3Point(5, 64, 0));
        route.add(new Vec3Point(12, 64, 0));
        expect(route.advanceIfReached(new Vec3Point(0, 64, 0), 1.3).x == 5, "advance waypoint");
        expect(route.advanceIfReached(new Vec3Point(1, 64, 0), 1.3).x == 5, "hold until reached");
        route.restart();
        expect(route.index() == 0, "restart on respawn");
        expect(Vec3Point.parse(new Vec3Point(1, 64, 2).serialize()).z == 2.0, "roundtrip coordinates");

        expect(ServerSafety.isHypixel("mc.Hypixel.net:25565"), "block Hypixel public address");
        expect(ServerSafety.isHypixel("foo.hypixel.net"), "block Hypixel subdomain");
        expect(!ServerSafety.trainingAuthorized("mc.hypixel.net", "mc.hypixel.net"), "cannot opt in Hypixel");
        expect(!ServerSafety.trainingAuthorized("mc.hypixel.net", "127.0.0.1:25565"), "cannot train on Hypixel");
        expect(!ServerSafety.trainingAuthorized("training.net", "elsewhere.net"), "must exactly match configured host");
        expect(!ServerSafety.trainingAuthorized("", "127.0.0.1"), "no world = no training");
        expect(!ServerSafety.trainingAuthorized("127.0.0.1", "127.0.0.1:25565"), "legacy private authorization is inert");

        expect(!ControlPolicy.allowAttack(true, false, true, true, true, true, true, false), "unverified lobby never attacks");
        expect(!ControlPolicy.allowAttack(true, true, false, true, true, true, true, false), "outside pit never attacks");
        expect(!ControlPolicy.allowAttack(true, true, true, false, true, true, true, false), "target outside pit never attacks");
        expect(!ControlPolicy.allowAttack(true, true, true, true, false, true, true, false), "unlisted player never attacks");
        expect(!ControlPolicy.allowAttack(true, true, true, true, true, false, true, false), "range gated");
        expect(!ControlPolicy.allowAttack(true, true, true, true, true, true, false, false), "line of sight gated");
        expect(!ControlPolicy.allowAttack(true, true, true, true, true, true, true, true), "open GUI blocks combat");
        expect(ControlPolicy.allowAttack(true, true, true, true, true, true, true, false), "verified lobby valid target permitted");
        expect(!ControlPolicy.allowNavigation(true, false, true, true, true, false), "unverified lobby cannot navigate");
        expect(!ControlPolicy.allowNavigation(true, true, false, true, true, false), "unbound navigation blocked");
        expect(ControlPolicy.allowNavigation(true, true, true, true, true, false), "verified lobby one bounded navigation");
        for (int bits = 0; bits < 64; bits++) {
            boolean armed = (bits & 1) != 0, verified = (bits & 2) != 0, bound = (bits & 4) != 0;
            boolean inside = (bits & 8) != 0, alive = (bits & 16) != 0, gui = (bits & 32) != 0;
            expect(ControlPolicy.allowControl(armed, verified, bound, inside, alive, gui) == (bits == 31),
                "only fully valid lobby/pit permits aim and movement: " + bits);
            expect(ControlPolicy.allowNavigation(armed, verified, bound, inside, alive, gui) == (bits == 31),
                "only armed, verified Duels-context guarded navigation is permitted: " + bits);
        }
        for (int bits = 0; bits < 256; bits++) {
            expect(ControlPolicy.allowAttack((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0,
                (bits & 8) != 0, (bits & 16) != 0, (bits & 32) != 0, (bits & 64) != 0, (bits & 128) != 0)
                == (bits == 127), "attack guard truth table: " + bits);
        }

        CombatTurn turn = new CombatTurn();
        expect(turn.yaw(90, CombatProfile.BASELINE) == 14, "baseline yaw cap preserved");
        expect(turn.pitch(-30, CombatProfile.BASELINE) == -8, "baseline pitch cap preserved");
        turn.reset();
        expect(turn.yaw(-358, CombatProfile.PACED) == 1.5f, "take shortest turn across yaw seam");
        expect(turn.yaw(90, CombatProfile.PACED) == 3, "paced yaw accelerates gradually");
        expect(turn.yaw(-90, CombatProfile.PACED) == 0, "do not keep turning away after target reversal");
        turn.reset();
        float angle = 0, previous = 0;
        for (int i = 0; i < 60; i++) {
            float step = turn.yaw(90 - angle, CombatProfile.PACED);
            expect(step >= 0 && step <= 6, "paced yaw rate bounded");
            expect(step - previous <= 1.5f, "paced yaw acceleration bounded");
            angle += step;
            expect(angle <= 90, "paced yaw does not overshoot");
            previous = step;
        }
        expect(angle == 90, "paced yaw converges without jitter");
        turn.reset();
        expect(turn.yaw(1, CombatProfile.PACED) == 1, "small angle snaps only to remaining gap");
        expect(turn.pitch(-90, CombatProfile.PACED) == -1.5f, "paced pitch starts gradually");
        expect(turn.pitch(-90, CombatProfile.PACED) == -3, "paced pitch accelerates");
        expect(turn.pitch(-90, CombatProfile.PACED) == -4, "paced pitch cap");
        java.util.Random first = new java.util.Random(42), second = new java.util.Random(42);
        for (int i = 0; i < 100; i++) {
            int delay = CombatProfile.PACED.attackDelay(first);
            expect(delay >= 7 && delay <= 10, "paced attack interval bounded");
            expect(delay == CombatProfile.PACED.attackDelay(second), "seed reproduces interval sequence");
        }
        expect(!CombatProfile.PACED.strafe && CombatProfile.BASELINE.strafe, "profile explicitly controls strafing");
        expect(CombatProfile.parse("PaCeD") == CombatProfile.PACED, "profile parsing ignores case");

        expect(CombatProfile.parse("BALANCED") == CombatProfile.BALANCED, "balanced selectable");
        expect(CombatProfile.BALANCED.strafe, "balanced enables combat strafing");
        int balancedAcquisition = acquisitionTicks(CombatProfile.BALANCED);
        int pacedAcquisition = acquisitionTicks(CombatProfile.PACED);
        expect(balancedAcquisition <= 6, "balanced acquires a 90-degree stationary target within six ticks");
        expect(balancedAcquisition < pacedAcquisition, "starting preset acquires faster than paced");
        turn.reset();
        angle = 0;
        for (int i = 0; i < 30; i++) {
            float desired = 90 + i * 5;
            float step = turn.yaw(desired - angle, CombatProfile.BALANCED);
            angle += step;
            expect(step >= 0 && step <= CombatProfile.BALANCED.yawLimit && angle <= desired, "moving-target tracking respects cap without overshoot");
        }
        expect(Math.abs(CombatTurn.wrap(235 - angle)) <= CombatProfile.BALANCED.yawTolerance,
            "balanced catches and tracks a target turning five degrees per tick");
        first = new java.util.Random(42);
        second = new java.util.Random(42);
        int totalDelay = 0;
        for (int i = 0; i < 100; i++) {
            int delay = CombatProfile.BALANCED.attackDelay(first);
            expect(delay >= 2 && delay <= 4, "balanced attack spacing stays in configured range");
            expect(delay == CombatProfile.BALANCED.attackDelay(second), "balanced pacing repeats with same seed");
            totalDelay += delay;
        }
        expect(totalDelay >= 250 && totalDelay <= 350, "balanced seed produces useful attack-attempt throughput");
        System.out.println("Profile model: stationary 90-degree acquisition balanced=" + balancedAcquisition
            + " ticks, paced=" + pacedAcquisition + " ticks; balanced attack-delay mean="
            + totalDelay / 100.0 + " ticks (seed 42)");

        try {
            File sandbox = Files.createTempDirectory("optimus-test").toFile();
            ClientSettings settings = new ClientSettings(sandbox);
            expect(settings.trainingServer.isEmpty(), "no default training host");
            expect(settings.combatProfile == CombatProfile.BALANCED, "new configs use capable starting profile");
            settings.combatProfile = CombatProfile.PACED;
            settings.combatSeed = -42;
            settings.trainingServer = "127.0.0.1:25565";
            settings.whitelist.add("Example_Bot");
            settings.hypixelPit.setCornerA(new Vec3Point(1, 70, 1));
            settings.hypixelPit.setCornerB(new Vec3Point(9, 75, 9));
            settings.pitLobby = "duelslobby28";
            settings.pitInstance = "dynamiclobby14F";
            settings.trainingPit.setCornerA(new Vec3Point(10, 60, 10));
            settings.trainingPit.setCornerB(new Vec3Point(20, 67, 20));
            settings.trainingRoute.add(new Vec3Point(4, 64, 6));
            settings.save();
            configReplacementChecks(settings, sandbox);
            ClientSettings reloaded = new ClientSettings(sandbox);
            expect(reloaded.whitelist.contains("example_bot"), "whitelist persisted");
            expect(reloaded.trainingPit.contains(new Vec3Point(15, 65, 15)), "training pit persisted");
            expect(reloaded.hypixelPit.contains(new Vec3Point(5, 72, 5)), "separate Hypixel pit persisted");
            expect(reloaded.trainingRoute.size() == 1, "route persisted");
            expect(reloaded.trainingServer.equals("127.0.0.1:25565")
                && !ServerSafety.trainingAuthorized("127.0.0.1", reloaded.trainingServer), "saved legacy host cannot arm bot");
            expect(reloaded.pitLobby.equals("duelslobby28") && reloaded.pitInstance.equals("dynamiclobby14F"),
                "pit lobby binding persisted");
            expect(reloaded.pitMatches("duelslobby28", "dynamiclobby14F"), "calibration accepts exact lobby instance");
            expect(!reloaded.pitMatches("duelslobby29", "dynamiclobby14F"), "another lobby does not match saved calibration origin metadata");
            expect(!reloaded.pitMatches("duelslobby28", "dynamiclobby15F"), "another instance does not match saved calibration origin metadata");
            expect(!reloaded.pitMatches("", ""), "unknown location never matches calibration");
            reloaded.hypixelPit.clear();
            expect(!reloaded.pitMatches("duelslobby28", "dynamiclobby14F"), "cleared boundaries cannot authorize old binding");
            expect(reloaded.combatProfile == CombatProfile.PACED && reloaded.combatSeed == -42,
                "combat profile and seed persisted");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(sandbox, "optimus.properties"))) {
                java.util.Properties invalid = new java.util.Properties();
                invalid.setProperty("combat.profile", "unknown");
                invalid.setProperty("combat.seed", "oops");
                invalid.store(out, "invalid config test");
            }
            ClientSettings fallback = new ClientSettings(sandbox);
            expect(fallback.combatProfile == CombatProfile.BASELINE && fallback.combatSeed == 1,
                "malformed profile settings safely fall back");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(sandbox, "optimus.properties"))) {
                java.util.Properties legacy = new java.util.Properties();
                legacy.setProperty("training.server", "127.0.0.1:25565");
                legacy.store(out, "legacy config without profile");
            }
            expect(new ClientSettings(sandbox).combatProfile == CombatProfile.BALANCED,
                "config without profile adopts balanced default");
            expect(new ClientSettings(sandbox).pitLobby.isEmpty() && new ClientSettings(sandbox).pitInstance.isEmpty(),
                "legacy unbound coordinates require recalibration");
            expect(!new ClientSettings(sandbox).pitMatches("duelslobby28", "dynamiclobby14F"),
                "legacy calibration cannot match verified lobby");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(sandbox, "optimus.properties"))) {
                java.util.Properties explicit = new java.util.Properties();
                explicit.setProperty("combat.profile", "baseline");
                explicit.store(out, "explicit selection");
            }
            expect(new ClientSettings(sandbox).combatProfile == CombatProfile.BASELINE,
                "an explicit existing profile selection is preserved");
            File traceFile;
            try (CombatTrace trace = new CombatTrace(reloaded.traceDirectory())) {
                traceFile = trace.file;
                trace.row(10, "combat", "127.0.0.1:25565", "paced", -42,
                    "Example_Bot", 2.0, 1, 1, 0, 0, true, true, true, true);
            }
            java.util.List<String> rows = Files.readAllLines(traceFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            expect(rows.size() == 2 && rows.get(0).split(",").length == 16, "trace has header and combat record");
            expect(rows.get(1).contains(",\"paced\",-42,\"Example_Bot\",")
                && rows.get(1).endsWith(",true,true,true,true"), "trace captures profile seed and attack decisions");
            expect(CombatTrace.csv("a,\"b\"\nc").equals("\"a,\"\"b\"\" c\""), "CSV escapes quotes and newlines");
        } catch (java.io.IOException e) {
            throw new AssertionError("test config directory setup failed", e);
        }
        System.out.println("PASS: " + checks + " safety, config, route, combat profile, turn, pacing, and trace checks");
    }

    private static void configReplacementChecks(ClientSettings settings, File directory) throws java.io.IOException {
        java.nio.file.Path config = new File(directory, "optimus.properties").toPath();
        byte[] saved = Files.readAllBytes(config);
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Windows prevents replacing a file opened without delete sharing.
            // A failed save must leave the previous calibration and identity intact.
            try (java.io.FileInputStream held = new java.io.FileInputStream(config.toFile())) {
                settings.combatSeed = 99;
                settings.save();
                expect(java.util.Arrays.equals(saved, Files.readAllBytes(config)),
                    "a failed replacement preserves the complete previous configuration");
            }
            settings.combatSeed = -42;
        }
        settings.save();
        expect(new ClientSettings(directory).combatSeed == -42,
            "settings remain writable after a failed replacement");
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(directory.toPath())) {
            expect(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                "successful and failed saves leave no temporary configuration files");
        }
    }

    private static void lobbySafetyChecks() {
        LobbySafety safety = new LobbySafety();
        Object world = new Object(), otherWorld = new Object();
        String host = "mc.hypixel.net";
        expect(!safety.verified(host, world, 1000), "disabled without location evidence");
        expect(!safety.confirm(host, world, 1000, "DUELS", "duelslobby28", "dynamiclobby14F", false),
            "unsolicited locraw cannot authorize");
        safety.request(host, world, 1000);
        expect(safety.awaitingReply(host, world, 1001) && !safety.verified(host, world, 1001), "request blocks all control");
        expect(safety.confirm(host, world, 1100, "DUELS", "duelslobby28", "dynamiclobby14F", false), "valid lobby confirmed");
        expect(safety.verified("MC.HYPIXEL.NET:25565", world, 1101), "normalized exact connection verified");
        expect(!safety.verified(host, otherWorld, 1101), "same coordinates in another world never authorize");
        expect(!safety.verified("other.hypixel.net", world, 1101), "different connection invalidates evidence");
        expect(!safety.verified(host, world, 1101+LobbySafety.MAX_AGE_MS), "stale evidence blocks control");
        expect(!safety.verified(host, world, 1099), "clock rollback blocks control");
        safety.request(host, world, 6100);
        expect(!safety.verified(host, world, 6101), "explicit verification pauses even a previously verified lobby");
        expect(safety.confirm(host, world, 6200, "DUELS", "duelslobby28", "dynamiclobby14F", false), "same instance refresh permitted");
        safety.request(host, world, 6300);
        expect(!safety.confirm(host, world, 6400, "DUELS", "duelslobby28", "dynamiclobby15F", false),
            "same lobby name with different instance disarms");
        expect(!safety.pending() && !safety.verified(host, world, 6400), "bad refresh clears all evidence");

        String[][] rejected = {
            {"DUELS", null, "mini45AJ"}, // actual duel
            {"DUELS", "duelslobby28", "mini45AJ"}, // spoofed lobbyname in match context
            {"BEDWARS", "duelslobby28", "dynamiclobby14F"},
            {"DUELS", "mainlobby1", "dynamiclobby14F"},
            {null, "duelslobby28", "dynamiclobby14F"},
            {"DUELS", "duelslobby28evil", "dynamiclobby14F"},
            {"DUELS", "duelslobby28", null},
            {"DUELS", "duelslobby", "dynamiclobby14F"}
        };
        for (String[] values : rejected) {
            safety.request(host, world, 7000);
            expect(!safety.confirm(host, world, 7100, values[0], values[1], values[2], false), "reject match/unknown location");
            expect(!safety.verified(host, world, 7101), "rejected context remains off");
        }
        safety.request(host, world, 8000);
        expect(!safety.confirm(host, world, 8100, "DUELS", "duelslobby28", "dynamiclobby14F", true), "mode/map fields reject match");
        safety.request(host, world, 9000);
        expect(!safety.confirm(host, world, 12001, "DUELS", "duelslobby28", "dynamiclobby14F", false), "late reply rejected");
        safety.request(host, world, 13000);
        safety.invalidate(); // packet-level transition, even with same world object
        expect(!safety.confirm(host, world, 13100, "DUELS", "duelslobby28", "dynamiclobby14F", false), "transition rejects queued stale reply");
        safety.request(host, world, 14000);
        expect(!safety.confirm(host, otherWorld, 14100, "DUELS", "duelslobby28", "dynamiclobby14F", false), "world-bound request rejects delayed reply");
        expect(!safety.awaitingReply(host, world, 17001), "missing response times out");
        safety.request("127.0.0.1", world, 18000);
        expect(!safety.confirm("127.0.0.1", world, 18100, "DUELS", "duelslobby28", "dynamiclobby14F", false), "private server cannot fake lobby");
        safety.request(host, null, 19000);
        expect(!safety.pending(), "no world cannot request verification");

        safety.request(host,world,20000);
        expect(safety.confirm(host,world,20100,"DUELS","duelslobby28","dynamiclobby14F",false),"routine-refresh fixture confirms");
        expect(!safety.refreshDue(host,world,25100) && !safety.refresh(host,world,25100),"five seconds no longer triggers a refresh");
        expect(!safety.refreshDue(host,world,80099),"routine refresh waits a full minute");
        expect(safety.refresh(host,world,80100),"routine refresh starts at one minute");
        expect(safety.pending() && !safety.blocking() && safety.verified(host,world,80101),"background request retains control in the verified world");
        expect(!safety.refresh(host,world,80102),"pending refresh cannot be resent or extend its timeout");
        expect(!safety.verified(host,otherWorld,80101) && !safety.verified("other.hypixel.net",world,80101),"background refresh never loosens world/connection identity");
        expect(safety.age(80200)==60100,"sending a refresh does not renew evidence before its reply");
        expect(safety.confirm(host,world,80300,"DUELS","duelslobby28","dynamiclobby14F",false),"same-instance background reply renews evidence");
        expect(!safety.pending() && safety.age(80301)==1,"successful refresh completes without a blocking phase");
        expect(safety.refresh(host,world,140300),"next background refresh waits another minute");
        expect(!safety.confirm(host,world,140400,"DUELS","duelslobby28","dynamiclobbyOTHER",false)
            && !safety.verified(host,world,140401),"changed background instance immediately invalidates control");
        expect(!safety.refresh(host,world,140402),"unverified state cannot start a nonblocking refresh");
        safety.request(host,world,150000);
        expect(safety.blocking() && !safety.verified(host,world,150001),"fresh verification still blocks control");
        safety.confirm(host,world,150100,"DUELS","duelslobby28","dynamiclobby14F",false);
        safety.refresh(host,world,210100);safety.invalidate();
        expect(!safety.verified(host,world,210101) && !safety.confirm(host,world,210102,"DUELS","duelslobby28","dynamiclobby14F",false),"transition cancels even an in-flight background reply");
        safety.request(host,world,220000);safety.confirm(host,world,220100,"DUELS","duelslobby28","dynamiclobby14F",false);
        safety.refresh(host,world,280100);
        expect(!safety.awaitingReply(host,world,283101),"background refresh retains the three-second reply deadline");
        expect(!safety.verified(host,world,220101+LobbySafety.MAX_AGE_MS),"unanswered refresh cannot keep old evidence alive indefinitely");

        PitRegion degenerate = new PitRegion();
        degenerate.setCornerA(new Vec3Point(1, 60, 1));
        degenerate.setCornerB(new Vec3Point(1, 64, 2));
        expect(!degenerate.isDefined(), "zero-width pit rejected");
        degenerate.clear();
        expect(degenerate.cornerA() == null && degenerate.cornerB() == null, "new calibration clears both old corners");
    }

    private static int acquisitionTicks(CombatProfile profile) {
        CombatTurn turn = new CombatTurn();
        float angle = 0;
        for (int tick = 1; tick <= 100; tick++) {
            angle += turn.yaw(90 - angle, profile);
            if (Math.abs(90 - angle) <= profile.yawTolerance) return tick;
        }
        throw new AssertionError("profile cannot acquire stationary target: " + profile.id());
    }
}