package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Limited to one synchronous Create dispatch. It never changes ordinary Create packages. */
public final class DispatchScope implements AutoCloseable {
    private static final ThreadLocal<DispatchScope> CURRENT = new ThreadLocal<>();
    private final CacheLedger ledger;
    private final HolderLookup.Provider registries;
    private final ParcelSeal route;
    public DispatchScope(CacheLedger ledger, HolderLookup.Provider registries, ParcelSeal route) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested FMP dispatch");
        this.ledger = ledger; this.registries = registries; this.route = route; CURRENT.set(this);
    }
    public static void onCreated(ItemStack box) {
        DispatchScope scope = CURRENT.get(); if (scope == null) return;
        ParcelSeal seal = new ParcelSeal(UUID.randomUUID(), scope.route.cacheId(), scope.route.playerId(), scope.route.requestId(),
                scope.route.variant(), scope.route.filterRevision(), scope.route.address(), false, "");
        ParcelAuthentication.apply(scope.ledger, scope.registries, box, seal);
    }
    @Override public void close() { if (CURRENT.get() != this) throw new IllegalStateException("Dispatch scope mismatch"); CURRENT.remove(); }
}
