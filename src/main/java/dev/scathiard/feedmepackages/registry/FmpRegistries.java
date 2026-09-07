package dev.scathiard.feedmepackages.registry;

import com.mojang.serialization.Codec;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.item.SupplyLinkItem;
import dev.scathiard.feedmepackages.growth.PendantSmithingRecipe;
import dev.scathiard.feedmepackages.logistics.ParcelSeal;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.UUID;
import java.util.List;
import java.util.stream.IntStream;

public final class FmpRegistries {
    private FmpRegistries() {}
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FeedMePackages.MOD_ID);
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, FeedMePackages.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FeedMePackages.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPES = DeferredRegister.create(Registries.RECIPE_SERIALIZER, FeedMePackages.MOD_ID);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<PendantSmithingRecipe>> PENDANT_SMITHING = RECIPES.register("pendant_smithing", PendantSmithingRecipe.Serializer::new);
    public static final DeferredItem<PendantItem> PENDANT = ITEMS.register("supply_chain_pendant", () -> new PendantItem(new Item.Properties().stacksTo(1), false));
    public static final DeferredItem<PendantItem> PERSONAL_PENDANT = ITEMS.register("personal_supply_chain_pendant", () -> new PendantItem(new Item.Properties().stacksTo(1), true));
    public static final DeferredItem<Item> ASSEMBLY_TEMPLATE = ITEMS.register("assembly_template", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final List<DeferredItem<SupplyLinkItem>> UPGRADE_LINKS = IntStream.rangeClosed(2, 5).mapToObj(level ->
            ITEMS.register("upgrade_link_" + level, () -> new SupplyLinkItem(new Item.Properties(), level))).toList();
    public static final DeferredItem<SupplyLinkItem> PRIVATE_LINK = ITEMS.register("private_link", () -> new SupplyLinkItem(new Item.Properties(), 0));
    public static final List<DeferredItem<Item>> LINK_FRAMES = IntStream.rangeClosed(2, 5).mapToObj(level ->
            ITEMS.register("link_frame_" + level, () -> new Item(new Item.Properties()))).toList();
    public static final List<DeferredItem<Item>> INCOMPLETE_LINKS = IntStream.rangeClosed(2, 5).mapToObj(level ->
            ITEMS.register("incomplete_link_" + level, () -> new Item(new Item.Properties().stacksTo(1)))).toList();
    public static final DeferredItem<Item> INCOMPLETE_PRIVATE_LINK = ITEMS.register("incomplete_private_link", () -> new Item(new Item.Properties().stacksTo(1)));
    public static DeferredItem<SupplyLinkItem> upgradeLink(int level) {
        if (level < 2 || level > 5) throw new IllegalArgumentException("Invalid upgrade level"); return UPGRADE_LINKS.get(level - 2);
    }
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> IDENTITY = COMPONENTS.register("identity", () -> DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> NETWORK = COMPONENTS.register("network", () -> DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> DISABLED = COMPONENTS.register("disabled", () -> DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> OWNER = COMPONENTS.register("owner", () -> DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> OWNER_NAME = COMPONENTS.register("owner_name", () -> DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> PREVIEW = COMPONENTS.register("preview", () -> DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ParcelSeal>> PARCEL_SEAL = COMPONENTS.register("parcel_seal", () -> DataComponentType.<ParcelSeal>builder().persistent(ParcelSeal.CODEC).build());
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("supplies", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.create_feed_me_packages"))
            .icon(() -> PENDANT.toStack()).displayItems((parameters, output) -> {
                output.accept(PENDANT); output.accept(PERSONAL_PENDANT);
                output.accept(ASSEMBLY_TEMPLATE); UPGRADE_LINKS.forEach(output::accept); output.accept(PRIVATE_LINK);
                LINK_FRAMES.forEach(output::accept); INCOMPLETE_LINKS.forEach(output::accept); output.accept(INCOMPLETE_PRIVATE_LINK);
            }).build());
    public static void register(IEventBus bus) { COMPONENTS.register(bus); ITEMS.register(bus); RECIPES.register(bus); TABS.register(bus); }
}
