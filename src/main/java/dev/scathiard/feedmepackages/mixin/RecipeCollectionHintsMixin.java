package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.client.ClientCrafting;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.stats.RecipeBook;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.crafting.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(RecipeCollection.class)
public abstract class RecipeCollectionHintsMixin {
    @Shadow @Final private Set<RecipeHolder<?>> craftable;
    @Shadow public abstract List<RecipeHolder<?>> getRecipes();
    @SuppressWarnings("unchecked")
    @Inject(method = "canCraft", at = @At("TAIL"))
    private void fmp$exact(StackedContents contents, int width, int height, RecipeBook book, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null || !ClientMaterials.active() || !CraftingService.supported(player.containerMenu)) return;
        for (var holder : getRecipes()) if (holder.value() instanceof CraftingRecipe recipe && !recipe.getIngredients().isEmpty()) {
            if (recipe.canCraftInDimensions(width, height) && book.contains(holder) && contents.canCraft(recipe, null)
                    && ClientCrafting.check(player, (RecipeHolder<CraftingRecipe>) holder, false, false) == CraftingService.Result.OK) craftable.add(holder);
            else craftable.remove(holder);
        }
    }
}
