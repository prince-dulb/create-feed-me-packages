package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.consumption.CraftingSafety;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class CraftingPickupMixin {
    @Inject(method = "mayPickup", at = @At("RETURN"), cancellable = true)
    private void fmp$remainders(Player player, CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValue() && player instanceof ServerPlayer server && !player.containerMenu.slots.isEmpty()
                && player.containerMenu.getSlot(0) == (Object)this && CraftingSafety.applies(server, player.containerMenu)
                && !CraftingSafety.canTake(server, ClickType.PICKUP, 0)) ci.setReturnValue(false);
    }
}
