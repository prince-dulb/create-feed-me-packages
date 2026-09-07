package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.compat.TransportDispatch;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** Automatic real returns: when a cell's stock exceeds its maximum, package the excess and dispatch it to the
 *  per-cache default return address. Nothing is deducted until a carrier is actually accepted (received true). */
public final class ReturnService {
    private ReturnService() {}

    private static final Item CARD = BuiltInRegistries.ITEM.get(ResourceLocation.parse("cmpackagecouriers:cardboard_plane_parts"));
    private static final Item BEE = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create_mobile_packages:robo_bee"));

    public static void tick(ServerPlayer player) {
        if (player.tickCount % 40 != 0) return;
        check(player);
    }

    /** Dispatch any overage to the return address. No deduction unless a carrier is actually accepted. */
    public static void check(ServerPlayer player) {
        var access = AccessGate.resolve(player);
        if (!access.active() || access.handle().networkId() == null) return;
        if (CraftingReservations.operating(access.handle().cacheId())) return;
        var ledger = CacheLedger.get(player.getServer());
        String address = ledger.returnAddress(access.handle().cacheId());
        if (address == null || address.isEmpty()) return;
        CacheHandle initial = access.handle();
        var record = ledger.find(initial.cacheId()); var state = record.state();
        for (int slot = 0; slot < state.cells().size(); slot++) {
            var cell = state.cells().get(slot);
            if (cell.filter() == null || cell.maximum() < 0) continue;
            int overage = Math.max(0, cell.amount() - cell.maximum() * cell.filter().stackSize());
            if (overage == 0) continue;
            var now = AccessGate.resolve(player);
            if (!now.active() || !now.handle().equals(initial)) return;
            record = ledger.find(initial.cacheId()); state = record.state();
            if (dispatch(player, initial, cell.filter(), overage, address)) {
                var edit = state.edit(); edit.extract(slot, overage);
                ledger.replace(initial, state.revision(), record.withState(edit.finish()));
            }
        }
    }

    private static boolean dispatch(ServerPlayer player, CacheHandle handle, ItemVariantKey variant, int overage, String address) {
        ServerLevel level = player.serverLevel();
        ItemStack box = buildBox(player, variant, overage, address);
        if (box == null) return false;
        if (CARD != net.minecraft.world.item.Items.AIR && haveCarrier(player, handle, CARD)) {
            if (TransportDispatch.paperPlane(level, box, player.position()).dispatched()) { consumeCarrier(player, handle, CARD); return true; }
        }
        if (BEE != net.minecraft.world.item.Items.AIR && haveCarrier(player, handle, BEE)) {
            if (TransportDispatch.bee(level, box, player.blockPosition()).dispatched()) { consumeCarrier(player, handle, BEE); return true; }
        }
        return false;
    }

    private static ItemStack buildBox(ServerPlayer player, ItemVariantKey variant, int overage, String address) {
        try {
            var box = PackageItem.containing(List.of(variant.stack(player.registryAccess(), overage)));
            PackageItem.addAddress(box, address);
            return box;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static boolean haveCarrier(ServerPlayer player, CacheHandle handle, Item carrier) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (player.getInventory().getItem(i).is(carrier)) return true;
        var ledger = CacheLedger.get(player.getServer()); var state = ledger.find(handle.cacheId()).state();
        var key = ItemVariantKey.of(new ItemStack(carrier), player.registryAccess());
        int slot = state.find(key);
        return slot >= 0 && state.cells().get(slot).amount() >= 1;
    }

    private static void consumeCarrier(ServerPlayer player, CacheHandle handle, Item carrier) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.is(carrier)) { stack.shrink(1); player.getInventory().setChanged(); return; }
        }
        var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId()); var state = record.state();
        var key = ItemVariantKey.of(new ItemStack(carrier), player.registryAccess());
        int slot = state.find(key);
        if (slot >= 0 && state.cells().get(slot).amount() >= 1) {
            var edit = state.edit(); edit.extract(slot, 1);
            ledger.replace(handle, state.revision(), record.withState(edit.finish()));
        }
    }
}
