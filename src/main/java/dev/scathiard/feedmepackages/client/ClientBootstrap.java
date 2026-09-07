package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientBootstrap {
    private ClientBootstrap() {}
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(() -> { LogisticsPanel.register(); ClientMaterials.register(); }); }
}
