package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import java.util.UUID;

/** Read-only contents calculation. Callers must authenticate the route before using this plan. */
public final class ParcelIntake {
    private ParcelIntake() {}
    public record Plan(CacheState<ItemVariantKey> state, ItemStack remainder, int moved) {
        public Plan { remainder = remainder.copy(); }
        @Override public ItemStack remainder() { return remainder.copy(); }
    }

    public static Plan simulate(CacheState<ItemVariantKey> state, ItemStack box, ItemVariantKey requested,
                                UUID requestId, boolean alreadyArrived, HolderLookup.Provider registries) {
        if (box.getCount() != 1 || !PackageItem.isPackage(box)
                || box.getOrDefault(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.EMPTY).getSlots() > 9)
            throw new IllegalArgumentException("Invalid native package shape");
        var contents = PackageItem.getContents(box); var edit = state.edit();
        NonNullList<ItemStack> remainder = NonNullList.withSize(contents.getSlots(), ItemStack.EMPTY);
        int arrived = 0, moved = 0; boolean anyRemainder = false;
        for (int index = 0; index < contents.getSlots(); index++) {
            ItemStack item = contents.getStackInSlot(index).copy();
            if (item.isEmpty()) continue;
            if (item.getCount() > item.getMaxStackSize()) throw new IllegalArgumentException("Illegal package stack count");
            ItemVariantKey key;
            try { key = ItemVariantKey.of(item, registries); }
            catch (IllegalArgumentException unsupported) { remainder.set(index, item); anyRemainder = true; continue; }
            if (key.equals(requested)) arrived = Math.addExact(arrived, item.getCount());
            int slot = state.find(key);
            if (slot >= 0) {
                int accepted = edit.insert(slot, key, item.getCount());
                item.shrink(accepted); moved = Math.addExact(moved, accepted);
            }
            if (!item.isEmpty()) { remainder.set(index, item); anyRemainder = true; }
        }
        if (!alreadyArrived) edit.arrived(requestId, arrived);
        ItemStack rest = ItemStack.EMPTY;
        if (anyRemainder) {
            rest = box.copy(); rest.set(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.fromItems(remainder));
            // This is a candidate only. Never leave a stale signature on changed contents.
            rest.remove(FmpRegistries.PARCEL_SEAL.get());
        }
        return new Plan(edit.finish(), rest, moved);
    }
}
