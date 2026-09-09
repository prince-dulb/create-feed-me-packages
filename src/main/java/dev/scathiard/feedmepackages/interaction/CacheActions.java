package dev.scathiard.feedmepackages.interaction;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-side panel intent interpreter. A session is context, never a substitute for current access. */
public final class CacheActions {
    private CacheActions() {}
    public enum Action { DEPOSIT, TAKE_CURSOR, TAKE_INVENTORY, SET_GHOST, CLEAR_FILTER, THRESHOLDS, RESET_REQUEST, PREFERENCE, TERMINAL_ENABLED, CLEAR_NETWORK, TAKE_RESIDUAL, FILL_RECIPE, SET_RETURN_ADDRESS, RELEASE_PREVIEW, CREATIVE_BEGIN, CREATIVE_END }
    public enum Result { OK, STALE, NOT_ACTIVE, INVALID_ITEM, DUPLICATE_FILTER, FILTER_OCCUPIED, NO_SPACE, INVALID_REQUEST, MISSING_MATERIAL, UNSUPPORTED_RECIPE, TOO_COMPLEX }
    public record Intent(UUID session, long revision, Action action, int slot, int first, int second, String template) {
        public Intent {
            Objects.requireNonNull(session); Objects.requireNonNull(action); Objects.requireNonNull(template);
            if (template.getBytes(StandardCharsets.UTF_8).length > ItemVariantKey.MAX_BYTES)
                throw new IllegalArgumentException("Oversized intent template");
        }
    }
    private record TerminalProof(int slot, ItemStack stack) {}
    private record Session(UUID id, WeakReference<AbstractContainerMenu> menu, CacheHandle handle, List<TerminalProof> terminals) {}
    public record View(UUID session, AccessGate.Result access, CacheRecord record, boolean cacheFirst) {}
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();

    public static View open(ServerPlayer player) {
        var access = AccessGate.resolve(player); var menu = player.containerMenu;
        if (!(menu instanceof InventoryMenu || menu instanceof CraftingMenu)) {
            SESSIONS.remove(player); return new View(null, access, null, false);
        }
        var proofs = access.terminals().stream().map(t -> new TerminalProof(t.slot(), t.stack().copy())).toList();
        var session = new Session(UUID.randomUUID(), new WeakReference<>(menu), access.handle(), proofs);
        SESSIONS.put(player, session);
        var ledger = CacheLedger.get(player.getServer());
        return new View(session.id(), access, access.active() ? ledger.find(access.handle().cacheId()) : null, ledger.cacheFirst(player.getUUID()));
    }

    public static void close(ServerPlayer player) {
        // Cancel any unplaced cursor preview (the cache amount was never deducted for it).
        CursorReservations.cancel(player);
        SESSIONS.remove(player);
    }

