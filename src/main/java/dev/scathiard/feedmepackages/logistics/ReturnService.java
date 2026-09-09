package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.compat.TransportDispatch;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.domain.CacheEdit;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.CacheRecord;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Return surplus in legal package-sized batches, with one coherent cache debit. */
public final class ReturnService {
    private static final Item CARD = BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:cardboard_plane_parts"));
    private static final Item BEE = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"));
    private static final int PACKAGE_SLOTS = 9;
    private record Carrier(int inventorySlot, int cacheSlot) {}
    public static final int RETURN_UNKNOWN = 0;
    public static final int RETURN_FAILED = 1;
    public static final int RETURN_SENT = 2;

    private ReturnService() {}

    public static void clearTransient() {
        ReturnDispatchState.clear();
    }

    public static void forget(ServerPlayer player) {
        ReturnDispatchState.forget(player.getUUID());
    }

    public static int dispatchState(CacheHandle handle, String address, int slot, String filter, int maximum) {
        return ReturnDispatchState.state(handle, address, slot, filter, maximum);
    }

    public static void tick(ServerPlayer player) {
        if (player.tickCount % 40 == 0) check(player);
    }

    public static void check(ServerPlayer player) {
        AccessGate.Result access = AccessGate.resolve(player);
        if (!access.active()) return;
        CacheHandle handle = access.handle();
        if (handle.networkId() == null || CraftingReservations.operating(handle.cacheId())) {
            ReturnDispatchState.reset(handle);
            return;
        }
        CacheLedger ledger = CacheLedger.get(player.getServer());
        String address = ledger.returnAddress(handle.cacheId());
        CacheRecord record = ledger.find(handle.cacheId());
        if (record == null) {
            ReturnDispatchState.reset(handle);
            return;
        }
        int slots = record.state().cells().size();
        ReturnDispatchState.ensureContext(handle, address, slots);
        for (int slot = 0; slot < slots; slot++) {
            AccessGate.Result current = AccessGate.resolve(player);
            if (!current.active() || !current.handle().equals(handle)) return;
            CacheRecord live = ledger.find(handle.cacheId());
            if (live == null || slot >= live.state().cells().size()) {
                ReturnDispatchState.reset(handle);
                return;
            }
            Cell<ItemVariantKey> cell = live.state().cells().get(slot);
            String filter = cell.filter() == null ? "" : cell.filter().encoded();
            if (cell.filter() == null || cell.maximum() < 0) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.maximum(), RETURN_UNKNOWN);
                continue;
            }
            int overage = Math.max(0, cell.amount() - cell.maximum() * cell.filter().stackSize());
            if (overage == 0) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.maximum(), RETURN_UNKNOWN);
                continue;
            }
            if (address == null || address.isEmpty()) {
                ReturnDispatchState.rememberCell(handle, slot, filter, cell.maximum(), RETURN_FAILED);
                continue;
            }
            // Preserve plane-first preference. A rejected attempt changes no FMP stock
            // and the bee receives its own newly built package, never the plane's object.
            int result = dispatch(player, handle, ledger, slot, CARD, address);
            if (result == RETURN_FAILED) result = dispatch(player, handle, ledger, slot, BEE, address);
            ReturnDispatchState.rememberCell(handle, slot, filter, cell.maximum(), result);
        }
    }

    private static int dispatch(ServerPlayer player, CacheHandle handle, CacheLedger ledger,
            int slot, Item carrierItem, String address) {
        if (carrierItem == Items.AIR) return RETURN_FAILED;
        CacheRecord record = ledger.find(handle.cacheId());
        if (record == null || slot >= record.state().cells().size()) return RETURN_FAILED;
        CacheState<ItemVariantKey> state = record.state();
        Cell<ItemVariantKey> cell = state.cells().get(slot);
        if (cell.filter() == null || cell.maximum() < 0) return RETURN_UNKNOWN;
        int overage = Math.max(0, cell.amount() - cell.maximum() * cell.filter().stackSize());
        if (overage == 0) return RETURN_UNKNOWN;
        Carrier carrier = findCarrier(player, handle, state, carrierItem);
        if (carrier == null) return RETURN_FAILED;

        int available = cell.amount() - CraftingReservations.reservedCache(handle.cacheId(), slot, null);
        if (carrier.cacheSlot() == slot) available--; // A carrier cannot also be its own cargo.
        int amount = Math.min(Math.min(overage, available), PACKAGE_SLOTS * cell.filter().stackSize());
        if (amount <= 0) return RETURN_FAILED;
        ItemStack box = buildBox(player, cell.filter(), amount, address);
        if (box == null) return RETURN_FAILED;

        // Prepare both cache debits together, without publishing any change yet.
        CacheEdit<ItemVariantKey> edit = state.edit();
        if (edit.extract(slot, amount) != amount) return RETURN_FAILED;
        if (carrier.cacheSlot() >= 0 && edit.extract(carrier.cacheSlot(), 1) != 1) return RETURN_FAILED;
        CacheRecord after = record.withState(edit.finish());

        boolean sent = carrierItem == CARD
                ? TransportDispatch.paperPlane(player.serverLevel(), box.copy(), player.position()).dispatched()
                : TransportDispatch.bee(player.serverLevel(), box.copy(), player.blockPosition(), handle.networkId()).dispatched();
        if (!sent) return RETURN_FAILED;

        // A single revision advance removes cargo AND any carrier held in the cache.
        // The former consumeCarrier() committed separately and invalidated this revision.
        ledger.replace(handle, state.revision(), after);
        if (carrier.inventorySlot() >= 0) {
            player.getInventory().getItem(carrier.inventorySlot()).shrink(1);
            player.getInventory().setChanged();
        }
        return RETURN_SENT;
    }

    private static ItemStack buildBox(ServerPlayer player, ItemVariantKey variant, int amount, String address) {
        try {
            List<ItemStack> contents = new ArrayList<>();
            for (int remaining = amount; remaining > 0;) {
                int count = Math.min(remaining, variant.stackSize());
                contents.add(variant.stack(player.registryAccess(), count));
                remaining -= count;
            }
            if (contents.isEmpty() || contents.size() > PACKAGE_SLOTS) return null;
            ItemStack box = PackageItem.containing(contents);
            PackageItem.addAddress(box, address);
            return box;
        } catch (IllegalArgumentException invalid) {
            FeedMePackages.LOGGER.warn("FMP return package could not be built for " + variant + " x" + amount, invalid);
            return null;
        }
    }

    private static Carrier findCarrier(ServerPlayer player, CacheHandle handle,
            CacheState<ItemVariantKey> state, Item item) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)
                    && stack.getCount() > CraftingReservations.reservedInventory(player, i))
                return new Carrier(i, -1);
        }
        try {
            ItemVariantKey key = ItemVariantKey.of(new ItemStack(item), player.registryAccess());
            int slot = state.find(key);
            if (slot >= 0 && state.cells().get(slot).amount()
                    > CraftingReservations.reservedCache(handle.cacheId(), slot, null))
                return new Carrier(-1, slot);
        } catch (IllegalArgumentException unsupportedCarrier) {
            // An optional carrier may prohibit container storage; inventory use still works.
        }
        return null;
    }
}
