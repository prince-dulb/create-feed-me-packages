package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.logistics.ReceiveService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryReceiveMixin {
    @Shadow @Final public Player player;
    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void fmp$receive(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        if (player instanceof ServerPlayer serverPlayer && ReceiveService.receive(serverPlayer, stack)) callback.setReturnValue(true);
    }
    @Inject(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("HEAD"), cancellable = true)
    private void fmp$receiveBeforeDrop(ItemStack stack, boolean sendPacket, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer && ReceiveService.receive(serverPlayer, stack)) callback.cancel();
    }
}
