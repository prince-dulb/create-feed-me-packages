package dev.scathiard.feedmepackages.consumption;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import java.util.*;

/** Ephemeral query objects are not inventory. Only the standard draw/useAmmo commit path may spend them. */
public final class AmmoService {
    private AmmoService() {}
    @FunctionalInterface public interface ClientQuery { ItemStack find(Player player, ItemStack weapon, ItemStack vanilla); }
    private static ClientQuery clientQuery = (player, weapon, vanilla) -> vanilla;
    public static void clientQuery(ClientQuery query) { clientQuery = Objects.requireNonNull(query); }
    // ItemStack uses identity equality; weak keys do not extend the lifetime of discarded queries.
    private static final Map<ItemStack, Ticket> TICKETS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Ticket {
        final ServerPlayer player; final MaterialTransaction plan; final ItemStack weapon, prototype;
        boolean used, committed;
        Ticket(ServerPlayer player, MaterialTransaction plan, ItemStack weapon, ItemStack prototype) {
            this.player = player; this.plan = plan; this.weapon = weapon.copy(); this.prototype = prototype.copyWithCount(1);
        }
        boolean valid(ItemStack actualWeapon, LivingEntity shooter) {
            return !used && player == shooter && ItemStack.matches(weapon, actualWeapon) && plan.valid();
        }
    }
    public static ItemStack candidate(Player player, ItemStack weapon, ItemStack vanilla) {
        if (!(weapon.getItem() instanceof ProjectileWeaponItem projectile) || player.isSpectator()) return vanilla;
        if (player.level().isClientSide) return clientQuery.find(player, weapon, vanilla);
        if (!(player instanceof ServerPlayer server)) return vanilla;
        var plan = MaterialTransaction.open(server).orElse(null);
        boolean reserved = false;
        for (int i = 0; i < server.getInventory().items.size(); i++)
            if (server.getInventory().items.get(i) == vanilla && CraftingReservations.reservedInventory(server, i) > 0) reserved = true;
        if (plan == null || (!reserved && !vanilla.isEmpty() && !server.hasInfiniteMaterials())) return vanilla;
        // A physical backpack candidate still wins over creative mode's synthetic default ammo.
        if (!reserved && !vanilla.isEmpty() && server.getInventory().contains(vanilla)) return vanilla;
        var predicate = projectile.getAllSupportedProjectiles(weapon);
        for (var prototype : plan.candidates(predicate)) {
            int available = reserved ? plan.available(prototype) : plan.cacheAvailable(prototype);
            if (available <= 0) continue;
            ItemStack candidate = prototype.copyWithCount(Math.min(prototype.getMaxStackSize(), plan.available(prototype)));
            TICKETS.put(candidate, new Ticket(server, plan, weapon, prototype)); return candidate;
        }
        return reserved ? ItemStack.EMPTY : vanilla;
    }
    /** Keep NeoForge's projectile event in its native order, including vetoes. */
    public static ItemStack afterEvent(ItemStack offered, ItemStack returned) {
        var ticket = TICKETS.get(offered);
        if (ticket == null || returned.isEmpty()) return returned;
        if (!ItemStack.matches(offered, returned)) return ItemStack.EMPTY;
        TICKETS.put(returned, ticket); return returned;
    }
    public static boolean isQuery(ItemStack ammo) { return TICKETS.containsKey(ammo); }
    public static void validate(ItemStack weapon, ItemStack ammo, LivingEntity shooter) {
        var ticket = TICKETS.get(ammo);
        if (ticket != null && (!ticket.valid(weapon, shooter) || ammo.isEmpty()
                || !ItemStack.isSameItemSameComponents(ammo, ticket.prototype))) throw new Unavailable();
    }
    /** Redirect only the native split call; all native enchantment and intangible-count decisions remain original. */
    public static ItemStack split(ItemStack ammo, int amount) {
        var ticket = TICKETS.get(ammo);
        if (ticket == null) return ammo.split(amount);
        if (ticket.used || ticket.committed || amount <= 0 || amount > ammo.getCount()
                || !ticket.plan.take(ticket.prototype, amount) || !ticket.plan.commit()) throw new Unavailable();
        ticket.committed = true;
        return ammo.split(amount);
    }
    public static void used(ItemStack weapon, ItemStack ammo, LivingEntity shooter, ItemStack result) {
        var ticket = TICKETS.get(ammo);
        if (ticket == null) return;
        if (result.isEmpty() || (!ticket.committed && !ticket.valid(weapon, shooter))) throw new Unavailable();
        ticket.used = true;
    }
    /** Internal control flow to stop a complete native draw before it can manufacture multishot copies. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("Material query is no longer valid", null, false, false); }
    }
}
