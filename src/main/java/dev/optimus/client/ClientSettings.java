package dev.optimus.client;

import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.control.RemoteCommand;
import dev.optimus.core.control.TargetWhitelist;
import dev.optimus.core.geometry.ArenaPreset;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.RouteFollower;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Persistent config stored under .minecraft/config/optimus.properties. */
public final class ClientSettings {
    public final TargetWhitelist whitelist = new TargetWhitelist();
    public final PitRegion hypixelPit = new PitRegion();
    public String pitLobby = "", pitInstance = "";
    public final PitRegion navigationArea = new PitRegion();
    public Vec3Point pitEntrance;
    public Vec3Point spawnPoint, dropRim;
    public double dropFloor = 63;
    public boolean parkourEnabled = true;
    public String controllerName = "", controllerUuid = "";
    public final Map<String, String> operators = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    public boolean swordEnabled = true, targetAll = false;
    public boolean debugVisuals = false;
    // Preserve legacy data for migration; none of these fields authorize bot input.
    public final PitRegion trainingPit = new PitRegion();
    public final RouteFollower trainingRoute = new RouteFollower();
    public String trainingServer = ""; // intentionally no default authorization
    public CombatProfile combatProfile = CombatProfile.BALANCED;
    public long combatSeed = 1;

    private final File file;

    public ClientSettings(File directory) {
        this.file = new File(directory, "optimus.properties");
        load(file);
    }

    private void load(File source) {
        Properties p = new Properties();
        boolean upgraded = false;
        if (!source.isFile()) return;
        try (FileInputStream in = new FileInputStream(source)) {
            p.load(in);
            trainingServer = p.getProperty("training.server", "").trim();
            pitLobby = p.getProperty("hypixel.lobby", "");
            pitInstance = p.getProperty("hypixel.instance", "");
            loadControl(p);
            loadCombat(p);
            upgraded = loadNavigation(p);
            loadLegacyRoute(p);
            if ("duelslobby1".equals(pitLobby)
                    && ArenaPreset.same(spawnPoint, ArenaPreset.SPAWN)
                    && ArenaPreset.same(dropRim, ArenaPreset.DROP_RIM)
                    && ArenaPreset.same(navigationArea.cornerA(), ArenaPreset.NAV_A)
                    && ArenaPreset.same(navigationArea.cornerB(), ArenaPreset.NAV_B)) {
                upgraded = ArenaPreset.upgradeLegacyPit(hypixelPit) || upgraded;
            }
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("[Optimus] Unable to load configuration: " + error);
        }
        if (upgraded) save();
    }

    private static void loadPoint(Properties p, String key, PitRegion region, boolean first) {
        String raw = p.getProperty(key, "");
        if (raw.isEmpty()) return;
        try {
            if (first) region.setCornerA(Vec3Point.parse(raw));
            else region.setCornerB(Vec3Point.parse(raw));
        } catch (RuntimeException ignored) {
        }
    }

    private static Vec3Point loadOptionalPoint(Properties p, String key) {
        try {
            return Vec3Point.parse(p.getProperty(key, ""));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("training.server", trainingServer);
        p.setProperty("hypixel.lobby", pitLobby);
        p.setProperty("hypixel.instance", pitInstance);
        p.setProperty("control.owner.name", controllerName);
        p.setProperty("control.owner.uuid", controllerUuid);
        for (Map.Entry<String, String> operator : operators.entrySet())
            p.setProperty("control.operator." + operator.getKey(), operator.getValue());
        p.setProperty("combat.weapon", swordEnabled ? "sword" : "fist");
        p.setProperty("target.all", Boolean.toString(targetAll));
        p.setProperty("debug.visuals", Boolean.toString(debugVisuals));
        p.setProperty("combat.profile", combatProfile.id());
        p.setProperty("combat.seed", Long.toString(combatSeed));
        p.setProperty("target.names", String.join(",", whitelist.all()));
        saveRegion(p, "hypixel", hypixelPit);
        saveRegion(p, "navigation", navigationArea);
        if (pitEntrance != null) p.setProperty("navigation.entrance", pitEntrance.serialize());
        if (spawnPoint != null) p.setProperty("navigation.spawn", spawnPoint.serialize());
        if (dropRim != null) p.setProperty("navigation.drop.rim", dropRim.serialize());
        p.setProperty("navigation.drop.floor", Double.toString(dropFloor));
        p.setProperty("navigation.parkour", Boolean.toString(parkourEnabled));
        p.setProperty("navigation.parkour.defaults", "enabled-v1");
        saveRegion(p, "training", trainingPit);
        StringBuilder route = new StringBuilder();
        for (Vec3Point waypoint : trainingRoute.all()) {
            if (route.length() != 0) route.append(';');
            route.append(waypoint.serialize());
        }
        p.setProperty("training.route", route.toString());
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            System.err.println("[Optimus] Cannot create config directory: " + parent);
            return;
        }
        try {
            store(p);
        } catch (IOException error) {
            System.err.println("[Optimus] Unable to save configuration: " + error);
        }
    }

