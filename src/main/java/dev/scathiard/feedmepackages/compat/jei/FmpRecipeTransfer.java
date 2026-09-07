package dev.scathiard.feedmepackages.compat.jei;

import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.ClientCrafting;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Public JEI extension. Unworn users delegate to JEI's own unregistered basic handler. */
final class FmpRecipeTransfer<C extends AbstractContainerMenu> implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {
    private final Class<C> type;
    private final MenuType<C> menuType;
    private final int width;
    private final IRecipeTransferHandlerHelper helper;
    private final IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> fallback;
    FmpRecipeTransfer(Class<C> type, MenuType<C> menuType, int width, IRecipeTransferHandlerHelper helper) {
        this.type = type; this.menuType = menuType; this.width = width; this.helper = helper;
        var info = helper.createBasicRecipeTransferInfo(type, menuType, RecipeTypes.CRAFTING, 1, width * width, width == 2 ? 9 : 10, 36);
        fallback = helper.createUnregisteredRecipeTransferHandler(info);
    }
    @Override public Class<C> getContainerClass() { return type; }
    @Override public Optional<MenuType<C>> getMenuType() { return Optional.ofNullable(menuType); }
    @Override public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() { return RecipeTypes.CRAFTING; }
    @Override public IRecipeTransferError transferRecipe(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean perform) {
        if (!ClientMaterials.active()) return nativeTransfer(menu, recipe, slots, player, maximum, perform);
        if (!LogisticsPanel.recipeReady()) return error("not_active");
        var checked = ClientCrafting.check(player, recipe, maximum, true);
        if (checked != CraftingService.Result.OK) return error(switch (checked) {
            case UNSUPPORTED -> "unsupported_recipe"; case TOO_COMPLEX -> "too_complex"; case NO_SPACE -> "no_space"; default -> "missing_material";
        });
        if (perform && !LogisticsPanel.fillRecipe(recipe.id(), maximum)) return error("stale");
        return null;
    }
    private IRecipeTransferError nativeTransfer(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean perform) {
        if (width == 3) return fallback.transferRecipe(menu, recipe, slots, player, maximum, perform);
        var input = slots.getSlotViews(RecipeIngredientRole.INPUT);
        var mapped = new ArrayList<mezz.jei.api.gui.ingredient.IRecipeSlotView>();
        for (int index = 0; index < input.size(); index++) {
            if (index % 3 < 2 && index / 3 < 2) mapped.add(input.get(index));
            else if (!input.get(index).isEmpty()) return helper.createUserErrorWithTooltip(Component.translatable("jei.tooltip.error.recipe.transfer.too.large.player.inventory"));
        }
        return fallback.transferRecipe(menu, recipe, helper.createRecipeSlotsView(mapped), player, maximum, perform);
    }
    private IRecipeTransferError error(String key) { return helper.createUserErrorWithTooltip(Component.translatable("gui.create_feed_me_packages.result." + key)); }
}
