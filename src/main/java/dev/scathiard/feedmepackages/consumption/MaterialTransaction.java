package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.domain.CacheEdit;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.CacheRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.Predicate;

/** Query/simulate/commit for original inventory + active cache. No destination callbacks or fallback drops. */
public final class MaterialTransaction {
    private final ServerPlayer player;
    private final CacheLedger ledger;
    private final CacheHandle handle;
    private final CacheRecord original;
    private final boolean preparation;
    private final long reservationEpoch;
    private final List<ItemStack> before, inventory, preparationInventory;
    private final List<CraftingReservations.Source> allocations = new ArrayList<>();
    private final CacheEdit<ItemVariantKey> edit;
    private boolean committed;

    private MaterialTransaction(ServerPlayer player, CacheHandle handle, boolean preparation) {
        this.player = player; this.handle = handle; ledger = CacheLedger.get(player.getServer());
        original = ledger.find(handle.cacheId()); this.preparation = preparation;
        reservationEpoch = CraftingReservations.epoch();
        before = copyInventory(player); inventory = copies(before); edit = original.state().edit();
        preparationInventory = copies(before);
    }
    public static Optional<MaterialTransaction> open(ServerPlayer player) {
        return open(player, false);
    }
    static Optional<MaterialTransaction> prepare(ServerPlayer player) { return open(player, true); }
    private static Optional<MaterialTransaction> open(ServerPlayer player, boolean preparation) {
        var access = AccessGate.resolve(player);
        return access.active() && !player.isSpectator() && player.getInventory().items.size() == 36
                ? Optional.of(new MaterialTransaction(player, access.handle(), preparation)) : Optional.empty();
    }
    public CacheHandle handle() { return handle; }
    public long revision() { return original.state().revision(); }
    public boolean cacheFirst() { return false; }
    List<CraftingReservations.Source> allocations() { return List.copyOf(allocations); }
    public List<ItemStack> inventory() { return copies(inventory); }
    public List<ItemStack> candidates(Predicate<ItemStack> accepts) {
        var result = new ArrayList<ItemStack>();
        for (int pass = 0; pass < 2; pass++) {
            boolean cache = pass == 1;
            if (cache) {
                for (var cell : edit.cells()) if (cell.amount() > 0 && cacheAvailable(cell.filter().stack(player.registryAccess(), 1)) > 0)
                    addCandidate(result, cell.filter().stack(player.registryAccess(), 1), accepts);
            } else for (int i = 0; i < inventory.size(); i++) if (inventoryAvailable(i) > 0) addCandidate(result, inventory.get(i), accepts);
        }
        return copies(result);
    }
    private static void addCandidate(List<ItemStack> result, ItemStack stack, Predicate<ItemStack> accepts) {
        if (accepts.test(stack.copy()) && result.stream().noneMatch(prior -> ItemStack.isSameItemSameComponents(prior, stack)))
            result.add(stack.copyWithCount(1));
    }
    public int available(ItemStack prototype) {
        int amount = cacheAvailable(prototype);
        for (int i = 0; i < inventory.size(); i++) if (ItemStack.isSameItemSameComponents(inventory.get(i), prototype)) amount = Math.addExact(amount, inventoryAvailable(i));
        return amount;
    }
    public int cacheAvailable(ItemStack prototype) {
        int index = cacheIndex(prototype);
        return index < 0 ? 0 : Math.max(0, edit.cells().get(index).amount()
                - CraftingReservations.reservedCache(handle.cacheId(), index, preparation ? player : null));
    }
    private int inventoryAvailable(int index) {
        return Math.max(0, inventory.get(index).getCount() - (preparation ? 0 : CraftingReservations.reservedInventory(player, index)));
    }
    private int cacheIndex(ItemStack prototype) {
        var cells = edit.cells();
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            if (cell.filter() != null && ItemStack.isSameItemSameComponents(prototype, cell.filter().stack(player.registryAccess(), 1))) return i;
        }
        return -1;
    }
    /** A failed simulation leaves this plan usable, with no partial removal. */
    public boolean take(ItemStack prototype, int amount) {
        return takeForGrid(prototype, amount, -1);
    }
    boolean takeForGrid(ItemStack prototype, int amount, int gridSlot) {
        ensurePlanning();
        if (prototype.isEmpty() || amount < 0 || available(prototype) < amount) return false;
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            var stack = inventory.get(i);
            if (!ItemStack.isSameItemSameComponents(stack, prototype)) continue;
            int taken = Math.min(remaining, inventoryAvailable(i)); stack.shrink(taken); remaining -= taken;
            if (taken > 0 && gridSlot >= 0) allocations.add(new CraftingReservations.Source(false, i, gridSlot, prototype, taken));
        }
        if (remaining > 0) {
            int index = cacheIndex(prototype), taken = Math.min(remaining, cacheAvailable(prototype));
            if (taken > 0) {
                edit.extract(index, taken); remaining -= taken;
                if (gridSlot >= 0) allocations.add(new CraftingReservations.Source(true, index, gridSlot, prototype, taken));
            }
        }
        if (remaining != 0) throw new IllegalStateException("Material simulation failed its own availability check");
        return true;
    }
    public boolean takeCache(ItemStack prototype, int amount) {
        ensurePlanning(); int index = cacheIndex(prototype);
        if (index < 0 || amount < 0 || cacheAvailable(prototype) < amount) return false;
        return edit.extract(index, amount) == amount;
    }
    /** Return displaced crafting inputs into the simulated original backpack. */
    public boolean returnToInventory(ItemStack stack) {
        ensurePlanning();
        var target = preparation ? preparationInventory : inventory;
        var next = copies(target);
        if (!insert(next, stack, player.getInventory().getMaxStackSize())) return false;
        for (int i = 0; i < 36; i++) target.set(i, next.get(i));
        return true;
    }
    public boolean valid() {
        if (committed || !player.getServer().isSameThread() || player.isSpectator()) return false;
        var access = AccessGate.resolve(player); var current = ledger.find(handle.cacheId());
        if (!access.active() || !handle.equals(access.handle()) || current == null
                || current.state().revision() != revision() || CraftingReservations.epoch() != reservationEpoch) return false;
        return matches(before, copyInventory(player));
    }
    /** Call only after destination simulation + validation, with no intervening external callbacks. */
    public boolean commit() {
        if (preparation) throw new IllegalStateException("A reservation cannot be committed as a debit");
        if (!valid()) return false;
        var next = edit.finish();
        if (!next.equals(original.state())) ledger.replace(handle, revision(), original.withState(next));
        for (int i = 0; i < 36; i++) player.getInventory().items.set(i, inventory.get(i).copy());
        committed = true; player.getInventory().setChanged(); return true;
    }
    boolean commitPreparation(net.minecraft.world.inventory.AbstractContainerMenu menu, List<ItemStack> display) {
        if (!preparation || !valid()) return false;
        for (int i = 0; i < 36; i++) player.getInventory().items.set(i, preparationInventory.get(i).copy());
        CraftingReservations.install(player, menu, handle, allocations, display);
        committed = true; player.getInventory().setChanged(); return true;
    }
    private void ensurePlanning() { if (committed) throw new IllegalStateException("Material plan already committed"); }
    public static List<ItemStack> copyInventory(ServerPlayer player) { return copies(player.getInventory().items); }
    public static List<ItemStack> copies(List<ItemStack> stacks) {
        var result = new ArrayList<ItemStack>(stacks.size()); for (var stack : stacks) result.add(stack.copy()); return result;
    }
    public static boolean matches(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!ItemStack.matches(first.get(i), second.get(i))) return false;
        return true;
    }
    public static boolean insert(List<ItemStack> inventory, ItemStack stack, int containerLimit) {
        if (stack.isEmpty()) return true;
        int remaining = stack.getCount(), limit = Math.min(containerLimit, stack.getMaxStackSize());
        for (int pass = 0; pass < 2 && remaining > 0; pass++) for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            var current = inventory.get(i);
            if (pass == 0 ? current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack) : !current.isEmpty()) continue;
            int moved = Math.min(remaining, Math.max(0, limit - current.getCount()));
            if (moved > 0) { inventory.set(i, stack.copyWithCount(current.getCount() + moved)); remaining -= moved; }
        }
        return remaining == 0;
    }
}
