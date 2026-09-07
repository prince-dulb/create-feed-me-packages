package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.growth.GrowthService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractContainerMenu.class)
public abstract class SmithingClickMixin {
    @WrapMethod(method = "clicked")
    private void fmp$manufacture(int slot, int button, ClickType click, Player player, Operation<Void> original) {
        var menu = (AbstractContainerMenu)(Object)this;
        if (slot == 3 && GrowthService.applies(menu)) { GrowthService.take((SmithingMenu)menu, player, click, button); return; }
        original.call(slot, button, click, player);
    }
}
