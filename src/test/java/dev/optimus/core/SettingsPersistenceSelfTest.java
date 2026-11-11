package dev.optimus.core;

import dev.optimus.client.ClientSettings;
import dev.optimus.core.config.PropertiesStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

/** Saved preferences, unknown-key retention and non-destructive failures. */
public final class SettingsPersistenceSelfTest {
    private static int checks;

    private static void expect(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("optimus-config-migration-");
        Path primary = root.resolve("optimus.properties");
        String operator = UUID.randomUUID().toString(), guard = UUID.randomUUID().toString();
        String data =
                "control.owner.name=rocket770\ncontrol.owner.uuid="
                        + ClientSettings.DEFAULT_CONTROLLER_UUID
                        + "\ncontrol.operator.Helper_One="
                        + operator
                        + "\ncombat.weapon=fist\ntarget.all=true\ntarget.names=Target_One,Target_Two"
                        + "\ncombat.profile=paced\ncombat.seed=770\nnavigation.parkour=false\nnavigation.routes.enabled=false"
                        + "\nguard.name=Guarded_Player\nguard.uuid="
                        + guard
                        + "\nguard.keys=off\nfuture.setting=keep-me"
                        + "\nhypixel.lobby=duelslobby9\nhypixel.instance=dynamiclobbyTEST\nhypixel.a=-10:0:-10\nhypixel.b=10:10:10"
                        + "\nnavigation.entrance=0:1:0\ntraining.route=0:1:0;1:1:1\ntraining.server=legacy.example";
        Files.write(primary, data.getBytes(StandardCharsets.UTF_8));
        byte[] original = Files.readAllBytes(primary);
        ClientSettings settings = new ClientSettings(root.toFile());
        expect(Files.isRegularFile(primary), "the Optimus config remains available");
        expect(
                Arrays.equals(Files.readAllBytes(primary), original),
                "loading never rewrites the saved settings");
        expect(
                settings.controllerName.equals("rocket770")
                        && settings.controllerUuid.equals(ClientSettings.DEFAULT_CONTROLLER_UUID),
                "controller name and UUID survive the save");
        expect(
                settings.operatorMatches("helper_one", operator),
                "operator permissions retain exact UUID binding");
        expect(
                !settings.swordEnabled
                        && settings.targetAll
                        && settings.whitelist.contains("Target_One"),
                "weapons and target preferences survive loading");
        expect(
                settings.combatSeed == 770 && settings.combatProfile.id().equals("paced"),
                "profile and seed survive loading");
        expect(
                !settings.parkourEnabled && !settings.routeHintsEnabled,
                "explicit disabled navigation preferences are not replaced by defaults");
        expect(
                settings.guardName.equals("Guarded_Player")
                        && settings.guardUuid.equals(guard)
                        && settings.guardKeys.equals("off"),
                "guard identity and input preference survive loading");
        expect(
                settings.arenaConfigured() && settings.trainingRoute.all().size() == 2,
                "geometry and legacy route data remain readable");
        settings.save();
        expect(
                PropertiesStore.read(primary.toFile())
                        .getProperty("future.setting")
                        .equals("keep-me"),
                "ordinary saves retain unknown keys");
        settings.operators.clear();
        settings.whitelist.clear();
        settings.targetAll = false;
        settings.guardName = settings.guardUuid = "";
        settings.hypixelPit.clear();
        settings.save();
        ClientSettings cleared = new ClientSettings(root.toFile());
        expect(
                cleared.operators.isEmpty()
                        && cleared.whitelist.all().isEmpty()
                        && !cleared.targetAll,
                "cleared operators and targets do not reappear from retained properties");
        expect(
                !cleared.hypixelPit.isDefined() && cleared.guardUuid.isEmpty(),
                "cleared bounds and guard selection stay cleared");
        expect(
                PropertiesStore.read(primary.toFile())
                        .getProperty("future.setting")
                        .equals("keep-me"),
                "clearing known settings does not delete unrelated keys");
        Properties override = new Properties();
        override.setProperty("combat.weapon", "sword");
        override.setProperty("control.owner.name", "");
        override.setProperty("control.owner.uuid", "");
        PropertiesStore.write(primary.toFile(), override);
        ClientSettings preferred = new ClientSettings(root.toFile());
        expect(
                preferred.swordEnabled && preferred.operators.isEmpty(),
                "an existing Optimus file takes precedence over previously saved values");
        expect(
                preferred.controllerName.isEmpty() && preferred.controllerUuid.isEmpty(),
                "explicit controller off is not re-enabled by the restored default");
        Path fresh = Files.createTempDirectory("optimus-config-default-");
        expect(
                new ClientSettings(fresh.toFile()).controllerName.equals("rocket770"),
                "fresh settings restore the requested default controller");
        Files.write(
                fresh.resolve("optimus.properties"),
                "control.owner.name=OnlyName".getBytes(StandardCharsets.UTF_8));
        expect(
                new ClientSettings(fresh.toFile()).controllerUuid.isEmpty(),
                "a partial owner pair never inherits the default UUID");
        Path malformed = Files.createTempDirectory("optimus-config-malformed-");
        Path broken = malformed.resolve("optimus.properties");
        byte[] invalid = "custom=\\uNOTH".getBytes(StandardCharsets.UTF_8);
        Files.write(broken, invalid);
        ClientSettings failed = new ClientSettings(malformed.toFile());
        failed.save();
        expect(
                Arrays.equals(invalid, Files.readAllBytes(broken)),
                "an unreadable config is not replaced by a partially loaded save");
        expect(
                failed.controllerUuid.isEmpty(),
                "malformed settings do not authorize the default controller");
        Path bom = Files.createTempFile("optimus-config-bom-", ".properties");
        Files.write(bom, "\ufeffmode=live\nlabel=caf\u00e9".getBytes(StandardCharsets.UTF_8));
        expect(
                PropertiesStore.read(bom.toFile()).getProperty("mode").equals("live"),
                "UTF-8 BOM is handled without losing the first key");
        expect(
                PropertiesStore.read(bom.toFile()).getProperty("label").equals("caf\u00e9"),
                "UTF-8 values retain their characters");
        System.out.println("PASS: " + checks + " settings persistence and failure checks");
    }
}