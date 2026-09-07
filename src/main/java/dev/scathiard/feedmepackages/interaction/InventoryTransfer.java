package dev.scathiard.feedmepackages.interaction;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** A bounded original-inventory insertion plan; no fallback drops or third-party container callbacks. */
public final class InventoryTransfer {
    private InventoryTransfer() {}
    public static final int SLOTS = 36;
    public record Plan(List<ItemStack> before, List<ItemStack> after, int moved) {
        public Plan { before = copies(before); after = copies(after); }
        @Override public List<ItemStack> before() { return copies(before); }
        @Override public List<ItemStack> after() { return copies(after); }
        public boolean stillValid(Inventory inventory) {
            if (inventory.items.size() < SLOTS) return false;
            for (int i = 0; i < SLOTS; i++) if (!ItemStack.matches(before.get(i), inventory.getItem(i))) return false;
            return true;
        }
        public void commit(Inventory inventory) {
            if (!stillValid(inventory)) throw new IllegalStateException("Inventory changed after simulation");
            // Native item use holds the original stack reference; leave unrelated slots intact.
            for (int i = 0; i < SLOTS; i++)
                if (!ItemStack.matches(before.get(i), after.get(i))) inventory.setItem(i, after.get(i).copy());
            inventory.setChanged();
        }
    }

    public static Plan insert(Inventory inventory, ItemStack prototype, int amount) {
        if (prototype.isEmpty() || amount < 0 || inventory.items.size() < SLOTS) throw new IllegalArgumentException("Invalid insertion");
        var before = new ArrayList<ItemStack>(SLOTS);
        for (int i = 0; i < SLOTS; i++) before.add(inventory.getItem(i).copy());
        var after = new ArrayList<>(copies(before)); int remaining = amount;
        int limit = Math.min(prototype.getMaxStackSize(), inventory.getMaxStackSize());
        for (int pass = 0; pass < 2 && remaining > 0; pass++) for (int i = 0; i < SLOTS && remaining > 0; i++) {
            var current = after.get(i);
            if (pass == 0 ? current.isEmpty() || !ItemStack.isSameItemSameComponents(current, prototype) : !current.isEmpty()) continue;
            int moved = Math.min(remaining, Math.max(0, limit - current.getCount()));
            if (moved > 0) { after.set(i, prototype.copyWithCount(current.getCount() + moved)); remaining -= moved; }
        }
        return new Plan(before, after, amount - remaining);
    }
    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
}
