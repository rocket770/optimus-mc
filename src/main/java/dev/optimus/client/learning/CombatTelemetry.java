package dev.optimus.client.learning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.optimus.core.combat.CombatAim;
import dev.optimus.core.combat.CombatClicks;
import dev.optimus.core.geometry.CollisionGeometry;
import dev.optimus.core.geometry.Vec3Point;
import dev.optimus.core.navigation.MovementState;
import dev.optimus.learning.recording.CombatRecording;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/** Converts native observations to the versioned anonymous dataset. Never controls gameplay. */
public final class CombatTelemetry {
    private final Minecraft mc;
    private final CombatRecording recorder;
    private EntityPlayer target;
    private JsonObject ownBefore, enemyBefore;
    private double[] features;
    private boolean enemyAims;
    private long lastAttack = Long.MIN_VALUE, previousAttack = Long.MIN_VALUE;

    public CombatTelemetry(Minecraft mc, CombatRecording recorder) {
        this.mc = mc;
        this.recorder = recorder;
    }

    public void reset() {
        target = null;
        features = null;
        ownBefore = enemyBefore = null;
        lastAttack = previousAttack = Long.MIN_VALUE;
    }

    public void begin(long tick, EntityPlayer opponent) {
        target = opponent;
        features = null;
        ownBefore = enemyBefore = null;
        if (!recorder.active() || opponent == null) return;
        ownBefore = snapshot(mc.thePlayer);
        enemyBefore = snapshot(opponent);
        Vec3Point eye = eye(opponent);
        Vec3Point hit =
                CombatAim.hit(
                        eye,
                        body(mc.thePlayer),
                        opponent.rotationYawHead,
                        opponent.rotationPitch,
                        CombatClicks.SWING_REACH);
        enemyAims = hit != null && clear(eye, hit);
    }

    public void features(double[] values) {
        if (recorder.active()) features = values.clone();
    }

    public void attackRequested(long tick) {
        previousAttack = lastAttack;
        lastAttack = tick;
    }

    public void end(long tick, MovementState controls, boolean authorized, String movementState) {
        if (!recorder.active()) return;
        if (!authorized || target == null || ownBefore == null || enemyBefore == null) {
            recorder.end(tick, "target_unavailable");
            return;
        }
        JsonObject frame = new JsonObject();
        frame.add("self", ownBefore);
        frame.add("opponent", enemyBefore);
        frame.addProperty("opponent_aims_at_self", enemyAims);
        if (features != null) {
            JsonArray values = new JsonArray();
            for (double value : features) values.add(new JsonPrimitive(value));
            frame.add("policy_features", values);
        }
        frame.addProperty(
                "policy_eligible",
                features != null && mc.thePlayer.onGround && !controls.jump && !controls.sneak);
        JsonObject action = new JsonObject();
        action.addProperty("forward", controls.direction.forward);
        action.addProperty("side", controls.direction.side);
        action.addProperty("jump", controls.jump);
        action.addProperty("sneak", controls.sneak);
        action.addProperty("sprint", controls.sprint);
        action.addProperty("attack_requested", lastAttack == tick);
        action.addProperty("yaw", mc.thePlayer.rotationYaw);
        action.addProperty("pitch", mc.thePlayer.rotationPitch);
        frame.add("action", action);
        frame.addProperty("movement_state", movementState);
        frame.addProperty("nearby_other_players", nearby(target));
        recorder.observe(tick, target, target.getName(), frame);
    }

