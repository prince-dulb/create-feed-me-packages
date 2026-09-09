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
import dev.scathiard.feedmepackages.network.PanelNetwork;
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
    private static final Map<ServerPlayer, CreativeOperation> CREATIVE = new WeakHashMap<>();
    // 0 transfers the cursor, 1 changes/discards a list cursor locally, 2 operates an independent slot.
    private static final class CreativeOperation {
        final Hold hold; final int sequence, mode; final ItemStack before;
        int realLeft, accepted;
        CreativeOperation(Hold hold, int sequence, int mode, ItemStack before) {
            this.hold=hold; this.sequence=sequence; this.mode=mode; this.before=before.copy();
            realLeft=Math.max(0, before.getCount()-hold.amount);
        }
    }
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
            PanelNetwork.sendCursor(player, hold.panelSession, hold.requestSeq, stack, hold.amount);
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
        CREATIVE.remove(player);
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

    /** A client that replaced/consumed its preview asks the server to release only its own specific take.
     *  All of creative + current menu + panel session + exact take rule must match; no wild card. Returns
     *  the real result so the caller can reject a mismatched/stale target instead of pretending OK. */
    public static CacheActions.Result releasePreview(ServerPlayer player, UUID panelSession, int targetTakeSequence) {
        if (targetTakeSequence <= 0) return CacheActions.Result.INVALID_REQUEST;
        Hold hold = HOLDS.get(player);
        if (hold == null) return CacheActions.Result.OK;
        if (!hold.creative || hold.menu.get() != player.containerMenu
                || !Objects.equals(hold.panelSession, panelSession)
                || hold.requestSeq <= 0 || hold.requestSeq != targetTakeSequence)
            return CacheActions.Result.STALE;
        cancel(player);
        return CacheActions.Result.OK;
    }

    public static CacheActions.Result beginCreative(ServerPlayer player, UUID session, int take, int sequence, int mode, ItemStack before) {
        Hold hold = HOLDS.get(player);
        if (mode < 0 || mode > 2 || take <= 0 || sequence <= 0) return CacheActions.Result.INVALID_REQUEST;
        if (hold == null || !hold.creative || hold.menu.get() != player.containerMenu
                || !Objects.equals(session, hold.panelSession) || hold.requestSeq != take
                || !same(before, hold) || before.getCount() < hold.amount || CREATIVE.containsKey(player))
            return CacheActions.Result.STALE;
        CREATIVE.put(player, new CreativeOperation(hold, sequence, mode, before));
        return CacheActions.Result.OK;
    }

    public static CacheActions.Result endCreative(ServerPlayer player, UUID session, int take, int beginSequence, ItemStack after) {
        CreativeOperation operation = CREATIVE.get(player);
        if (operation == null || operation.sequence != beginSequence || operation.hold.requestSeq != take
                || !Objects.equals(session, operation.hold.panelSession)) return CacheActions.Result.STALE;
        CREATIVE.remove(player);
        Hold hold = operation.hold;
        if (hold.menu.get() != player.containerMenu) return CacheActions.Result.STALE;
        if (after == null) {
            if (operation.mode != 0) { hold.amount=0; HOLDS.remove(player, hold); ++epoch; }
            if (operation.mode == 0 && hold.amount > 0)
                PanelNetwork.sendCursor(player, session, take,
                        hold.prototype.copyWithCount(operation.before.getCount()-operation.accepted), hold.amount);
            else PanelNetwork.sendCursor(player, session, take, null, 0);
            return CacheActions.Result.OK;
        }
        ItemStack corrected = after.copy();
        if (operation.mode == 1) {
            // Creative list edits destroy only the alias, never charge the cache. Added list items
            // are real; shrinking a mixed cursor removes the real part first.
            int remaining = same(after, hold) ? Math.min(hold.amount, after.getCount()) : 0;
            if (remaining != hold.amount) { hold.amount=remaining; ++epoch; }
            if (remaining == 0) HOLDS.remove(player, hold);
        } else if (operation.mode == 0) {
            int locallyRemoved = same(after, hold) ? Math.max(0, operation.before.getCount()-after.getCount())
                    : operation.before.getCount();
            int missing = Math.max(0, locallyRemoved-operation.accepted);
            // Native rejection left uncommitted material. Restore only that portion and only to a
            // compatible cursor; its tagged reply cannot overwrite a later operation or closed UI.
            if (missing > 0) {
                int count = (same(corrected, hold) ? corrected.getCount() : 0) + missing;
                if (count > hold.prototype.getMaxStackSize()) return CacheActions.Result.STALE;
                corrected = hold.prototype.copyWithCount(count);
            }
        }
        PanelNetwork.sendCursor(player, session, take, corrected, hold.amount);
        return CacheActions.Result.OK;
    }

    private static void creativeDebit(ServerPlayer player, Hold hold, int transferred) {
        CreativeOperation operation = CREATIVE.get(player);
        if (operation != null && operation.hold == hold) {
            if (operation.mode != 0) return;
            operation.accepted += transferred;
            int real = Math.min(operation.realLeft, transferred);
            operation.realLeft -= real;
            transferred -= real;
        }
        debit(player, hold, transferred);
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
        if (hold.requestSeq > 0 && !CREATIVE.containsKey(player)) return -1;
        ItemStack before=player.inventoryMenu.getSlot(slot).getItem();
        return same(before,hold) ? before.getCount() : 0;
    }
    public static void creativeAfter(ServerPlayer player, int slot, int before) {
        Hold hold=HOLDS.get(player);
        if (hold == null || !hold.creative || before < 0 || slot >= player.inventoryMenu.slots.size()) return;
        ItemStack after=player.inventoryMenu.getSlot(slot).getItem();
        int added=same(after,hold) ? Math.max(0,after.getCount()-before) : 0;
        creativeDebit(player,hold,added);
    }
    public static void creativeDropped(ServerPlayer player, ItemStack offered, ItemEntity entity) {
        Hold hold=HOLDS.get(player);
        if (hold == null || !hold.creative || !same(offered,hold)) return;
        if (hold.requestSeq > 0 && !CREATIVE.containsKey(player)) return;
        if (entity != null) creativeDebit(player,hold,offered.getCount());
        else restoreCreativeCursor(player);
    }
    public static void restoreCreativeCursor(ServerPlayer player) {
        if (CREATIVE.containsKey(player)) return; // Reconcile once at the matching native operation end.
        Hold hold=HOLDS.get(player);
        if (hold != null && hold.creative && hold.requestSeq <= 0) cursor(player,hold,hold.prototype.copyWithCount(hold.amount));
    }
}
