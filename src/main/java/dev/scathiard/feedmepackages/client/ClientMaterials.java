package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.AmmoService;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.MaterialHints;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.*;

/** Animation/recipe hints only. These stacks never enter a player's real inventory. */
public final class ClientMaterials {
    private ClientMaterials() {}
    private static UUID generation;
    private static long serial;
    private static boolean active, first;
    private static List<ItemStack> prototypes = List.of();
    private static List<Integer> counts = List.of();
    private static List<Integer> ownReservations = List.of(), reservedGrid = List.of();
    private static int menu;
    public static void register() {
        MaterialHints.receiveOnClient(ClientMaterials::accept); AmmoService.clientQuery(ClientMaterials::ammo);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }
    public static void clear() { generation = null; serial = 0; active = false; first = false; prototypes = List.of(); counts = List.of(); ownReservations = List.of(); reservedGrid = List.of(); }
    public static boolean active() { return active && Minecraft.getInstance().player != null && !Minecraft.getInstance().player.isSpectator(); }
    public static boolean cacheFirst() { return false; }
    public static long version() { return serial; }
    public static List<ItemStack> stacks() {
        return stacks(false);
    }
    public static List<ItemStack> craftingStacks() { return stacks(true); }
    private static List<ItemStack> stacks(boolean preparing) {
        if (!active()) return List.of();
        var result = new ArrayList<ItemStack>();
        for (int i = 0; i < counts.size(); i++) {
            int count = counts.get(i) + (preparing ? ownReservations.get(i) : 0);
            if (count > 0) result.add(prototypes.get(i).copyWithCount(count));
        }
        return result; // Counts are hints, not legal transferable stacks.
    }
    public static List<ItemStack> realGrid(Player player, net.minecraft.world.inventory.CraftingContainer grid) {
        var result = dev.scathiard.feedmepackages.consumption.MaterialTransaction.copies(grid.getItems());
        if (player.containerMenu.containerId == menu && result.size() == reservedGrid.size())
            for (int i = 0; i < result.size(); i++) result.get(i).shrink(Math.min(result.get(i).getCount(), reservedGrid.get(i)));
        return result;
    }
    private static void accept(MaterialHints.Message packet) {
        var player = Minecraft.getInstance().player;
        if (player == null || (packet.generation().equals(generation) && packet.serial() <= serial)
                || (!packet.full() && !packet.generation().equals(generation))) return;
        try {
            if (packet.full()) {
                var decoded = new ArrayList<ItemStack>();
                for (var template : packet.templates()) decoded.add(template.isEmpty() ? ItemStack.EMPTY : ItemVariantKey.decode(template, player.registryAccess()).stack(player.registryAccess(), 1));
                prototypes = decoded; generation = packet.generation();
            }
            if (prototypes.size() != packet.amounts().size()) throw new IllegalArgumentException("Mismatched material delta");
            for (int i = 0; i < prototypes.size(); i++) if (prototypes.get(i).isEmpty() && packet.amounts().get(i) > 0) throw new IllegalArgumentException("Empty material with positive count");
            counts = packet.amounts(); first = false; active = packet.active(); serial = packet.serial();
            ownReservations = packet.ownReservations(); reservedGrid = packet.reservedGrid(); menu = packet.menu();
        } catch (IllegalArgumentException invalid) { clear(); FeedMePackages.LOGGER.warn("Rejected invalid material hints: {}", invalid.getMessage()); }
    }
    private static ItemStack ammo(Player player, ItemStack weapon, ItemStack vanilla) {
        if (!active() || (!first && !vanilla.isEmpty() && !player.hasInfiniteMaterials())) return vanilla;
        if (!first && !vanilla.isEmpty() && player.getInventory().contains(vanilla)) return vanilla;
        var predicate = ((ProjectileWeaponItem) weapon.getItem()).getAllSupportedProjectiles(weapon);
        for (var stack : stacks()) if (predicate.test(stack)) return stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
        return vanilla;
    }
}
