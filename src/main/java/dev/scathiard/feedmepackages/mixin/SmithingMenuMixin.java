package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.growth.GrowthService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {
    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true)
    private void fmp$preview(CallbackInfo ci) {
        var menu = (SmithingMenu)(Object)this;
        if (GrowthService.applies(menu)) { GrowthService.refresh(menu); ci.cancel(); }
    }
    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void fmp$noUncontrolledResult(Player player, boolean hasStack, CallbackInfoReturnable<Boolean> ci) {
        if (GrowthService.applies((SmithingMenu)(Object)this)) ci.setReturnValue(false);
    }
    @Inject(method = "onTake", at = @At("HEAD"), cancellable = true)
    private void fmp$noUncontrolledTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (GrowthService.applies((SmithingMenu)(Object)this) || stack.getOrDefault(FmpRegistries.PREVIEW.get(), false)) ci.cancel();
    }
}