    public void decision(
            long tick,
            EntityPlayer opponent,
            float desiredYaw,
            float desiredPitch,
            float yawStep,
            float pitchStep,
            boolean equipment,
            boolean ready,
            boolean bodyInReach,
            boolean visible,
            boolean aiming,
            boolean canAttack,
            CombatClicks.Action action) {
        if (!recorder.active() || opponent == null) return;
        boolean requested = lastAttack == tick && action == CombatClicks.Action.ATTACK;
        JsonObject row =
                decisionState(
                        opponent,
                        desiredYaw,
                        desiredPitch,
                        mc.thePlayer.rotationYaw - yawStep,
                        mc.thePlayer.rotationPitch - pitchStep);
        row.addProperty("combat_mode", "normal");
        row.addProperty("equipment_ready", equipment);
        row.addProperty("pacing_ready", ready);
        row.addProperty("action", action.name().toLowerCase(java.util.Locale.ROOT));
        row.addProperty("attack_requested", requested);
        row.addProperty(
                "gate",
                requested
                        ? "none"
                        : !equipment
                                ? "equipment"
                                : !ready
                                        ? "pacing"
                                        : !bodyInReach
                                                ? "range"
                                                : !visible
                                                        ? "visibility"
                                                        : !aiming
                                                                ? "aim"
                                                                : !canAttack
                                                                        ? "policy"
                                                                        : "duplicate_tick");
        attackInterval(row, tick, requested);
        recorder.combatDecision(tick, opponent, opponent.getName(), row);
    }

    public void guardDecision(
            long tick,
            EntityPlayer opponent,
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
            String gate) {
        if (!recorder.active()
                || opponent == null && (action != CombatClicks.Action.AIR_SWING || requested))
            return;
        JsonObject row;
        if (opponent == null) {
            row = new JsonObject();
            row.addProperty("combat_contract", "aim-timing-v1");
            row.add("self", snapshot(mc.thePlayer));
            row.addProperty("ping_ms", ping(mc.thePlayer));
        } else row = decisionState(opponent, desiredYaw, desiredPitch, beforeYaw, beforePitch);
        row.addProperty("combat_mode", "guard");
        row.addProperty("control_tick", controlTick);
        row.addProperty("equipment_ready", equipment);
        row.addProperty("hit_window_ready", hitWindow);
        row.addProperty("assist_pending", pending);
        row.addProperty("cooldown_ticks", cooldown);
        row.addProperty("action", action.name().toLowerCase(java.util.Locale.ROOT));
        row.addProperty("attack_requested", requested);
        row.addProperty("gate", opponent == null ? "target_unresolved" : gate);
        attackInterval(row, tick, requested);
        recorder.combatDecision(tick, opponent, opponent == null ? "" : opponent.getName(), row);
    }

    private void attackInterval(JsonObject row, long tick, boolean requested) {
        long previous = requested ? previousAttack : lastAttack;
        if (previous != Long.MIN_VALUE && tick >= previous)
            row.addProperty("ticks_since_previous_attack", tick - previous);
    }

    private JsonObject decisionState(
            EntityPlayer opponent,
            float desiredYaw,
            float desiredPitch,
            float beforeYaw,
            float beforePitch) {
        Vec3Point eye = eye(mc.thePlayer);
        CollisionGeometry.Box body = body(opponent);
        Vec3Point hit =
                CombatAim.hit(
                        eye,
                        body,
                        mc.thePlayer.rotationYaw,
                        mc.thePlayer.rotationPitch,
                        CombatClicks.ATTACK_REACH);
        JsonObject row = new JsonObject();
        row.addProperty("combat_contract", "aim-timing-v1");
        row.add("self", snapshot(mc.thePlayer));
        row.add("opponent", snapshot(opponent));
        row.addProperty("desired_yaw", desiredYaw);
        row.addProperty("desired_pitch", desiredPitch);
        row.addProperty("view_yaw_before", beforeYaw);
        row.addProperty("view_pitch_before", beforePitch);
        row.addProperty(
                "yaw_error_before", MathHelper.wrapAngleTo180_float(desiredYaw - beforeYaw));
        row.addProperty("pitch_error_before", desiredPitch - beforePitch);
        row.addProperty(
                "yaw_error_after",
                MathHelper.wrapAngleTo180_float(desiredYaw - mc.thePlayer.rotationYaw));
        row.addProperty("pitch_error_after", desiredPitch - mc.thePlayer.rotationPitch);
        row.addProperty("reach", CombatClicks.ATTACK_REACH);
        row.addProperty("body_distance", Math.sqrt(CombatAim.distanceSquared(eye, body)));
        row.addProperty("ray_intersects_body", hit != null);
        row.addProperty("clear_body_ray", hit != null && clear(eye, hit));
        row.addProperty("ping_ms", ping(mc.thePlayer));
        int otherPing = ping(opponent);
        if (otherPing > 0) row.addProperty("opponent_ping_ms_observed", otherPing);
        row.addProperty("nearby_other_players", nearby(opponent));
        return row;
    }

