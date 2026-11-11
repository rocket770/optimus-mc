package dev.optimus.client;

import java.util.Locale;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.EnumChatFormatting;

/** Keep lobby menu items out of bot swings; wait briefly after a weapon/slot change. */
final class CombatEquipment {
    private int previousSlot = -1;
    private Item previousItem;
    private boolean previousSword;
    private long readyAt;

    void reset() {
        previousSlot = -1;
        previousItem = null;
        readyAt = Long.MAX_VALUE;
    }

    static boolean sword(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemSword))
            return false;
        if (!stack.hasDisplayName()) return true;
        // A lobby selector can itself use a sword icon. Its visible usage/menu
        // label must not qualify it as the weapon returned for pit combat.
        String label =
                EnumChatFormatting.getTextWithoutFormattingCodes(
                                stack.getTagCompound().getCompoundTag("display").getString("Name"))
                        .toLowerCase(Locale.ROOT);
        String compact = label.replaceAll("\\s+", "");
        return !compact.contains("rightclick")
                && !compact.contains("leftclick")
                && !label.matches(".*\\b(menu|selector)\\b.*");
    }

    boolean ready(EntityPlayer player, long tick) {
        return ready(player, tick, true);
    }

    static boolean usable(EntityPlayer player, boolean swordEnabled) {
        return swordEnabled
                ? sword(player.getHeldItem())
                : player.inventory.currentItem == 1 && player.getHeldItem() == null;
    }

    boolean ready(EntityPlayer player, long tick, boolean swordEnabled) {
        ItemStack stack = player.getHeldItem();
        if (!usable(player, swordEnabled)) {
            reset();
            return false;
        }
        Item item = stack == null ? null : stack.getItem();
        if (previousSlot != player.inventory.currentItem
                || previousItem != item
                || previousSword != swordEnabled) {
            previousSlot = player.inventory.currentItem;
            previousItem = item;
            previousSword = swordEnabled;
            readyAt = tick + 2;
        }
        return tick >= readyAt;
    }

    static boolean select(EntityPlayer player, boolean swordEnabled) {
        if (swordEnabled) return selectSword(player);
        if (player.inventory.currentItem == 1) return false;
        player.inventory.currentItem = 1;
        return true;
    }

    static boolean selectSword(EntityPlayer player) {
        if (sword(player.getHeldItem())) return false;
        for (int slot = 0; slot < 9; slot++)
            if (sword(player.inventory.getStackInSlot(slot))) {
                player.inventory.currentItem = slot;
                return true;
            }
        return false;
    }

    static boolean selectEmpty(EntityPlayer player) {
        if (player.getHeldItem() == null) return false;
        for (int slot = 0; slot < 9; slot++)
            if (player.inventory.getStackInSlot(slot) == null) {
                player.inventory.currentItem = slot;
                return true;
            }
        return false;
    }
}