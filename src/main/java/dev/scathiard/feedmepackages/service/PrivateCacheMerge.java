package dev.scathiard.feedmepackages.service;

import dev.scathiard.feedmepackages.domain.CacheMerge;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Pure world-state proposal, NOT a manufacturing/ownership commit or an access grant. */
public final class PrivateCacheMerge {
    private PrivateCacheMerge() {}
    public enum Reason { INVALID_OWNER, INVALID_RESIDUAL, TWO_RESIDUALS }
    public static final class Rejected extends IllegalArgumentException {
        private final Reason reason;
        private Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
    public record Plan(CacheRecord source, CacheRecord target, CacheRecord replacement,
                       Map<CacheMerge.Route<ItemVariantKey>, Long> sourceRoutes) {
        public Plan { sourceRoutes = Map.copyOf(sourceRoutes); }
    }
    private record Intake(CacheState<ItemVariantKey> state, Map<ItemVariantKey, ItemStack> residuals) {
        private Intake(CacheState<ItemVariantKey> state) { this(state, Map.of()); }
    }

    public static Plan simulate(CacheLedger ledger, HolderLookup.Provider registries, UUID player, UUID sourceId) {
        Objects.requireNonNull(player);
        return simulate(ledger, registries, player, ledger.find(sourceId));
    }

    public static Plan simulate(CacheLedger ledger, HolderLookup.Provider registries, UUID player, CacheRecord source) {
        Objects.requireNonNull(player); UUID targetId = ledger.personal(player);
        var target = targetId == null ? null : ledger.find(targetId);
        if (!ledger.problem().isEmpty() || source == null || source.owner() != null
                || (targetId != null && (target == null || !player.equals(target.owner()))))
            throw new Rejected(Reason.INVALID_OWNER);
        CacheState<ItemVariantKey> state;
        Map<CacheMerge.Route<ItemVariantKey>, Long> routes;
        if (target == null) {
            state = source.state(); routes = Map.of();
        } else {
            var cells = CacheMerge.simulate(source.state(), target.state());
            state = cells.merged(); routes = cells.sourceRoutes();
        }
        Map<ItemVariantKey, ItemStack> targetResiduals = Map.of();
        if (target != null) {
            var intake = resume(ledger, registries, target, state, null);
            state = intake.state(); targetResiduals = intake.residuals();
        }
        var sourceIntake = resume(ledger, registries, source, state, target == null ? null : routes);
        state = sourceIntake.state();
        var rest = new HashMap<>(targetResiduals);
        for (var entry : sourceIntake.residuals().entrySet())
            if (rest.putIfAbsent(entry.getKey(), entry.getValue()) != null) throw new Rejected(Reason.TWO_RESIDUALS);
        // All intermediate edits are one candidate, not separately published revisions.
        long beforeRevision = target == null ? source.state().revision() : target.state().revision();
        state = new CacheState<>(state.id(), state.level(), Math.incrementExact(beforeRevision), state.cells(), state.orders());
        return new Plan(source, target, new CacheRecord(state, player, rest), routes);
    }

    /** Merge every residual owned by {@code from} into {@code destination}, one per cell by exact variant. */
    private static Intake resume(CacheLedger ledger, HolderLookup.Provider registries, CacheRecord from,
                                 CacheState<ItemVariantKey> destination, Map<CacheMerge.Route<ItemVariantKey>, Long> routes) {
        if (from.residuals().isEmpty()) return new Intake(destination);
        Map<ItemVariantKey, ItemStack> acc = new HashMap<>();
        for (var entry : List.copyOf(from.residuals().entrySet())) {
            ItemStack box = entry.getValue();
            var seal = box.get(FmpRegistries.PARCEL_SEAL.get());
            if (seal == null || !seal.arrived() || !seal.cacheId().equals(from.state().id())
                    || !ParcelAuthentication.valid(ledger, registries, box, seal))
                throw new Rejected(Reason.INVALID_RESIDUAL);
            ItemVariantKey variant = ItemVariantKey.decode(seal.variant(), registries);
            int oldSlot = from.state().find(variant), slot = destination.find(variant);
            if (oldSlot < 0 || slot < 0 || from.state().cells().get(oldSlot).filterRevision() != seal.filterRevision())
                throw new Rejected(Reason.INVALID_RESIDUAL);
            Long revision = routes == null ? seal.filterRevision() : routes.get(new CacheMerge.Route<>(variant, seal.filterRevision()));
            if (revision == null || destination.cells().get(slot).filterRevision() != revision)
                throw new Rejected(Reason.INVALID_RESIDUAL);
            var plan = ParcelIntake.simulate(destination, box, variant, seal.requestId(), true, registries);
            destination = plan.state();
            if (!plan.remainder().isEmpty()) {
                var remaining = plan.remainder();
                var newSeal = new ParcelSeal(seal.parcelId(), destination.id(), seal.playerId(), seal.requestId(), seal.variant(),
                        revision, seal.address(), true, "");
                ParcelAuthentication.apply(ledger, registries, remaining, newSeal);
                if (acc.putIfAbsent(variant, remaining) != null) throw new Rejected(Reason.TWO_RESIDUALS);
            }
        }
        return new Intake(destination, acc);
    }
}
