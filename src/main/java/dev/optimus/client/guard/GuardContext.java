package dev.optimus.client.guard;

import dev.optimus.client.ClientSettings;
import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.control.RemoteCommand;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

/** Native scene queries and immutable dependencies shared by guard policies. */
final class GuardContext {
    final Minecraft mc;
    final GuardHost host;
    final GuardNavigation navigator;
    private final Supplier<ClientSettings> configuration;

    GuardContext(
            Minecraft mc,
            GuardHost host,
            GuardNavigation navigator,
            Supplier<ClientSettings> configuration) {
        this.mc = mc;
        this.host = host;
        this.navigator = navigator;
        this.configuration = configuration;
    }

    ClientSettings settings() {
        return configuration.get();
    }

    boolean protects(EntityPlayer player) {
        ClientSettings settings = settings();
        return enabled()
                && (settings.guardUuid.equals(player.getUniqueID().toString())
                        || settings.guardName.equalsIgnoreCase(player.getName()));
    }

    boolean present(EntityPlayer player, PitRegion pit) {
        return player != null
                && player != mc.thePlayer
                && player.worldObj == mc.theWorld
                && player.isEntityAlive()
                && player.getHealth() > 0
                && pit.containsBody(position(player), .3, 1.8);
    }

    static Vec3Point position(EntityPlayer player) {
        return new Vec3Point(player.posX, player.posY, player.posZ);
    }

    static Vec3Point measuredPosition(EntityPlayer player) {
        Vec3Point rendered = position(player),
                server =
                        new Vec3Point(
                                player.serverPosX / 32.0,
                                player.serverPosY / 32.0,
                                player.serverPosZ / 32.0);
        return server.horizontalDistanceSq(rendered) <= 9 && Math.abs(server.y - rendered.y) <= 3
                ? server
                : rendered;
    }

    static CollisionGeometry.Box box(EntityPlayer player) {
        AxisAlignedBB b = player.getEntityBoundingBox();
        return new CollisionGeometry.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    static double distance(Vec3Point a, Vec3Point b) {
        double y = a.y - b.y;
        return a.horizontalDistanceSq(b) + y * y;
    }

    boolean reachable(Vec3Point self, EntityPlayer target) {
        if (target == null) return false;
        return reachable(self, box(target), CombatClicks.ATTACK_REACH);
    }

    boolean reachable(Vec3Point self, CollisionGeometry.Box target, double reach) {
        Vec3Point eye = new Vec3Point(self.x, self.y + mc.thePlayer.getEyeHeight(), self.z);
        return CombatAim.clearPoint(eye, target, reach, hit -> host.guardRay(eye, hit)) != null;
    }

    boolean enabled() {
        return !settings().guardUuid.isEmpty();
    }

    boolean guardingController() {
        ClientSettings settings = settings();
        return RemoteCommand.ownerMatches(
                settings.controllerName,
                settings.controllerUuid,
                settings.guardName,
                settings.guardUuid);
    }
}