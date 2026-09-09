package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CursorCreativePacketMixin {
    @Shadow public ServerPlayer player;
    @Unique private int fmp$cursorBefore = -1;
    @Unique private boolean fmp$cursorDropHandled;

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER))
    private void fmp$beforeCreativePlacement(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        fmp$cursorBefore = -1;
        fmp$cursorDropHandled = false;
        if (!player.getAbilities().instabuild || !player.gameMode.isCreative()) return;
        CursorReservations.validate(player);
        fmp$cursorBefore = CursorReservations.creativeBefore(player,packet.slotNum());
    }
    @Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
    private void fmp$afterCreativePlacement(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        int before=fmp$cursorBefore; fmp$cursorBefore=-1;
        // Only restore the FMP preview when the creative carry was emptied/dropped (slotNum<0 with an
        // EMPTY item). A pick-up of a NEW item (itemStack non-empty) replaces the carry; restoring the old
        // preview would overwrite the fresh independent stone — the n=3 wipe the §21 trace caught.
        if (packet.slotNum() < 0 && !fmp$cursorDropHandled && packet.itemStack().isEmpty()) {
            CursorReservations.restoreCreativeCursor(player);
        }
        if (before >= 0) CursorReservations.creativeAfter(player,packet.slotNum(),before);
    }

    @WrapOperation(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private ItemEntity fmp$settleCreativeDrop(ServerPlayer player, ItemStack stack, boolean random,
            Operation<ItemEntity> original) {
        // Keep vanilla validation and drop rate limits; charge only accepted drops.
        ItemEntity entity = original.call(player,stack,random);
        fmp$cursorDropHandled = true;
        CursorReservations.creativeDropped(player,stack,entity);
        return entity;
    }
}
