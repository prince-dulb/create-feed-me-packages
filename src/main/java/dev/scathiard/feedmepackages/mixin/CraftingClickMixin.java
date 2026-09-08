package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.consumption.CraftingSafety;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractContainerMenu.class)
public abstract class CraftingClickMixin {
    @WrapMethod(method = "clicked")
    private void fmp$click(int slot, int button, ClickType click, Player player, Operation<Void> original) {
        var menu = (AbstractContainerMenu)(Object)this;
        if (!(player instanceof ServerPlayer server) || !CraftingService.supported(menu)) {
            original.call(slot, button, click, player); return;
        }
        try (var cursor = CursorReservations.beforeClick(server, menu);
             var scope = CraftingReservations.begin(server, menu, slot, button, click)) {
            if (CraftingSafety.applies(server, menu) && ((slot == 0 && click != ClickType.CLONE && !CraftingSafety.canTake(server, click, button))
                    || click == ClickType.QUICK_MOVE && !CraftingReservations.canQuickMoveInput(server, slot))) {
                menu.broadcastFullState(); return;
            }
            original.call(slot, button, click, player);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "removed", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void fmp$cancelPreparation(Player player, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (player instanceof ServerPlayer server) {
            CursorReservations.cancel(server);
            CraftingReservations.cancel(server, (AbstractContainerMenu)(Object)this);
        }
    }
}