    public static View snapshot(ServerPlayer player) {
        validateOpenContext(player);
        var session = SESSIONS.get(player);
        if (session == null) return open(player);
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer());
        return new View(session.id(), access, access.active() ? ledger.find(access.handle().cacheId()) : null,
                ledger.cacheFirst(player.getUUID()));
    }

    public static void validateOpenContext(ServerPlayer player) {
        CursorReservations.validate(player);
        var session = SESSIONS.get(player); if (session == null) return;
        var access = AccessGate.resolve(player);
        if (session.menu().get() != player.containerMenu || !Objects.equals(session.handle(), access.handle())
                || access.terminals().size() != session.terminals().size()) { close(player); return; }
        for (var proof : session.terminals()) {
            var actual = access.terminals().stream().filter(t -> t.slot() == proof.slot()).findFirst().orElse(null);
            if (actual == null || !ItemStack.matches(actual.stack(), proof.stack())) { close(player); return; }
        }
    }

    public static Result execute(ServerPlayer player, Intent intent) {
        return execute(player, intent, -1);
    }

    public static Result execute(ServerPlayer player, Intent intent, int requestSeq) {
        // Creative inventory / ordinary packed access is gated HERE (the new-seq overload), so the old
        // signature delegating to it cannot bypass the restriction by omitting the sequence.
        if (player.gameMode.isCreative() && player.containerMenu instanceof InventoryMenu
                && (intent.action() == Action.DEPOSIT || intent.action() == Action.TAKE_CURSOR || intent.action() == Action.TAKE_RESIDUAL))
            return Result.INVALID_REQUEST;
        return execute(player, intent, null, requestSeq);
    }

    public static Result executeCreative(ServerPlayer player, Intent intent, String template, int count) {
        return executeCreative(player, intent, template, count, -1);
    }

    public static Result executeCreative(ServerPlayer player, Intent intent, String template, int count, int requestSeq) {
        if (!player.getAbilities().instabuild || !player.gameMode.isCreative() || !(player.containerMenu instanceof InventoryMenu)
                || !(intent.action() == Action.DEPOSIT || intent.action() == Action.TAKE_CURSOR || intent.action() == Action.TAKE_RESIDUAL))
            return Result.INVALID_REQUEST;
        ItemStack cursor;
        try {
            if (count == 0 && template.isEmpty()) cursor = ItemStack.EMPTY;
            else cursor = ItemVariantKey.decode(template, player.registryAccess()).stack(player.registryAccess(), count);
        } catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
        // Creative inventory owns its cursor on the client. A second server cursor would be
        // returned by vanilla on close after SetCreativeModeSlot has already placed the item.
        if (!player.containerMenu.getCarried().isEmpty()) return Result.STALE;
        return execute(player, intent, cursor, requestSeq);
    }

    private static Result execute(ServerPlayer player, Intent intent, ItemStack creativeCursor, int requestSeq) {
        var access = AccessGate.resolve(player); var session = SESSIONS.get(player);
        if (session == null || !session.id().equals(intent.session()) || session.menu().get() != player.containerMenu) return Result.STALE;
        // A client that replaced/consumed its preview asks to release only its own panel-session hold,
        // before an independent creative-source placement could otherwise be mis-debited by creativeAfter.
        if (intent.action() == Action.RELEASE_PREVIEW) {
            // Intent.first carries the target takeSequence (the take being replaced); slot stays -1. The
            // release Command's own sequence is only this message's increment, never the target.
            return CursorReservations.releasePreview(player, intent.session(), intent.first());
        }
        // Retain enum ordinals as explicit rejection paths, never as hidden old UI capabilities.
        if (intent.action() == Action.TERMINAL_ENABLED || intent.action() == Action.CLEAR_NETWORK
                || intent.action() == Action.SET_GHOST || intent.action() == Action.TAKE_RESIDUAL
                || intent.action() == Action.PREFERENCE) return Result.INVALID_REQUEST;
        if (!access.active()) return Result.NOT_ACTIVE;
        if (!access.handle().equals(session.handle())) return Result.STALE;
        if (CraftingReservations.operating(access.handle().cacheId())) return Result.STALE;
        var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        if (before.state().revision() != intent.revision()) return Result.STALE;
        if (intent.action() == Action.FILL_RECIPE) return recipe(player, intent);
        // The return address is a cache-level preference, not a cell action: it uses no slot, so it
        // must bypass the per-cell slot validation below. Setting it marks the ledger dirty (saved).
        if (intent.action() == Action.SET_RETURN_ADDRESS) {
            if (intent.template().length() > 128) return Result.INVALID_REQUEST;
            if (intent.template().equals(ledger.returnAddress(access.handle().cacheId()))) return Result.OK;
            ledger.setReturnAddress(access.handle().cacheId(), intent.template());
            return Result.OK;
        }
        var carried = creativeCursor == null ? player.containerMenu.getCarried() : creativeCursor;
        int slot = intent.slot();
        if (slot < 0 || slot >= before.state().cells().size()) return Result.INVALID_REQUEST;
        // A cursor preview/return is settled by the real cursor events; it is handled before the
        // cell actions below so a held preview never re-enters a plain insert (which would add stock).
        Result cursorResult = CursorReservations.action(player, access.handle(), slot, intent, creativeCursor, requestSeq);
        if (cursorResult != null) return cursorResult;
        var cell = before.state().cells().get(slot); var edit = before.state().edit();
        ItemStack nextCursor = null; InventoryTransfer.Plan inventoryPlan = null;
        switch (intent.action()) {
            case DEPOSIT -> {
                if (intent.first() != 0 && intent.first() != 1) return Result.INVALID_REQUEST;
                ItemVariantKey variant;
                try {
                    if (carried.isEmpty() || carried.getCount() > carried.getMaxStackSize()) return Result.INVALID_ITEM;
                    variant = ItemVariantKey.of(carried, player.registryAccess());
                } catch (IllegalArgumentException invalid) { return Result.INVALID_ITEM; }
                int duplicate = before.state().find(variant);
                if (duplicate >= 0 && duplicate != slot) return Result.DUPLICATE_FILTER;
                if (cell.amount() > 0 && !variant.equals(cell.filter())) return Result.FILTER_OCCUPIED;
                edit.filter(slot, variant);
                int moved = edit.insert(slot, variant, intent.first() == 1 ? 1 : carried.getCount());
                if (moved == 0) return Result.NO_SPACE;
                nextCursor = carried.copy(); nextCursor.shrink(moved);
            }
            case TAKE_INVENTORY -> {
                // Shift-take is a real placement: it deducts immediately, leaving no pending preview.
                if (cell.filter() == null || cell.amount() == 0) return Result.NO_SPACE;
                var prototype = cell.filter().stack(player.registryAccess(), 1);
                if (intent.first() < 1) return Result.INVALID_REQUEST;
                int available = Math.min(Math.max(0, cell.amount() - CraftingReservations.reservedCache(access.handle().cacheId(), slot, null)), intent.first());
                inventoryPlan = InventoryTransfer.insert(player.getInventory(), prototype, available);
                int moved = inventoryPlan.moved();
                if (moved == 0) return Result.NO_SPACE;
                edit.extract(slot, moved);
            }
            case CLEAR_FILTER -> {
                if (cell.amount() != 0) return Result.FILTER_OCCUPIED;
                edit.filter(slot, null);
            }
            case THRESHOLDS -> {
                try { edit.thresholds(slot, intent.first(), intent.second()); }
                catch (IllegalArgumentException invalid) { return Result.INVALID_REQUEST; }
            }
            case RESET_REQUEST -> edit.reset(slot);
            default -> { return Result.INVALID_REQUEST; }
        }
        if (inventoryPlan != null && !inventoryPlan.stillValid(player.getInventory())) return Result.STALE;
        var replacement = before.withState(edit.finish());
        // Only original menu/inventory setters follow this commit; no external insert/drop callbacks.
        ledger.replace(access.handle(), before.state().revision(), replacement);
        if (nextCursor != null) cursor(player, nextCursor, creativeCursor != null, intent.session(), requestSeq);
        if (inventoryPlan != null) inventoryPlan.commit(player.getInventory());
        player.containerMenu.broadcastChanges(); return Result.OK;
    }

    private static void cursor(ServerPlayer player, ItemStack next, boolean creative, UUID session, int sequence) {
        var menu = player.containerMenu;
        if (!creative) { menu.setCarried(next); return; }
        dev.scathiard.feedmepackages.network.PanelNetwork.sendCursor(player, session, 0, next, 0);
    }

    @SuppressWarnings("unchecked")
    private static Result recipe(ServerPlayer player, Intent intent) {
        if ((intent.first() != 0 && intent.first() != 1) || intent.template().length() > 256) return Result.INVALID_REQUEST;
        var id = net.minecraft.resources.ResourceLocation.tryParse(intent.template());
        var holder = id == null ? null : player.getServer().getRecipeManager().byKey(id).orElse(null);
        if (holder == null || !(holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe)) return Result.UNSUPPORTED_RECIPE;
        var result = CraftingService.place(player, (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>) holder, intent.first() == 1, false, true);
        return switch (result) {
            case OK -> Result.OK; case INACTIVE -> Result.NOT_ACTIVE; case STALE -> Result.STALE; case NO_SPACE -> Result.NO_SPACE;
            case MISSING -> Result.MISSING_MATERIAL; case UNSUPPORTED -> Result.UNSUPPORTED_RECIPE; case TOO_COMPLEX -> Result.TOO_COMPLEX;
        };
    }
}
