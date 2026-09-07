package dev.scathiard.feedmepackages.compat.jei;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.growth.PendantSmithingRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import mezz.jei.api.recipe.category.extensions.vanilla.smithing.ISmithingCategoryExtension;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import java.util.*;

/** JEI discovers this isolated plugin. No core/bootstrap class links to its optional API. */
@JeiPlugin
public final class FmpJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;
    @Override public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "jei"); }
    @Override public void onRuntimeAvailable(IJeiRuntime value) {
        runtime = value; LogisticsPanel.recipeOverlay(candidate -> candidate == runtime.getRecipesGui());
        // JEI's bookmark/history buttons are outside Screen.children and ignore ingredient exclusion areas.
        LogisticsPanel.overlayBottomInset(28);
    }
    @Override public void onRuntimeUnavailable() {
        runtime = null; LogisticsPanel.recipeOverlay(candidate -> false); LogisticsPanel.overlayBottomInset(0);
    }
    public static Optional<IJeiRuntime> runtime() { return Optional.ofNullable(runtime); }
    @Override public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        // The standard extension calls assemble(), which intentionally cannot manufacture an authoritative pendant.
        registration.getSmithingCategory().addExtension(PendantSmithingRecipe.class, new ISmithingCategoryExtension<PendantSmithingRecipe>() {
            @Override public <T extends IIngredientAcceptor<T>> void setTemplate(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addIngredients(recipe.templateIngredient());
            }
            @Override public <T extends IIngredientAcceptor<T>> void setBase(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addIngredients(recipe.baseIngredient());
            }
            @Override public <T extends IIngredientAcceptor<T>> void setAddition(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addIngredients(recipe.additionIngredient());
            }
            @Override public <T extends IIngredientAcceptor<T>> void setOutput(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addItemStack(recipe.getResultItem(net.minecraft.client.Minecraft.getInstance().level.registryAccess()));
            }
        });
    }
    @Override public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        var helper = registration.getTransferHelper();
        registration.addRecipeTransferHandler(new FmpRecipeTransfer<>(InventoryMenu.class, null, 2, helper), mezz.jei.api.constants.RecipeTypes.CRAFTING);
        registration.addRecipeTransferHandler(new FmpRecipeTransfer<>(CraftingMenu.class, MenuType.CRAFTING, 3, helper), mezz.jei.api.constants.RecipeTypes.CRAFTING);
    }
    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        register(registration, InventoryScreen.class); register(registration, CraftingScreen.class); register(registration, CreativeModeInventoryScreen.class);
    }
    private static <T extends AbstractContainerScreen<?>> void register(IGuiHandlerRegistration registration, Class<T> type) {
        registration.addGuiContainerHandler(type, new IGuiContainerHandler<>() {
            @Override public List<Rect2i> getGuiExtraAreas(T screen) {
                return LogisticsPanel.exclusions(screen).stream().map(r -> new Rect2i(r.x(), r.y(), r.width(), r.height())).toList();
            }
        });
    }
}
