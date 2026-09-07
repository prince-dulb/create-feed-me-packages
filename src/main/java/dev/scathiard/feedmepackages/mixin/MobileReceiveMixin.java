package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.logistics.ReceiveService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "de.theidler.create_mobile_packages.blocks.bee_port.BeePortBlockEntity", remap = false)
public abstract class MobileReceiveMixin {
    @Inject(method = "sendPackageToPlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fmp$receive(Player player, ItemStack box, CallbackInfoReturnable<Boolean> callback) {
        if (player instanceof ServerPlayer serverPlayer && ReceiveService.receive(serverPlayer, box)) callback.setReturnValue(true);
    }
}
