package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.CommonHooks;

/** Preflight the native output + remainder path, before vanilla moves or consumes anything. */
public final class CraftingSafety {
    private CraftingSafety() {}
    public static boolean applies(ServerPlayer player, AbstractContainerMenu menu) {
        return player.containerMenu == menu && CraftingService.supported(menu) && AccessGate.resolve(player).active();
    }
    public static boolean canTake(ServerPlayer player, ClickType click, int button) {
        var menu = player.containerMenu; var output = menu.getSlot(0).getItem();
        if (output.isEmpty()) return true;
        if (output.getCount() > output.getMaxStackSize()) return false;
        var inventory = CraftingReservations.inventoryAfterCraft(player); int limit = player.getInventory().getMaxStackSize();
        if (inventory == null) return false;
        if (click == ClickType.QUICK_MOVE && !MaterialTransaction.insert(inventory, output, limit)) return false;
        if (click == ClickType.SWAP && button >= 0 && button < 9) {
            if (!inventory.get(button).isEmpty()) return true; // Native result slot cannot accept the swap input.
            if (output.getCount() > limit) return false;
            inventory.set(button, output.copy());
        }
        var grid = CraftingService.grid(menu); var position = grid.asPositionedCraftInput(); var input = position.input();
        final net.minecraft.core.NonNullList<ItemStack> remainders;
        CommonHooks.setCraftingPlayer(player);
        try { remainders = player.level().getRecipeManager().getRemainingItemsFor(RecipeType.CRAFTING, input, player.level()); }
        finally { CommonHooks.setCraftingPlayer(null); }
        if (remainders.size() != input.size()) return false;
        for (int y = 0; y < input.height(); y++) for (int x = 0; x < input.width(); x++) {
            int slot = x + position.left() + (y + position.top()) * grid.getWidth();
            var left = grid.getItem(slot).copy(); if (!left.isEmpty()) left.shrink(1);
            var remainder = remainders.get(x + y * input.width()).copy(); if (remainder.isEmpty()) continue;
            if (left.isEmpty()) { if (remainder.getCount() > Math.min(grid.getMaxStackSize(), remainder.getMaxStackSize())) return false; }
            else if (ItemStack.isSameItemSameComponents(left, remainder)) {
                if (left.getCount() + remainder.getCount() > Math.min(grid.getMaxStackSize(), remainder.getMaxStackSize())) return false;
            } else if (!MaterialTransaction.insert(inventory, remainder, limit)) return false;
        }
        return true;
    }
}
