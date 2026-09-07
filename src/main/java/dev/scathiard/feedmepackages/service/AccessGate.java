package dev.scathiard.feedmepackages.service;

import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import java.util.*;

/** Every operation re-resolves actual functional Curios slots. Client GUI state grants no access. */
public final class AccessGate {
    private AccessGate() {}
    public enum Status { ACTIVE, NOT_WORN, DISABLED, NETWORK_CONFLICT, CACHE_CONFLICT, INVALID_IDENTITY, STORAGE_LOCKED, MULTIPLE_TERMINALS }
    public record Terminal(int slot, ItemStack stack, UUID cacheId, UUID network, boolean disabled) {}
    public record Result(Status status, CacheHandle handle, List<Terminal> terminals) {
        public Result { terminals = List.copyOf(terminals); }
        public boolean active() { return status == Status.ACTIVE; }
    }

    /** Pure equip-time check on both sides. Replacing the target slot is not a second pendant. */
    public static boolean canEquip(SlotContext context, ItemStack stack) {
        if (!context.identifier().equals("necklace") || context.cosmetic() || stack.getCount() != 1
                || !(stack.getItem() instanceof PendantItem) || stack.getOrDefault(FmpRegistries.PREVIEW.get(), false)) return false;
        var handler = CuriosApi.getCuriosInventory(context.entity()).orElse(null);
        if (handler == null) return false;
        return handler.findCurios(item -> item.getItem() instanceof PendantItem).stream().noneMatch(found ->
                !found.slotContext().cosmetic() && !(found.slotContext().identifier().equals(context.identifier())
                        && found.slotContext().index() == context.index()));
    }

    public static Result resolve(ServerPlayer player) {
        if (!player.getServer().isSameThread()) throw new IllegalStateException("Cache access outside server thread");
        if (!player.isAlive() || player.isSpectator()) return new Result(Status.NOT_WORN, null, List.of());
        var handler = CuriosApi.getCuriosInventory(player).orElse(null);
        if (handler == null) return new Result(Status.NOT_WORN, null, List.of());
        var found = handler.findCurios(item -> item.getItem() instanceof PendantItem).stream()
                .filter(result -> !result.slotContext().cosmetic()).toList();
        List<Terminal> terminals = new ArrayList<>();
        for (var entry : found) terminals.add(new Terminal(entry.slotContext().index(), entry.stack(),
                entry.stack().get(FmpRegistries.IDENTITY.get()), entry.stack().get(FmpRegistries.NETWORK.get()), false));
        if (terminals.isEmpty()) return new Result(Status.NOT_WORN, null, terminals);
        // Count before creating any cache or claiming any entitlement, including abnormal same-ID copies.
        if (terminals.size() > 1) return new Result(Status.MULTIPLE_TERMINALS, null, terminals);
        var entry = found.getFirst(); var context = entry.slotContext(); var stack = entry.stack();
        if (!context.identifier().equals("necklace") || !handler.isSlotActive("necklace", context.index()))
            return new Result(Status.NOT_WORN, null, terminals);
        if (stack.getCount() != 1 || stack.getOrDefault(FmpRegistries.PREVIEW.get(), false))
            return new Result(Status.INVALID_IDENTITY, null, terminals);
        var ledger = CacheLedger.get(player.getServer());
        if (!ledger.problem().isEmpty()) return new Result(Status.STORAGE_LOCKED, null, terminals);
        UUID cacheId;
        if (stack.getItem() instanceof PendantItem pendant && pendant.personal()) {
            UUID owner = stack.get(FmpRegistries.OWNER.get());
            if (owner == null || !owner.equals(player.getUUID())) {
                // A personal pendant is a key belonging to its crafter. A non-owner (or a legacy pendant with an
                // unknown owner) cannot touch the owner's cache: it degrades here to a fresh ordinary pendant.
                UUID id = ledger.createOrdinary();
                ItemStack ordinary = new ItemStack(FmpRegistries.PENDANT.get());
                ordinary.set(FmpRegistries.IDENTITY.get(), id);
                UUID network = stack.get(FmpRegistries.NETWORK.get()); if (network != null) ordinary.set(FmpRegistries.NETWORK.get(), network);
                handler.getStacksHandler("necklace").ifPresent(stacks -> stacks.getStacks().setStackInSlot(context.index(), ordinary));
                return resolve(player);
            }
            cacheId = ledger.personalOrCreate(player.getUUID());
        } else {
            cacheId = stack.get(FmpRegistries.IDENTITY.get());
            if (cacheId == null) { cacheId = ledger.createOrdinary(); stack.set(FmpRegistries.IDENTITY.get(), cacheId); }
            var record = ledger.find(cacheId);
            if (record == null || record.owner() != null) return new Result(Status.INVALID_IDENTITY, null, terminals);
        }
        UUID network = stack.get(FmpRegistries.NETWORK.get());
        return new Result(Status.ACTIVE, new CacheHandle(cacheId, player.getUUID(), network),
                List.of(new Terminal(context.index(), stack, cacheId, network, false)));
    }
}
