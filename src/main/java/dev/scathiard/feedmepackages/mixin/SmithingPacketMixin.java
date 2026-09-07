package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.growth.GrowthService;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SmithingPacketMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void fmp$rejectOldManufacturingClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        var menu = player.containerMenu;
        if (packet.getContainerId() == menu.containerId && packet.getSlotNum() == 3 && GrowthService.applies(menu)
                && packet.getStateId() != menu.getStateId()) {
            menu.broadcastFullState(); player.displayClientMessage(Component.translatable("message.create_feed_me_packages.growth.stale"), true); ci.cancel();
        }
    }
}
