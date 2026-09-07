package dev.scathiard.feedmepackages;

import com.mojang.logging.LogUtils;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import dev.scathiard.feedmepackages.network.MaterialHints;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;
import top.theillusivec4.curios.api.event.CurioChangeEvent;

@Mod(FeedMePackages.MOD_ID)
public final class FeedMePackages {
    public static final String MOD_ID = "create_feed_me_packages";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FeedMePackages(IEventBus modBus, ModContainer modContainer) {
        FmpRegistries.register(modBus);
        modBus.addListener(this::setup);
        modBus.addListener(PanelNetwork::register);
        modBus.addListener(MaterialHints::register);
        NeoForge.EVENT_BUS.addListener(this::playerTick);
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Close event) -> {
            if (event.getEntity() instanceof ServerPlayer player) PanelNetwork.forget(player);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) { CraftingReservations.forget(player); PanelNetwork.forget(player); MaterialHints.forget(player); }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangeGameModeEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) CraftingReservations.forget(player);
        });
        NeoForge.EVENT_BUS.addListener((CurioChangeEvent event) -> {
            if (event.getIdentifier().equals("necklace") && event.getEntity() instanceof ServerPlayer player) CacheActions.close(player);
        });
        LOGGER.info("Loading Create: Feed Me Packages! {}", modContainer.getModInfo().getVersion());
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ICurioItem behavior = new ICurioItem() {
                @Override public boolean canEquip(SlotContext context, ItemStack stack) {
                    return dev.scathiard.feedmepackages.service.AccessGate.canEquip(context, stack);
                }
            };
            CuriosApi.registerCurio(FmpRegistries.PENDANT.get(), behavior);
            CuriosApi.registerCurio(FmpRegistries.PERSONAL_PENDANT.get(), behavior);
        });
    }

    private void playerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CraftingReservations.validate(player);
            CacheActions.validateOpenContext(player);
            SupplyService.tick(player);
            ReturnService.tick(player);
            MaterialHints.tick(player);
        }
    }
}
