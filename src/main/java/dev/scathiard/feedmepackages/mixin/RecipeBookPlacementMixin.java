package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.consumption.CraftingService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeBookMenu.class)
public abstract class RecipeBookPlacementMixin {
    @Inject(method = "handlePlacement", at = @At("HEAD"), cancellable = true)
    private void fmp$place(boolean maximum, RecipeHolder<?> recipe, ServerPlayer player, CallbackInfo ci) {
        if (player.containerMenu == (Object) this && recipe != null && CraftingService.fromRecipeBook(player, recipe, maximum)) ci.cancel();
    }
}
