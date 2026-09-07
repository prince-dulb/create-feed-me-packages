package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.mixin.CraftingItemsAccess;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import java.lang.ref.WeakReference;
import java.util.*;

/** Menu-local leases. Idle sources own every item; only a synchronous native operation materializes them. */
public final class CraftingReservations {
    private CraftingReservations() {}
    public record Source(boolean cache, int index, int gridSlot, ItemStack prototype, int amount) {
        public Source {
            if (prototype.isEmpty() || amount < 1 || index < 0 || gridSlot < 0) throw new IllegalArgumentException("Invalid material lease");
            prototype = prototype.copyWithCount(1);
        }
        @Override public ItemStack prototype() { return prototype.copy(); }
        Source count(int value) { return new Source(cache, index, gridSlot, prototype, value); }
    }
    private static final class Session {
        final WeakReference<AbstractContainerMenu> menu;
        final CacheHandle handle;
        List<Source> sources;
        List<ItemStack> display;
        boolean materialized;
        Session(AbstractContainerMenu menu, CacheHandle handle, List<Source> sources, List<ItemStack> display) {
            this.menu = new WeakReference<>(menu); this.handle = handle;
            this.sources = new ArrayList<>(sources); this.display = MaterialTransaction.copies(display);
        }
    }
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();
    private static long epoch;
    public static long epoch() { return epoch; }
    static void install(ServerPlayer player, AbstractContainerMenu menu, CacheHandle handle, List<Source> sources, List<ItemStack> display) {
        SESSIONS.put(player, new Session(menu, handle, sources, display)); epoch++;
    }
    public static int reservedCache(UUID cache, int cell, ServerPlayer except) {
        int sum = 0;
        for (var entry : SESSIONS.entrySet()) {
            var session = entry.getValue();
            if (entry.getKey() == except || session.materialized || !session.handle.cacheId().equals(cache)) continue;
            for (var source : session.sources) if (source.cache && source.index == cell) sum = Math.addExact(sum, source.amount);
        }
        return sum;
    }
    public static int reservedInventory(ServerPlayer player, int slot) {
        var session = SESSIONS.get(player);
        return session == null || session.materialized ? 0 : session.sources.stream().filter(s -> !s.cache && s.index == slot).mapToInt(Source::amount).sum();
    }
    public static List<Integer> gridAmounts(ServerPlayer player) {
        var session = SESSIONS.get(player);
        if (session == null || session.materialized || session.menu.get() != player.containerMenu) return List.of();
        var result = new ArrayList<Integer>(Collections.nCopies(session.display.size(), 0));
        for (var s : session.sources) result.set(s.gridSlot, result.get(s.gridSlot) + s.amount);
        return List.copyOf(result);
    }
    public static List<Integer> cacheAmounts(ServerPlayer player, int size) {
        var result = new ArrayList<Integer>(Collections.nCopies(size, 0)); var session = SESSIONS.get(player);
        if (session != null && !session.materialized) for (var s : session.sources) if (s.cache) result.set(s.index, result.get(s.index) + s.amount);
        return List.copyOf(result);
    }
    public static List<ItemStack> realGrid(ServerPlayer player, AbstractContainerMenu menu) {
        var grid = MaterialTransaction.copies(CraftingService.grid(menu).getItems()); var session = SESSIONS.get(player);
        if (session != null && session.menu.get() == menu && !session.materialized) strip(grid, session.sources);
        return grid;
    }
    private static void strip(List<ItemStack> grid, List<Source> sources) {
        for (var source : sources) {
            var stack = grid.get(source.gridSlot);
            if (ItemStack.isSameItemSameComponents(stack, source.prototype)) stack.shrink(Math.min(stack.getCount(), source.amount));
        }
    }
    public static void cancel(ServerPlayer player, AbstractContainerMenu menu) {
        var session = SESSIONS.get(player);
        if (session == null || session.menu.get() != menu) return;
        if (session.materialized) finish(player, session);
        session = SESSIONS.remove(player); epoch++;
        if (session == null) return;
        var grid = CraftingService.grid(menu); var raw = ((CraftingItemsAccess)grid).fmp$items();
        strip(raw, session.sources); menu.slotsChanged(grid); menu.broadcastChanges();
    }
    public static void forget(ServerPlayer player) {
        var session = SESSIONS.get(player);
        if (session != null && session.menu.get() != null) cancel(player, session.menu.get());
        else if (SESSIONS.remove(player) != null) epoch++;
    }
    public static void validate(ServerPlayer player) {
        var session = SESSIONS.get(player); if (session == null || session.materialized) return;
        var menu = session.menu.get();
        if (menu == null) { SESSIONS.remove(player); epoch++; return; }
        if (!valid(player, session)) cancel(player, menu);
    }
    private static boolean valid(ServerPlayer player, Session session) {
        var menu = session.menu.get(); var access = AccessGate.resolve(player);
        if (menu == null || player.containerMenu != menu || !menu.stillValid(player) || !access.active()
                || !session.handle.equals(access.handle()) || player.isSpectator()
                || !MaterialTransaction.matches(session.display, CraftingService.grid(menu).getItems())) return false;
        var record = CacheLedger.get(player.getServer()).find(session.handle.cacheId());
        if (record == null) return false;
        for (var source : session.sources) {
            if (source.cache) {
                var cell = record.state().cells().get(source.index);
                if (cell.filter() == null || !ItemStack.isSameItemSameComponents(source.prototype, cell.filter().stack(player.registryAccess(), 1))
                        || cell.amount() < reservedCache(session.handle.cacheId(), source.index, null)) return false;
            } else {
                var stack = player.getInventory().getItem(source.index);
                if (!ItemStack.isSameItemSameComponents(source.prototype, stack) || stack.getCount() < reservedInventory(player, source.index)) return false;
            }
        }
        return true;
    }
    public static boolean operating(ServerPlayer player) { var s = SESSIONS.get(player); return s != null && s.materialized; }
    public static boolean operating(UUID cache) {
        return cache != null && SESSIONS.values().stream().anyMatch(s -> s.materialized && s.handle.cacheId().equals(cache));
    }
    public static final class Scope implements AutoCloseable {
        private final ServerPlayer player; private final Session session;
        private final Integer prior;
        private boolean closed;
        private Scope(ServerPlayer player, Session session) {
            this.player = player; this.session = session; prior = session == null ? null : CraftingService.beginClick();
        }
        @Override public void close() {
            if (closed || session == null) return; closed = true;
            try { if (SESSIONS.get(player) == session && session.materialized) finish(player, session); }
            finally { CraftingService.endClick(prior); }
        }
    }
    public static Scope begin(ServerPlayer player, AbstractContainerMenu menu, int slot, int button, ClickType click) {
        var session = SESSIONS.get(player);
        if (session != null && session.materialized) return new Scope(player, null);
        validate(player); session = SESSIONS.get(player);
        // Editing a reserved backpack source cancels preparation first; the native hand sees its real full stack.
        if (session != null && ((slot >= 0 && slot < menu.slots.size() && menu.getSlot(slot).container == player.getInventory()
                && reservedInventory(player, menu.getSlot(slot).getContainerSlot()) > 0)
                || click == ClickType.SWAP && reservedInventory(player, button) > 0)) {
            cancel(player, menu); session = null;
        }
        var access = AccessGate.resolve(player);
        if (player.containerMenu != menu || !CraftingService.supported(menu) || !access.active() || player.isSpectator()) return new Scope(player, null);
        if (click != ClickType.QUICK_CRAFT && click != ClickType.PICKUP_ALL && (slot < 0 || slot > CraftingService.grid(menu).getContainerSize()))
            return new Scope(player, null);
        if (session == null) session = new Session(menu, access.handle(), List.of(), CraftingService.grid(menu).getItems());
        var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(session.handle.cacheId()); var edit = record.state().edit();
        var inventory = MaterialTransaction.copyInventory(player);
        for (var source : session.sources) {
            if (source.cache) {
                if (edit.extract(source.index, source.amount) != source.amount) throw new IllegalStateException("Validated lease lost its source");
            } else inventory.get(source.index).shrink(source.amount);
        }
        var next = edit.finish();
        if (!next.equals(record.state())) ledger.replace(session.handle, record.state().revision(), record.withState(next));
        for (int i = 0; i < 36; i++) player.getInventory().items.set(i, inventory.get(i));
        session.materialized = true; SESSIONS.put(player, session); epoch++;
        return new Scope(player, session);
    }
    /** Observe a complete native take before adding the next refill. Real manual inputs are used first. */
    public static void reconcile(ServerPlayer player) {
        var session = SESSIONS.get(player); if (session == null || !session.materialized || session.menu.get() == null) return;
        var current = CraftingService.grid(session.menu.get()).getItems();
        var remaining = new ArrayList<Source>(session.sources);
        for (int slot = 0; slot < session.display.size(); slot++) {
            var before = session.display.get(slot); var after = current.get(slot); final int index = slot;
            int held = remaining.stream().filter(s -> s.gridSlot == index).mapToInt(Source::amount).sum();
            int now = ItemStack.isSameItemSameComponents(before, after) ? after.getCount() : 0;
            int spent = Math.max(0, held - Math.min(before.getCount(), now));
            for (int i = 0; i < remaining.size() && spent > 0; i++) {
                var source = remaining.get(i); if (source.gridSlot != slot) continue;
                int used = Math.min(spent, source.amount); spent -= used;
                if (used == source.amount) remaining.remove(i--); else remaining.set(i, source.count(source.amount - used));
            }
        }
        session.sources = remaining; session.display = MaterialTransaction.copies(current);
    }
    static void appendDebits(ServerPlayer player, List<Source> sources) {
        var session = SESSIONS.get(player);
        if (session == null || !session.materialized) throw new IllegalStateException("Refill outside a native operation");
        session.sources.addAll(sources); session.display = MaterialTransaction.copies(CraftingService.grid(session.menu.get()).getItems());
    }
    private static List<Source> afterOneCraft(Session session) {
        var result = new ArrayList<Source>(); var usedSlots = new HashSet<Integer>();
        for (var source : session.sources) {
            int total = session.sources.stream().filter(s -> s.gridSlot == source.gridSlot).mapToInt(Source::amount).sum();
            boolean consumesLease = session.display.get(source.gridSlot).getCount() == total;
            int count = source.amount - (consumesLease && usedSlots.add(source.gridSlot) ? 1 : 0);
            if (count > 0) result.add(source.count(count));
        }
        return result;
    }
    /** Capacity planning includes unspent leases; temporary holes cannot become output space twice. */
    public static List<ItemStack> inventoryAfterCraft(ServerPlayer player) {
        var inventory = MaterialTransaction.copyInventory(player); var session = SESSIONS.get(player);
        if (session == null) return inventory;
        if (!session.materialized) {
            for (var source : session.sources) if (!source.cache) inventory.get(source.index).shrink(source.amount);
        }
        for (var source : afterOneCraft(session)) if (!source.cache
                && !MaterialTransaction.insert(inventory, source.prototype.copyWithCount(source.amount), player.getInventory().getMaxStackSize())) return null;
        return inventory;
    }
    public static boolean canQuickMoveInput(ServerPlayer player, int slot) {
        var session = SESSIONS.get(player);
        if (session == null || !session.materialized || slot < 1 || slot > session.display.size()) return true;
        var inventory = MaterialTransaction.copyInventory(player);
        for (var source : session.sources) if (!source.cache && source.gridSlot != slot - 1
                && !MaterialTransaction.insert(inventory, source.prototype.copyWithCount(source.amount), player.getInventory().getMaxStackSize())) return false;
        return MaterialTransaction.insert(inventory, player.containerMenu.getSlot(slot).getItem(), player.getInventory().getMaxStackSize());
    }
    private static void finish(ServerPlayer player, Session session) {
        reconcile(player);
        var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(session.handle.cacheId()); var edit = record.state().edit();
        var inventory = MaterialTransaction.copyInventory(player); var restored = new ArrayList<Source>();
        for (var source : session.sources) {
            if (source.cache) {
                var key = ItemVariantKey.of(source.prototype, player.registryAccess());
                int returned = edit.insert(source.index, key, source.amount);
                if (returned > 0) restored.add(source.count(returned));
            } else {
                int remaining = source.amount;
                for (int pass = 0; pass < 3 && remaining > 0; pass++) for (int i = 0; i < 36 && remaining > 0; i++) {
                    if (pass == 0 && i != source.index) continue;
                    var current = inventory.get(i);
                    if (pass == 1 && current.isEmpty() || pass == 2 && !current.isEmpty()) continue;
                    if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, source.prototype)) continue;
                    int count = Math.min(remaining, Math.max(0, Math.min(source.prototype.getMaxStackSize(), player.getInventory().getMaxStackSize()) - current.getCount()));
                    if (count > 0) {
                        inventory.set(i, source.prototype.copyWithCount(current.getCount() + count)); remaining -= count;
                        restored.add(new Source(false, i, source.gridSlot, source.prototype, count));
                    }
                }
            }
        }
        // Unrestorable items (e.g. a reentrant third-party mutation) remain real in the grid, never discarded.
        int left = session.sources.stream().mapToInt(Source::amount).sum() - restored.stream().mapToInt(Source::amount).sum();
        if (left > 0) dev.scathiard.feedmepackages.FeedMePackages.LOGGER.warn("Crafting source changed during native operation; {} real inputs retained in grid", left);
        var next = edit.finish();
        if (!next.equals(record.state())) ledger.replace(session.handle, record.state().revision(), record.withState(next));
        for (int i = 0; i < 36; i++) player.getInventory().items.set(i, inventory.get(i));
        session.sources = restored; session.materialized = false; epoch++;
        player.getInventory().setChanged();
        if (restored.isEmpty()) SESSIONS.remove(player);
        var menu = session.menu.get(); if (menu != null) { menu.slotsChanged(CraftingService.grid(menu)); menu.broadcastChanges(); }
    }
}
