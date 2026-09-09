package dev.scathiard.feedmepackages.interaction;

import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.domain.CacheEdit;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.CacheRecord;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Server-thread cursor escrow. A cursor preview is the SAME material as the cache stock (a display
 * alias), never a second copy. The cache amount stays put until the player really places the items
 * (into the backpack, a crafting grid, or the world); then the cache is debited once. Returning the
 * preview to its source cell only releases the alias and never re-inserts stock.
 *
 * <p>Unlike the retired t49 CONFIRM_TAKE/RELEASE_TAKE packet model, the settlement is driven by the
 * real native cursor events (click wrapper, creative slot/drop packets) so the server owns the fact
 * of whether the items are still on the cursor or already left the cache.</p>
 */
public final class CursorReservations {
    private static final Map<ServerPlayer, Hold> HOLDS = new WeakHashMap<>();
    private static long epoch;
    public static long epoch() { return epoch; }
    private static final class Hold {
        final WeakReference<AbstractContainerMenu> menu;
        final CacheHandle handle;
        final int slot;
        final ItemVariantKey variant;
        final ItemStack prototype;
        final boolean creative;
        final UUID panelSession;
        final int requestSeq;
        int amount;
        boolean clicking;
        int clickBefore;
        Hold(ServerPlayer player, CacheHandle handle, int slot, ItemVariantKey variant, ItemStack prototype, int amount, boolean creative, UUID panelSession, int requestSeq) {
            this.menu = new WeakReference<>(player.containerMenu);
            this.handle = handle; this.slot = slot; this.variant = variant;
            this.prototype = prototype.copyWithCount(1); this.amount = amount; this.creative = creative;
            this.panelSession = panelSession; this.requestSeq = requestSeq;
        }
    }
    private CursorReservations() {}

    /** Called only AFTER CacheActions' session, identity, revision and slot validation. */
    public static CacheActions.Result action(ServerPlayer player, CacheHandle handle, int slot,
            CacheActions.Intent intent, ItemStack creativeCursor, int requestSeq) {
        Hold hold = HOLDS.get(player);
        ItemStack carried = creativeCursor == null ? player.containerMenu.getCarried() : creativeCursor;
        if (hold != null && intent.action() == CacheActions.Action.DEPOSIT) {
            if (!hold.handle.equals(handle) || hold.menu.get() != player.containerMenu
                    || hold.creative != (creativeCursor != null) || !same(carried, hold)
                    || carried.getCount() < hold.amount) return CacheActions.Result.STALE;
            if (intent.first() != 0 && intent.first() != 1) return CacheActions.Result.INVALID_REQUEST;
            if (slot != hold.slot) return CacheActions.Result.DUPLICATE_FILTER;
            return putBack(player, hold, carried, intent.first() == 1);
        }
        if (intent.action() != CacheActions.Action.TAKE_CURSOR || !carried.isEmpty()) return null;
        if (hold != null) return CacheActions.Result.STALE;
        if (intent.first() < 1) return CacheActions.Result.INVALID_REQUEST;
        CacheRecord record = CacheLedger.get(player.getServer()).find(handle.cacheId());
        Cell<ItemVariantKey> cell = record.state().cells().get(slot);
        if (cell.filter() == null) return CacheActions.Result.NO_SPACE;
        int available = Math.max(0, cell.amount() - CraftingReservations.reservedCache(handle.cacheId(), slot, null));
        int amount = Math.min(Math.min(available, intent.first()), cell.filter().stackSize());
        if (amount == 0) return CacheActions.Result.NO_SPACE;
        ItemStack prototype = cell.filter().stack(player.registryAccess(), 1);
        Hold next = new Hold(player, handle, slot, cell.filter(), prototype, amount, creativeCursor != null, intent.session(), requestSeq);
        HOLDS.put(player, next);
        ++epoch;
        cursor(player, next, prototype.copyWithCount(amount));
        player.containerMenu.broadcastChanges();
        return CacheActions.Result.OK;
    }

