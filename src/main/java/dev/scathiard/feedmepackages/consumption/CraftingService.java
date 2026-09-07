package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.mixin.CraftingItemsAccess;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameRules;
import java.util.*;

/** Backed reservations in the native grid; the native result and remainder path still executes. */
public final class CraftingService {
    private CraftingService() {}
    public enum Result { OK, INACTIVE, UNSUPPORTED, MISSING, NO_SPACE, STALE, TOO_COMPLEX }
    public static boolean supported(AbstractContainerMenu menu) {
        return (menu.getClass() == InventoryMenu.class || menu.getClass() == CraftingMenu.class)
                && menu.getSlot(1).container.getClass() == TransientCraftingContainer.class;
    }
    public static TransientCraftingContainer grid(AbstractContainerMenu menu) { return (TransientCraftingContainer) menu.getSlot(1).container; }
    public static final class Plan {
        private final ServerPlayer player; private final AbstractContainerMenu menu; private final TransientCraftingContainer grid;
        private final MaterialTransaction materials; private final List<ItemStack> before, after;
        private Plan(ServerPlayer player, MaterialTransaction materials, List<ItemStack> before, List<ItemStack> after) {
            this.player = player; this.menu = player.containerMenu; this.grid = grid(menu); this.materials = materials;
            this.before = MaterialTransaction.copies(before); this.after = MaterialTransaction.copies(after);
        }
        public boolean commit() {
            if (player.containerMenu != menu || !menu.stillValid(player) || !MaterialTransaction.matches(before, grid.getItems()) || !materials.valid()) return false;
            var raw = ((CraftingItemsAccess) grid).fmp$items();
            if (raw.size() != after.size() || !materials.commitPreparation(menu, after)) return false;
            for (int i = 0; i < raw.size(); i++) raw.set(i, after.get(i).copy());
            menu.slotsChanged(grid); menu.broadcastChanges(); return true;
        }
    }
    public record Simulation(Result result, Plan plan) {}
    public static Simulation simulate(ServerPlayer player, RecipeHolder<CraftingRecipe> recipe, boolean maximum, boolean recipeBook) {
        var menu = player.containerMenu;
        if (!supported(menu) || !menu.stillValid(player)) return new Simulation(Result.UNSUPPORTED, null);
        CraftingReservations.validate(player);
        var materials = MaterialTransaction.prepare(player).orElse(null);
        if (materials == null) return new Simulation(Result.INACTIVE, null);
        if ((recipeBook || player.level().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)) && !player.getRecipeBook().contains(recipe)) return new Simulation(Result.UNSUPPORTED, null);
        var grid = grid(menu); var before = MaterialTransaction.copies(grid.getItems());
        var real = CraftingReservations.realGrid(player, menu); var sources = MaterialTransaction.copies(real);
        for (var candidate : materials.candidates(s -> true)) sources.add(candidate.copyWithCount(materials.available(candidate)));
        int requested = 1;
        if (!maximum && recipe.value().matches(grid.asCraftInput(), player.level()))
            requested = before.stream().filter(s -> !s.isEmpty()).mapToInt(ItemStack::getCount).min().orElse(0) + 1;
        var choice = CraftingPlanner.solve(recipe, grid.getWidth(), grid.getHeight(), grid.getMaxStackSize(), requested, maximum, sources, player.level());
        if (choice.error() != CraftingPlanner.Error.NONE) return new Simulation(switch (choice.error()) {
            case UNSUPPORTED -> Result.UNSUPPORTED; case MISSING -> Result.MISSING; case TOO_COMPLEX -> Result.TOO_COMPLEX; default -> throw new IllegalStateException();
        }, null);
        var remaining = MaterialTransaction.copies(real); var after = choice.grid();
        for (int slot = 0; slot < after.size(); slot++) {
            var target = after.get(slot); if (target.isEmpty()) continue;
            int needed = target.getCount();
            for (var old : remaining) if (ItemStack.isSameItemSameComponents(old, target)) {
                int reuse = Math.min(needed, old.getCount()); old.shrink(reuse); needed -= reuse;
            }
            if (!materials.takeForGrid(target, needed, slot)) return new Simulation(Result.STALE, null);
        }
        for (var old : remaining) if (!materials.returnToInventory(old)) return new Simulation(Result.NO_SPACE, null);
        return new Simulation(Result.OK, new Plan(player, materials, before, after));
    }
    public static Result place(ServerPlayer player, RecipeHolder<CraftingRecipe> recipe, boolean maximum, boolean recipeBook, boolean commit) {
        var simulation = simulate(player, recipe, maximum, recipeBook);
        if (simulation.result != Result.OK || !commit) return simulation.result;
        return simulation.plan.commit() ? Result.OK : Result.STALE;
    }
    @SuppressWarnings("unchecked")
    public static boolean fromRecipeBook(ServerPlayer player, RecipeHolder<?> recipe, boolean maximum) {
        if (!supported(player.containerMenu) || !AccessGate.resolve(player).active() || !(recipe.value() instanceof CraftingRecipe)) return false;
        var result = place(player, (RecipeHolder<CraftingRecipe>)recipe, maximum, true, true);
        if (result == Result.MISSING) player.connection.send(new ClientboundPlaceGhostRecipePacket(player.containerMenu.containerId, recipe));
        return true;
    }
    public record Refill(List<ItemStack> before, RecipeHolder<CraftingRecipe> recipe, ItemStack expectedOutput, AbstractContainerMenu menu) {}
    public static Refill beforeCraft(ServerPlayer player) {
        if (!supported(player.containerMenu) || !AccessGate.resolve(player).active()) return null;
        var grid = grid(player.containerMenu); var input = grid.asCraftInput();
        var recipe = player.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, player.level()).orElse(null);
        return recipe == null ? null : new Refill(MaterialTransaction.copies(grid.getItems()), recipe,
                recipe.value().assemble(input, player.registryAccess()).copy(), player.containerMenu);
    }
    private static final ThreadLocal<Integer> CRAFTS = new ThreadLocal<>();
    public static Integer beginClick() { var old = CRAFTS.get(); CRAFTS.set(0); return old; }
    public static void endClick(Integer prior) { if (prior == null) CRAFTS.remove(); else CRAFTS.set(prior); }
    public static void afterCraft(ServerPlayer player, Refill refill) {
        if (refill == null || player.containerMenu != refill.menu || !supported(player.containerMenu) || !CraftingReservations.operating(player)) return;
        CraftingReservations.reconcile(player);
        int count = CRAFTS.get() == null ? 1 : CRAFTS.get() + 1;
        if (CRAFTS.get() != null) CRAFTS.set(count);
        if (count >= 64) return; // A native input stack's worth per click; no unbounded automatic shift loop.
        var materials = MaterialTransaction.open(player).orElse(null); if (materials == null) return;
        var grid = grid(player.containerMenu); var current = MaterialTransaction.copies(grid.getItems()); var after = MaterialTransaction.copies(current);
        boolean changed = false;
        for (int i = 0; i < refill.before.size(); i++) {
            var old = refill.before.get(i); var now = current.get(i);
            if (old.isEmpty()) { if (!now.isEmpty()) return; continue; }
            if (!now.isEmpty()) { if (!ItemStack.isSameItemSameComponents(old, now) || now.getCount() != old.getCount() - 1) return; continue; }
            if (old.getCount() != 1 || !materials.takeForGrid(old, 1, i)) return;
            after.set(i, old.copyWithCount(1)); changed = true;
        }
        if (!changed) return;
        var input = CraftingInput.of(grid.getWidth(), grid.getHeight(), after);
        if (!refill.recipe.value().matches(input, player.level()) || !ItemStack.matches(refill.expectedOutput, refill.recipe.value().assemble(input, player.registryAccess()))) return;
        if (!materials.valid() || !MaterialTransaction.matches(current, grid.getItems()) || !materials.commit()) return;
        var raw = ((CraftingItemsAccess)grid).fmp$items();
        for (int i = 0; i < raw.size(); i++) raw.set(i, after.get(i).copy());
        CraftingReservations.appendDebits(player, materials.allocations());
        player.containerMenu.slotsChanged(grid); player.containerMenu.broadcastChanges();
    }
}
