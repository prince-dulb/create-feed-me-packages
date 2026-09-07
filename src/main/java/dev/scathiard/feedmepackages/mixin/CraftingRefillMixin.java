package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ResultSlot.class)
public abstract class CraftingRefillMixin {
    @WrapMethod(method = "onTake")
    private void fmp$refill(Player player, ItemStack result, Operation<Void> original) {
        var refill = player instanceof ServerPlayer server && CraftingService.supported(player.containerMenu)
                && player.containerMenu.getSlot(0) == (Object) this ? CraftingService.beforeCraft(server) : null;
        original.call(player, result);
        if (player instanceof ServerPlayer server) CraftingService.afterCraft(server, refill);
    }
}
