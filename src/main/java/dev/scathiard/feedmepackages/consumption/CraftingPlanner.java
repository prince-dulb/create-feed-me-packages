package dev.scathiard.feedmepackages.consumption;

import net.minecraft.recipebook.PlaceRecipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import java.util.*;

/** Bounded exact-component assignment. Item IDs and client-selected outputs never prove a recipe. */
public final class CraftingPlanner {
    private CraftingPlanner() {}
    public enum Error { NONE, UNSUPPORTED, MISSING, TOO_COMPLEX }
    public record Choice(Error error, List<ItemStack> grid, int sets) {
        public Choice { grid = MaterialTransaction.copies(grid); }
        @Override public List<ItemStack> grid() { return MaterialTransaction.copies(grid); }
    }
    private record Demand(int slot, Ingredient ingredient) {}
    private static final int MAX_NODES = 32768;
    public static Choice solve(RecipeHolder<CraftingRecipe> recipe, int width, int height, int limit,
                               int requested, boolean maximum, List<ItemStack> sources, Level level) {
        if (width < 1 || height < 1 || width * height > 9 || requested < 1 || limit < 1 || limit > 99
                || !recipe.value().canCraftInDimensions(width, height) || recipe.value().getIngredients().isEmpty()
                || recipe.value().getIngredients().size() > 9) return failed(Error.UNSUPPORTED);
        List<Demand> demands = new ArrayList<>();
        PlaceRecipe<Ingredient> layout = (ingredient, slot, count, x, y) -> {
            if (!ingredient.isEmpty()) demands.add(new Demand(slot - 1, ingredient));
        };
        layout.placeRecipe(width, height, 0, recipe, recipe.value().getIngredients().iterator(), 1);
        if (demands.isEmpty() || demands.stream().anyMatch(d -> d.slot < 0 || d.slot >= width * height)) return failed(Error.UNSUPPORTED);
        var groups = new ArrayList<ItemStack>(); var counts = new ArrayList<Integer>();
        for (var source : sources) {
            if (source.isEmpty()) continue;
            int index = -1;
            for (int i = 0; i < groups.size(); i++) if (ItemStack.isSameItemSameComponents(groups.get(i), source)) { index = i; break; }
            if (index < 0) { groups.add(source.copyWithCount(1)); counts.add(source.getCount()); }
            else counts.set(index, Math.addExact(counts.get(index), source.getCount()));
        }
        int[] budget = {MAX_NODES};
        for (int sets = maximum ? limit : requested; sets >= (maximum ? 1 : requested); sets--) {
            List<int[]> eligible = new ArrayList<>(); boolean missing = false;
            for (var demand : demands) {
                var candidates = new ArrayList<Integer>();
                for (int i = 0; i < groups.size(); i++) {
                    var item = groups.get(i);
                    if (counts.get(i) >= sets && sets <= item.getMaxStackSize() && sets <= limit && demand.ingredient.test(item.copyWithCount(sets))) candidates.add(i);
                }
                if (candidates.isEmpty()) { missing = true; break; }
                eligible.add(candidates.stream().mapToInt(Integer::intValue).toArray());
            }
            if (missing) continue;
            var order = new ArrayList<Integer>(); for (int i = 0; i < demands.size(); i++) order.add(i);
            order.sort(Comparator.comparingInt(i -> eligible.get(i).length));
            var grid = new ArrayList<ItemStack>(Collections.nCopies(width * height, ItemStack.EMPTY));
            if (assign(0, order, demands, eligible, groups, counts.stream().mapToInt(Integer::intValue).toArray(), grid,
                    sets, recipe, width, height, level, budget)) return new Choice(Error.NONE, grid, sets);
            if (budget[0] <= 0) return failed(Error.TOO_COMPLEX);
        }
        return failed(Error.MISSING);
    }
    private static boolean assign(int depth, List<Integer> order, List<Demand> demands, List<int[]> eligible,
                                  List<ItemStack> groups, int[] left, List<ItemStack> grid, int sets,
                                  RecipeHolder<CraftingRecipe> recipe, int width, int height, Level level, int[] budget) {
        if (--budget[0] < 0) return false;
        if (depth == order.size()) {
            var input = CraftingInput.of(width, height, grid);
            if (!recipe.value().matches(input, level)) return false;
            var result = recipe.value().assemble(input, level.registryAccess());
            return !result.isEmpty() && result.getCount() <= result.getMaxStackSize() && result.isItemEnabled(level.enabledFeatures());
        }
        int demand = order.get(depth), slot = demands.get(demand).slot;
        for (int group : eligible.get(demand)) if (left[group] >= sets) {
            left[group] -= sets; grid.set(slot, groups.get(group).copyWithCount(sets));
            if (assign(depth + 1, order, demands, eligible, groups, left, grid, sets, recipe, width, height, level, budget)) return true;
            left[group] += sets; grid.set(slot, ItemStack.EMPTY);
            if (budget[0] <= 0) return false;
        }
        return false;
    }
    private static Choice failed(Error error) { return new Choice(error, List.of(), 0); }
}