    public void death(long tick, String reason) {
        recorder.physicalDeath(
                tick,
                reason,
                recorder.active() && mc.thePlayer != null ? snapshot(mc.thePlayer) : null);
        target = null;
    }

    private int ping(EntityPlayer player) {
        if (mc.getNetHandler() == null) return 0;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
        return info == null ? 0 : Math.max(0, info.getResponseTime());
    }

    private int nearby(EntityPlayer opponent) {
        int count = 0;
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other != mc.thePlayer
                    && other != opponent
                    && other.isEntityAlive()
                    && other.worldObj == mc.theWorld
                    && mc.thePlayer.getDistanceSqToEntity(other) <= 16) count++;
        }
        return count;
    }

    private boolean clear(Vec3Point from, Vec3Point to) {
        return mc.theWorld.rayTraceBlocks(
                        new Vec3(from.x, from.y, from.z), new Vec3(to.x, to.y, to.z))
                == null;
    }

    private static Vec3Point eye(EntityPlayer player) {
        return new Vec3Point(player.posX, player.posY + player.getEyeHeight(), player.posZ);
    }

    private static CollisionGeometry.Box body(EntityPlayer player) {
        AxisAlignedBB box = player.getEntityBoundingBox();
        return new CollisionGeometry.Box(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static String weapon(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return "fist";
        if (stack.getItem() instanceof ItemSword) return "sword";
        if (stack.getItem() instanceof ItemAxe) return "axe";
        if (stack.getItem() instanceof ItemBow) return "bow";
        return "other";
    }

    private static JsonObject snapshot(EntityPlayer player) {
        JsonObject row = new JsonObject();
        row.addProperty("x", player.posX);
        row.addProperty("y", player.posY);
        row.addProperty("z", player.posZ);
        row.addProperty("vx", player.motionX);
        row.addProperty("vy", player.motionY);
        row.addProperty("vz", player.motionZ);
        row.addProperty("server_x_observed", player.serverPosX / 32.0);
        row.addProperty("server_y_observed", player.serverPosY / 32.0);
        row.addProperty("server_z_observed", player.serverPosZ / 32.0);
        row.addProperty("observed_dx", player.posX - player.prevPosX);
        row.addProperty("observed_dy", player.posY - player.prevPosY);
        row.addProperty("observed_dz", player.posZ - player.prevPosZ);
        JsonArray bounds = new JsonArray();
        CollisionGeometry.Box box = body(player);
        for (double value :
                new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ})
            bounds.add(new JsonPrimitive(value));
        row.add("body", bounds);
        row.addProperty("yaw", player.rotationYaw);
        row.addProperty("pitch", player.rotationPitch);
        row.addProperty("head_yaw", player.rotationYawHead);
        row.addProperty("swinging", player.isSwingInProgress);
        row.addProperty("swing_progress", player.swingProgressInt);
        row.addProperty("health_observed", player.getHealth());
        row.addProperty("absorption_observed", player.getAbsorptionAmount());
        row.addProperty("armor", player.getTotalArmorValue());
        row.addProperty("hurt", player.hurtTime);
        row.addProperty("ground", player.onGround);
        row.addProperty("weapon", weapon(player.getHeldItem()));
        row.addProperty(
                "enchanted_weapon",
                player.getHeldItem() != null && player.getHeldItem().isItemEnchanted());
        return row;
    }
}