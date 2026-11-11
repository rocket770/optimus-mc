package dev.optimus.client;

import dev.optimus.core.combat.CombatProfile;
import dev.optimus.core.config.PropertiesStore;
import dev.optimus.core.control.RemoteCommand;
import dev.optimus.core.control.TargetWhitelist;
import dev.optimus.core.geometry.ArenaPreset;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.RouteFollower;
import java.io.File;
import java.io.IOException;
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
    public static final String DEFAULT_CONTROLLER_NAME = "rocket770";
    public static final String DEFAULT_CONTROLLER_UUID = "fd299507-9e07-433f-a908-52dea48a821e";
    public String controllerName = DEFAULT_CONTROLLER_NAME,
            controllerUuid = DEFAULT_CONTROLLER_UUID;
    public String guardName = "", guardUuid = "", guardKeys = "auto";
    public boolean routeHintsEnabled = true;
    public final Map<String, String> operators = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    public boolean swordEnabled = true, targetAll = false;
    public boolean debugVisuals = false;
    // Stored training data does not authorize bot input.
    public final PitRegion trainingPit = new PitRegion();
    public final RouteFollower trainingRoute = new RouteFollower();
    public String trainingServer = ""; // intentionally no default authorization
    public CombatProfile combatProfile = CombatProfile.BALANCED;
    public long combatSeed = 1;

    private final File file;
    private final Properties retained = new Properties();
    private boolean loadFailed;

    public ClientSettings(File directory) {
        this.file = new File(directory, "optimus.properties");
        load(file);
    }

    private void load(File source) {
        if (!source.exists()) return;
        try {
            Properties p = PropertiesStore.read(source);
            retained.putAll(p);
            trainingServer = p.getProperty("training.server", "").trim();
            pitLobby = p.getProperty("hypixel.lobby", "");
            pitInstance = p.getProperty("hypixel.instance", "");
            loadControl(p);
            loadGuard(p);
            routeHintsEnabled =
                    Boolean.parseBoolean(p.getProperty("navigation.routes.enabled", "true"));
            loadCombat(p);
            loadNavigation(p);
            loadTrainingRoute(p);
        } catch (IOException | IllegalArgumentException error) {
            loadFailed = true;
            controllerName = controllerUuid = "";
            System.err.println("[Optimus] Unable to load configuration: " + error);
        }
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
        if (loadFailed) {
            System.err.println(
                    "[Optimus] Config was unreadable; leaving the original file untouched.");
            return;
        }
        Properties p = new Properties();
        p.putAll(retained);
        for (String key : new java.util.HashSet<>(p.stringPropertyNames()))
            if (key.startsWith("control.operator.")) p.remove(key);
        for (String key :
                new String[] {
                    "hypixel.a",
                    "hypixel.b",
                    "navigation.a",
                    "navigation.b",
                    "navigation.entrance",
                    "navigation.spawn",
                    "navigation.drop.rim",
                    "training.a",
                    "training.b"
                }) p.remove(key);
        p.setProperty("training.server", trainingServer);
        p.setProperty("hypixel.lobby", pitLobby);
        p.setProperty("hypixel.instance", pitInstance);
        p.setProperty("control.owner.name", controllerName);
        p.setProperty("control.owner.uuid", controllerUuid);
        p.setProperty("control.owner.defaults", "rocket770-v1");
        p.setProperty("guard.name", guardName);
        p.setProperty("guard.uuid", guardUuid);
        p.setProperty("guard.keys", guardKeys);
        p.setProperty("navigation.routes.enabled", Boolean.toString(routeHintsEnabled));
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
            PropertiesStore.write(file, p);
            retained.clear();
            retained.putAll(p);
        } catch (IOException error) {
            System.err.println("[Optimus] Unable to save configuration: " + error);
        }
    }

    public String configStatus() {
        return "Config: optimus.properties" + (loadFailed ? " (unreadable; unchanged)" : "");
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
        boolean defaultOwner =
                !p.containsKey("control.owner.name") && !p.containsKey("control.owner.uuid");
        controllerName =
                p.getProperty("control.owner.name", defaultOwner ? DEFAULT_CONTROLLER_NAME : "");
        controllerUuid =
                p.getProperty("control.owner.uuid", defaultOwner ? DEFAULT_CONTROLLER_UUID : "");
        try {
            if (!controllerName.matches("[A-Za-z0-9_]{3,16}"))
                throw new IllegalArgumentException("Invalid controller");
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

    private void loadGuard(Properties p) {
        String name = p.getProperty("guard.name", "");
        String keys = p.getProperty("guard.keys", "auto").toLowerCase(java.util.Locale.ROOT);
        if (keys.equals("auto") || keys.equals("on") || keys.equals("off")) guardKeys = keys;
        if (!name.matches("[A-Za-z0-9_]{3,16}")) return;
        try {
            guardUuid = java.util.UUID.fromString(p.getProperty("guard.uuid", "")).toString();
            guardName = name;
        } catch (IllegalArgumentException ignored) {
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

    private void loadNavigation(Properties p) {
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
        parkourEnabled = Boolean.parseBoolean(p.getProperty("navigation.parkour", "true"));
    }

    private void loadTrainingRoute(Properties p) {
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