    private void store(Properties properties) throws IOException {
        Path temporary = Files.createTempFile(file.getParentFile().toPath(), "optimus-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary.toFile())) {
                properties.store(
                        out,
                        "Optimus Forge 1.8.9 - all verified Duels lobbies, full-lobby travel and pit-only combat");
            }
            try {
                Files.move(
                        temporary,
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public boolean targets(String name) {
        return targetAll || whitelist.contains(name);
    }

    public String targetSummary() {
        return targetAll ? "all pit players" : whitelist.all().toString();
    }

    public boolean operatorMatches(String name, String uuid) {
        return name != null && RemoteCommand.ownerMatches(name, operators.get(name), name, uuid);
    }

    private static void saveRegion(Properties p, String prefix, PitRegion region) {
        if (region.cornerA() != null) p.setProperty(prefix + ".a", region.cornerA().serialize());
        if (region.cornerB() != null) p.setProperty(prefix + ".b", region.cornerB().serialize());
    }

    public File traceDirectory() {
        return new File(file.getParentFile(), "optimus-traces");
    }

    public boolean pitMatches(String lobby, String instance) {
        return hypixelPit.isDefined()
                && !pitLobby.isEmpty()
                && !pitInstance.isEmpty()
                && pitLobby.equals(lobby)
                && pitInstance.equals(instance);
    }

    /**
     * Legacy rectangle validity retained for config compatibility; runtime uses arenaConfigured().
     */
    public boolean navigationConfigured() {
        return "duelslobby1".equals(pitLobby)
                && hypixelPit.isDefined()
                && navigationArea.isDefined()
                && navigationArea.contains(hypixelPit.cornerA())
                && navigationArea.contains(hypixelPit.cornerB())
                && pitEntrance != null
                && hypixelPit.containsBody(pitEntrance, 0.3, 1.8)
                && navigationArea.containsBody(pitEntrance, 0.3, 1.8)
                && (spawnPoint == null || navigationArea.containsBody(spawnPoint, 0.3, 1.8))
                && (dropRim == null
                        || (navigationArea.containsBody(dropRim, 0.3, 1.8)
                                && Double.isFinite(dropFloor)
                                && dropFloor
                                        >= Math.min(hypixelPit.cornerA().y, hypixelPit.cornerB().y)
                                && dropFloor < dropRim.y));
    }

    /**
     * Full-lobby travel needs calibrated pit/entry data, not a manually bounded approach rectangle.
     */
    public boolean pitCalibrated() {
        return pitLobby.matches("duelslobby[0-9]+")
                && pitInstance.matches("dynamiclobby[A-Za-z0-9]+")
                && hypixelPit.isDefined();
    }

    public boolean arenaConfigured() {
        return pitCalibrated()
                && pitEntrance != null
                && hypixelPit.containsBody(pitEntrance, 0.3, 1.8)
                && (dropRim == null
                        || (Double.isFinite(dropFloor)
                                && dropFloor
                                        >= Math.min(hypixelPit.cornerA().y, hypixelPit.cornerB().y)
                                && dropFloor < dropRim.y));
    }

    public void applySuppliedArena(String verifiedLobby, String verifiedInstance) {
        if (verifiedLobby == null
                || !verifiedLobby.matches("duelslobby[0-9]+")
                || verifiedInstance == null
                || !verifiedInstance.matches("dynamiclobby[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("A verified Duels lobby instance is required");
        }
        hypixelPit.setCornerA(ArenaPreset.PIT_A);
        hypixelPit.setCornerB(ArenaPreset.PIT_B);
        navigationArea.setCornerA(ArenaPreset.NAV_A);
        navigationArea.setCornerB(ArenaPreset.NAV_B);
        pitLobby = verifiedLobby;
        pitInstance = verifiedInstance;
        spawnPoint = ArenaPreset.SPAWN;
        dropRim = ArenaPreset.DROP_RIM;
        dropFloor = ArenaPreset.FLOOR;
        pitEntrance = ArenaPreset.LANDING_HINT;
        parkourEnabled = true;
        combatProfile = CombatProfile.BALANCED;
    }

    private void loadControl(Properties p) {
        controllerName = p.getProperty("control.owner.name", "");
        controllerUuid = p.getProperty("control.owner.uuid", "");
        try {
            java.util.UUID.fromString(controllerUuid);
        } catch (IllegalArgumentException error) {
            controllerName = controllerUuid = "";
        }
        for (String key : p.stringPropertyNames())
            if (key.startsWith("control.operator.")) {
                String name = key.substring("control.operator.".length()),
                        uuid = p.getProperty(key);
                if (name.matches("[A-Za-z0-9_]{3,16}")) {
                    try {
                        operators.put(name, java.util.UUID.fromString(uuid).toString());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
    }

    private void loadCombat(Properties p) {
        swordEnabled = !"fist".equalsIgnoreCase(p.getProperty("combat.weapon", "sword"));
        targetAll = Boolean.parseBoolean(p.getProperty("target.all", "false"));
        debugVisuals = Boolean.parseBoolean(p.getProperty("debug.visuals", "false"));
        try {
            combatProfile = CombatProfile.parse(p.getProperty("combat.profile", "balanced"));
        } catch (IllegalArgumentException ignored) {
            combatProfile = CombatProfile.BASELINE;
        }
        try {
            combatSeed = Long.parseLong(p.getProperty("combat.seed", "1"));
        } catch (NumberFormatException ignored) {
            combatSeed = 1;
        }
        for (String username : p.getProperty("target.names", "").split(",")) {
            if (!username.trim().isEmpty()) whitelist.add(username.trim());
        }
    }

    private boolean loadNavigation(Properties p) {
        loadPoint(p, "hypixel.a", hypixelPit, true);
        loadPoint(p, "hypixel.b", hypixelPit, false);
        loadPoint(p, "navigation.a", navigationArea, true);
        loadPoint(p, "navigation.b", navigationArea, false);
        pitEntrance = loadOptionalPoint(p, "navigation.entrance");
        spawnPoint = loadOptionalPoint(p, "navigation.spawn");
        dropRim = loadOptionalPoint(p, "navigation.drop.rim");
        try {
            dropFloor = Double.parseDouble(p.getProperty("navigation.drop.floor", "63"));
        } catch (NumberFormatException ignored) {
            dropRim = null;
        }
        if (!Double.isFinite(dropFloor)) dropRim = null;
        // Older releases wrote false as the default, indistinguishable from
        // an override. Adopt the new default once; later explicit off persists.
        boolean parkourDefaultsCurrent =
                "enabled-v1".equals(p.getProperty("navigation.parkour.defaults", ""));
        parkourEnabled =
                !parkourDefaultsCurrent
                        || Boolean.parseBoolean(p.getProperty("navigation.parkour", "true"));
        return !parkourDefaultsCurrent;
    }

    private void loadLegacyRoute(Properties p) {
        loadPoint(p, "training.a", trainingPit, true);
        loadPoint(p, "training.b", trainingPit, false);
        for (String point : p.getProperty("training.route", "").split(";")) {
            if (!point.trim().isEmpty()) {
                try {
                    trainingRoute.add(Vec3Point.parse(point));
                } catch (RuntimeException ignored) {
                    /* Invalid route point remains disabled */
                }
            }
        }
    }
}