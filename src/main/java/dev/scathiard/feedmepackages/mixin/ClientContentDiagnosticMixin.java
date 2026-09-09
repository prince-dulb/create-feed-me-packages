package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic only (tests): trace FMP creative cursor content packets on the client so the n=3
 *  reopen-carry source can be attributed to a concrete write, per Planner §21. Never ships an effect. */
@Mixin(ClientPacketListener.class)
public abstract class ClientContentDiagnosticMixin {
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void fmp$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        FeedMePackages.LOGGER.info("FMP_CONTENT id={} carry={} playerCarry={}",
                packet.getContainerId(), packet.getCarriedItem(), player == null ? null : player.containerMenu.getCarried());
    }
}
