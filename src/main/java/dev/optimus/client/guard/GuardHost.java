package dev.optimus.client.guard;

import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.PitRegion;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.NearbySteering;
import net.minecraft.entity.player.EntityPlayer;

/** Session authorization and native actions exposed to the guard controller. */
public interface GuardHost {
    boolean guardAllowed();

    boolean guardArmed();

    boolean isVerifiedLobby();

    String connectedServer();

    String guardLobby();

    String guardInstance();

    int guardPingMillis();

    void controlOptionsChanged();

    void startGuardIfNeeded();

    void notice(String text);

    void guardActivity(String text);

    void guardHold();

    void guardRoam(PitRegion pit);

    void guardCancelRoam();

    void guardNavigate(Vec3Point goal, PitRegion pit, boolean precise, boolean travelJump);

    boolean guardTravelJump(Vec3Point goal, PitRegion pit);

    NearbySteering.Input guardMovement();

    void guardReorient(NearbySteering.Input input, float previousYaw, PitRegion pit);

    boolean guardRay(Vec3Point eye, Vec3Point hit);

    boolean guardSword(EntityPlayer player);

    boolean guardSelectWeapon(boolean sword);

    boolean guardEquipment(boolean sword, long tick);

    int guardAttackDelay();

    void guardMove(NearbySteering.Input input, boolean running, boolean jump, boolean sneak);

    boolean guardStrike(EntityPlayer target, boolean sword);

    void recordCombatDecision(
            EntityPlayer target,
            String mode,
            long controlTick,
            float beforeYaw,
            float beforePitch,
            float desiredYaw,
            float desiredPitch,
            boolean equipment,
            boolean hitWindow,
            boolean pending,
            long cooldown,
            CombatClicks.Action action,
            boolean requested,
            String gate);
}