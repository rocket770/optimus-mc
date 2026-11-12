package dev.optimus.client.guard;

import dev.optimus.client.ClientSettings;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;
import java.lang.reflect.Proxy;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/** Checks the close-follow handoff when navigation rejects every moving key candidate. */
public final class GuardSteeringChecks {
    private GuardSteeringChecks() {}

    public static int verify(
            Minecraft mc,
            GuardHost host,
            ClientSettings settings,
            EntityPlayer leader,
            PitRegion pit) {
        GuardNavigation blocked =
                (GuardNavigation)
                        Proxy.newProxyInstance(
                                GuardNavigation.class.getClassLoader(),
                                new Class<?>[] {GuardNavigation.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("safeGuardInput"))
                                        return arguments[0] == NearbySteering.Input.NONE;
                                    throw new AssertionError(
                                            "Unexpected query: " + method.getName());
                                });
        GuardContext ctx = new GuardContext(mc, host, blocked, () -> settings);
        GuardSteering steering = new GuardSteering(ctx, new GuardCombat(ctx, new CombatClicks()));
        Vec3Point self = GuardContext.position(mc.thePlayer);
        GuardObservation observation =
                new GuardObservation(
                        leader,
                        new Vec3Point(self.x, self.y, self.z + .74),
                        new Vec3Point(0, 0, 0),
                        0,
                        0,
                        false,
                        false,
                        true,
                        false,
                        false,
                        null,
                        null);
        boolean recovered = false;
        int checks = 0;
        GuardFollowPlan blockedPlan =
                new GuardFollowPlan(
                        new Vec3Point(self.x, self.y, self.z + .3),
                        0,
                        -1,
                        false,
                        false,
                        false,
                        false);
        for (long tick = 0; tick <= 15; tick++) {
            GuardSteering.Result result =
                    steering.choose(blockedPlan, observation, pit, tick, false);
            if (result.input != NearbySteering.Input.NONE || result.jump)
                throw new AssertionError("Blocked follow cannot force unchecked movement");
            checks++;
            recovered |= result.recover;
        }
        if (!recovered)
            throw new AssertionError("A blocked close-follow corner must hand off to navigation");
        checks++;
        steering.reset();
        GuardFollowPlan settledPlan =
                new GuardFollowPlan(
                        new Vec3Point(self.x, self.y, self.z + .1),
                        0,
                        -1,
                        false,
                        false,
                        false,
                        false);
        for (long tick = 20; tick <= 40; tick++) {
            if (steering.choose(settledPlan, observation, pit, tick, false).recover)
                throw new AssertionError("Settled following must not repeatedly start recovery");
            checks++;
        }
        return checks;
    }
}