package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.growth.GrowthService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemCombinerMenu.class)
public abstract class SmithingQuickMoveMixin {
    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void fmp$manufacture(Player player, int index, CallbackInfoReturnable<ItemStack> ci) {
        var menu = (AbstractContainerMenu)(Object)this;
        if (index == 3 && GrowthService.applies(menu)) ci.setReturnValue(GrowthService.take((SmithingMenu)menu, player, ClickType.QUICK_MOVE, 0));
    }
}