    public static int reserved(UUID cache, int slot) {
        int sum = 0;
        if (cache == null) return 0;
        for (Hold hold : HOLDS.values())
            if (hold.slot == slot && hold.handle.cacheId().equals(cache)) sum += hold.amount;
        return sum;
    }

    private static boolean same(ItemStack stack, Hold hold) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, hold.prototype);
    }

    private static void cursor(ServerPlayer player, Hold hold, ItemStack stack) {
        AbstractContainerMenu menu = hold.menu.get();
        if (menu == null) return;
        if (hold.creative) {
            // Creative cursor ownership is client-side in vanilla, as in test.48.
            player.connection.send(new ClientboundContainerSetContentPacket(menu.containerId,
                    menu.incrementStateId(), menu.getItems(), stack));
        } else menu.setCarried(stack);
    }

    private static CacheActions.Result putBack(ServerPlayer player, Hold hold, ItemStack carried, boolean one) {
        CacheLedger ledger = CacheLedger.get(player.getServer());
        CacheRecord record = ledger.find(hold.handle.cacheId());
        int real = carried.getCount() - hold.amount;
        int requestedReal = one ? Math.min(1, real) : real;
        int restored = 0;
        if (requestedReal > 0) {
            CacheEdit<ItemVariantKey> edit = record.state().edit();
            restored = edit.insert(hold.slot, hold.variant, requestedReal);
            if (restored > 0) ledger.replace(hold.handle, record.state().revision(), record.withState(edit.finish()));
        }
        int released = one ? (real == 0 ? 1 : 0) : hold.amount;
        if (restored + released == 0) return CacheActions.Result.NO_SPACE;
        hold.amount -= released;
        if (released > 0) ++epoch;
        ItemStack next = carried.copy(); next.shrink(restored + released);
        if (hold.amount == 0) HOLDS.remove(player);
        cursor(player, hold, next);
        player.containerMenu.broadcastChanges();
        return CacheActions.Result.OK;
    }

    /** Do not alter cache stock on cancellation, including menu close, logout and death. */
    public static void cancel(ServerPlayer player) {
        Hold hold = HOLDS.get(player);
        if (hold == null) return;
        if (hold.clicking) settleClick(player, hold);
        HOLDS.remove(player);
        ++epoch;
        AbstractContainerMenu menu = hold.menu.get();
        // Survival syncs away the unplaced preview by shrinking the carried stack; the creative carry is
        // client-owned (TAKE_CURSOR sends a full-content packet without setting the server carried), so
        // the preview alias is withdrawn client-side in LogisticsPanel.close() when the screen closes.
        if (!hold.creative && menu != null && same(menu.getCarried(), hold)) {
            ItemStack real = menu.getCarried().copy(); real.shrink(hold.amount);
            menu.setCarried(real);
        }
    }

    /** A client that replaced/consumed its preview asks the server to release only its own panel-session
     *  hold, so a later independent creative-source placement is not mis-debited by creativeAfter. A
     *  requestSeq (>=0) narrows to that specific take so an old release cannot clear a NEWER same-session
     *  hold; otherwise it falls back to panel session + this player's own hold. Never another player's. */
    public static void releasePreview(ServerPlayer player, UUID panelSession, int requestSeq) {
        Hold hold = HOLDS.get(player);
        if (hold == null) return;
        if (requestSeq >= 0) { if (hold.requestSeq != requestSeq) return; }
        else if (!Objects.equals(hold.panelSession, panelSession)) return;
        cancel(player);
    }

    public static void validate(ServerPlayer player) {
        Hold hold = HOLDS.get(player);
        if (hold == null || hold.clicking) return;
        AccessGate.Result access = AccessGate.resolve(player);
        CacheRecord record = CacheLedger.get(player.getServer()).find(hold.handle.cacheId());
        if (hold.creative && !player.getAbilities().instabuild || !access.active() || !hold.handle.equals(access.handle()) || hold.menu.get() != player.containerMenu
                || record == null || hold.slot >= record.state().cells().size()
                || !hold.variant.equals(record.state().cells().get(hold.slot).filter())
                || record.state().cells().get(hold.slot).amount() < hold.amount) {
            cancel(player); return;
        }
        if (!hold.creative && (!same(player.containerMenu.getCarried(), hold)
                || player.containerMenu.getCarried().getCount() < hold.amount)) {
            // A non-vanilla cursor mutation escaped the click wrapper: reconcile only
            // the missing ghost portion, never restore possibly transferred items.
            int left = same(player.containerMenu.getCarried(), hold) ? player.containerMenu.getCarried().getCount() : 0;
            debit(player, hold, hold.amount - left);
        }
    }

    private static void debit(ServerPlayer player, Hold hold, int amount) {
        amount = Math.min(hold.amount, Math.max(0, amount));
        if (amount == 0) return;
        CacheLedger ledger = CacheLedger.get(player.getServer());
        CacheRecord record = ledger.find(hold.handle.cacheId());
        if (record == null || !hold.variant.equals(record.state().cells().get(hold.slot).filter()))
            throw new IllegalStateException("Cursor reservation lost its cache source");
        CacheEdit<ItemVariantKey> edit = record.state().edit();
        if (edit.extract(hold.slot, amount) != amount)
            throw new IllegalStateException("Cursor reservation stock was consumed twice");
        ledger.replace(hold.handle, record.state().revision(), record.withState(edit.finish()));
        hold.amount -= amount;
        ++epoch;
        if (hold.amount == 0) HOLDS.remove(player);
    }

    public static Scope beforeClick(ServerPlayer player, AbstractContainerMenu menu) {
        Hold hold = HOLDS.get(player);
        if (hold == null || hold.creative || hold.menu.get() != menu || hold.clicking) return new Scope(player, null);
        validate(player);
        hold = HOLDS.get(player);
        if (hold == null) return new Scope(player, null);
        hold.clicking = true;
        hold.clickBefore = menu.getCarried().getCount();
        return new Scope(player, hold);
    }
    private static void settleClick(ServerPlayer player, Hold hold) {
        ItemStack after = hold.menu.get().getCarried();
        int realBefore = Math.max(0, hold.clickBefore - hold.amount);
        int moved = same(after, hold) ? Math.max(0, hold.clickBefore - after.getCount() - realBefore) : hold.amount;
        debit(player, hold, moved);
        hold.clickBefore = after.getCount();
    }
    public static final class Scope implements AutoCloseable {
        private final ServerPlayer player;
        private final Hold hold;
        private boolean closed;
        private Scope(ServerPlayer player, Hold hold) {this.player=player;this.hold=hold;}
        @Override public void close() {
            if (closed) return; closed=true;
            if (hold == null) return;
            try {
                if (HOLDS.get(player) != hold) return;
                settleClick(player, hold);
            } finally {hold.clicking=false;}
        }
    }

    /** Creative mode places through its own packet instead of AbstractContainerMenu.clicked. */
    public static int creativeBefore(ServerPlayer player, int slot) {
        Hold hold=HOLDS.get(player);
        if (hold == null || !hold.creative || slot < 1 || slot >= player.inventoryMenu.slots.size()) return -1;
        ItemStack before=player.inventoryMenu.getSlot(slot).getItem();
        return same(before,hold) ? before.getCount() : 0;
    }
    public static void creativeAfter(ServerPlayer player, int slot, int before) {
        Hold hold=HOLDS.get(player);
        if (hold == null || !hold.creative || before < 0 || slot >= player.inventoryMenu.slots.size()) return;
        ItemStack after=player.inventoryMenu.getSlot(slot).getItem();
        int added=same(after,hold) ? Math.max(0,after.getCount()-before) : 0;
        debit(player,hold,added);
    }
    public static void creativeDropped(ServerPlayer player, ItemStack offered, ItemEntity entity) {
        Hold hold=HOLDS.get(player);
        if (hold == null || !hold.creative || !same(offered,hold)) return;
        if (entity != null) debit(player,hold,offered.getCount());
        else restoreCreativeCursor(player);
    }
    public static void restoreCreativeCursor(ServerPlayer player) {
        Hold hold=HOLDS.get(player);
        if (hold != null && hold.creative) cursor(player,hold,hold.prototype.copyWithCount(hold.amount));
    }
}
