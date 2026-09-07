package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.consumption.CraftingSafety;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({InventoryMenu.class, CraftingMenu.class})
public abstract class CraftingQuickMoveMixin {
    @WrapMethod(method = "quickMoveStack")
    private ItemStack fmp$capacity(Player player, int slot, Operation<ItemStack> original) {
        if (!(player instanceof ServerPlayer server)) return original.call(player, slot);
        var menu = (AbstractContainerMenu)(Object)this;
        try (var scope = CraftingReservations.begin(server, menu, slot, 0, ClickType.QUICK_MOVE)) {
            if (CraftingSafety.applies(server, menu) && (slot == 0 && !CraftingSafety.canTake(server, ClickType.QUICK_MOVE, 0)
                    || !CraftingReservations.canQuickMoveInput(server, slot))) return ItemStack.EMPTY;
            return original.call(player, slot);
        }
    }
}
