package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.stream.Collectors;

/** owner == null is an ordinary item cache.
 *  Residuals are actual packages, not counts, and are owned per exact filter variant (one per item cell).
 *  A cell may hold at most one residual; different cells never contend for a shared slot. */
public record CacheRecord(CacheState<ItemVariantKey> state, UUID owner, Map<ItemVariantKey, ItemStack> residuals) {
    public CacheRecord {
        residuals = copyResiduals(residuals);
    }
    @Override public Map<ItemVariantKey, ItemStack> residuals() { return copyResiduals(residuals); }
    public ItemStack residual(ItemVariantKey variant) {
        ItemStack stack = residuals.get(variant);
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }
    public boolean anyResidual() { return !residuals.isEmpty(); }
    public CacheRecord withState(CacheState<ItemVariantKey> next) { return new CacheRecord(next, owner, residuals); }

    /** Replace the residual for one cell; an empty box clears it. */
    public CacheRecord withResidual(ItemVariantKey variant, ItemStack box) {
        var next = new HashMap<>(residuals);
        if (box == null || box.isEmpty()) next.remove(variant);
        else next.put(variant, box.copy());
        return new CacheRecord(state, owner, next);
    }

    private static Map<ItemVariantKey, ItemStack> copyResiduals(Map<ItemVariantKey, ItemStack> source) {
        return source.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().copy()));
    }
}
