package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.client.PanelLayout;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Explicit opt-in, test-source-only driver. Uses real client/server networking and container event routing. */
@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientReview {
    private static int phase, ticks, changed;
    private static CompletableFuture<Void> work;
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static boolean failed;
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        if (Boolean.getBoolean("fmp.clientReview") || Boolean.getBoolean("fmp.t14Review"))
            event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(ClientReview::tick));
    }
    private static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance(); ticks++;
        try {
            if (work != null) { if (!work.isDone()) return; work.join(); work = null; }
            if (Boolean.getBoolean("fmp.t14Review")) {
                reviewT14();
                return;
            }
            if (ticks - changed > 2400) throw new IllegalStateException("Client review timed out in phase " + phase);
            if (Boolean.getBoolean("fmp.uiPlacementReview")) {
                reviewUiPlacement();
                return;
            }
            if (Boolean.getBoolean("fmp.t14Review")) {
                reviewT14();
                return;
            }
            switch (phase) {
                case 0 -> {
                    if (mc.getOverlay() != null || mc.screen == null) return;
                    mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.resizeDisplay();
                    mc.options.languageCode = "zh_cn"; mc.getLanguageManager().setSelected("zh_cn");
                    work = mc.reloadResourcePacks(); advance();
                }
                case 1 -> {
                    FeedMePackages.LOGGER.info("FMP_CLIENT_REVIEW_BEGIN {} {}", RUN, mc.gameDirectory);
                    mc.createWorldOpenFlows().createFreshLevel("fmp-ui-" + RUN,
                            new LevelSettings("FMP UI " + RUN, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(7319L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                    advance();
                }
                case 2 -> {
                    if (mc.player == null || mc.screen != null || mc.getSingleplayerServer() == null || mc.player.tickCount < 20) return;
                    server(player -> {
                        var level = player.serverLevel();
                        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) level.setBlockAndUpdate(new BlockPos(x, 120, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        player.teleportTo(5, 121, 5); level.setDayTime(6000);
                        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                        AccessGate.resolve(player); player.containerMenu.setCarried(new ItemStack(Items.STONE, 64)); player.containerMenu.broadcastFullState();
                    }); advance();
                }
                case 3 -> { mc.setScreen(new InventoryScreen(mc.player)); advance(); }
                case 4 -> {
                    if (ticks - changed < 30 || LogisticsPanel.visibleCells(mc.screen).size() != 9) return;
                    var first = LogisticsPanel.visibleCells(mc.screen).getFirst().bounds(); click(first.x() + 8, first.y() + 8); advance();
                }
                case 5 -> {
                    if (ticks - changed < 20 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "Survival deposit did not clear real cursor");
                    server(player -> require(stock(player) == 64, "Survival UI deposit did not reach server cache"));
                    capture("01-survival");
                    var first = LogisticsPanel.visibleCells(mc.screen).getFirst().bounds();
                    click(first.x() + 8, first.y() + 8); advance();
                }
                case 6 -> {
                    if (ticks - changed < 20 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    require(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 64, "Survival withdrawal lost cursor stack");
                    server(player -> {
                        require(stock(player) == 64, "Survival preview must not deduct cache before placement");
                        require(CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 64, "Survival take must reserve the preview amount");
                    });
                    placeIntoBackpack(0); advance();
                }
                case 7 -> {
                    if (ticks - changed < 20) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "Survival placement did not clear the preview cursor");
                    server(player -> {
                        require(stock(player) == 0, "Survival placement did not debit the cache");
                        require(player.getInventory().countItem(Items.STONE) == 64, "Survival placement lost the withdrawn stack");
                        require(CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0, "Survival placement left a dangling reservation");
                    });
                    server(player -> {
                        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(handle.cacheId());
                        var edit = before.state().edit(); for (int level = 1; level < 5; level++) edit.upgrade(); edit.thresholds(0, 2, -1);
                        Item[] examples = {Items.IRON_INGOT, Items.COPPER_INGOT, Items.REDSTONE, Items.ANDESITE, Items.GLASS, Items.HOPPER, Items.RAIL, Items.OAK_PLANKS};
                        for (int i = 0; i < examples.length; i++) {
                            var key = ItemVariantKey.of(new ItemStack(examples[i]), player.registryAccess()); edit.filter(i + 1, key); edit.insert(i + 1, key, 100 + i * 173);
                        }
                        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
                    }); advance();
                }
                case 8 -> {
                    if (ticks - changed < 25 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    clickCellDot(0); advance();
                }
                case 9 -> {
                    if (ticks - changed < 15 || panelLayout().slider() == null) return;
                    capture("02-expanded-level5");
                    dragMinimumToMidpoint(0); advance();
                }
                case 10 -> {
                    if (ticks - changed < 30) return;
                    server(player -> {
                        var cell = CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().getFirst();
                        require(cell.minimum() == 16 && cell.maximum() == -1, "Slider minimum must be 16 groups (16x64=1024), max -1");
                        require(cell.amount() == 0, "Slider drag must not move items");
                        player.setGameMode(GameType.CREATIVE);
                    }); mc.setScreen(null); advance();
                }
                case 11 -> { if (ticks - changed < 10) return; mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get())); advance(); }
                case 12 -> {
                    if (ticks - changed < 25 || !(mc.screen instanceof CreativeModeInventoryScreen) || !LogisticsPanel.recipeReady() || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    mc.player.containerMenu.setCarried(new ItemStack(Items.STONE, 3));
                    var cell = LogisticsPanel.visibleCells(mc.screen).stream().filter(c -> c.slot() == 0).findFirst().orElseThrow().bounds();
                    click(cell.x() + 8, cell.y() + 8); advance();
                }
                case 13 -> {
                    if (ticks - changed < 20) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "Creative full-state sync did not update cursor");
                    server(player -> require(stock(player) == 3, "Creative UI deposit must add 3 to cache")); capture("03-creative");
                    advance();
                }
                case 14 -> {
                    if (ticks - changed < 20) return;
                    if (!creativePlacementDone) {
                        if (!reviewCreativePlacement()) return;
                        creativePlacementDone = true;
                    }
                    if (!creativeIndependentDone) {
                        if (!reviewCreativeIndependentSource()) return;
                        creativeIndependentDone = true;
                    }
                    if (!independentPlaceDone) {
                        if (!reviewIndependentPlace()) return;
                        independentPlaceDone = true;
                    }
                    if (!sameTickDone) {
                        if (!reviewSameTick()) return;
                        sameTickDone = true;
                    }
                    if (!provenanceDone) {
                        if (!reviewCreativeProvenance()) return;
                        provenanceDone = true;
                    }
                    advance();
                }
                case 15 -> {
                    if (ticks - changed < 20) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "Creative redeposit cursor failed");
                    if (ModList.get().isLoaded("jei")) jei("begin", null); advance();
                }
                case 16 -> {
                    if (ticks - changed < 10) return;
                    if (ModList.get().isLoaded("jei")) jei("finish", null); advance();
                }
                case 17 -> {
                    if (ticks - changed < 20) return;
                    if (ModList.get().isLoaded("jei")) jei("clickOnly", null);
                    server(player -> {
                        if (ModList.get().isLoaded("jei")) jei("verify", player);
                        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, ItemStack.EMPTY);
                    }); advance();
                }
                case 18 -> {
                    if (ticks - changed < 30) return;
                    require(LogisticsPanel.exclusions(mc.screen).isEmpty(), "Panel remained after unwear"); capture("04-unworn"); advance();
                }
                case 19 -> {
                    if (ticks - changed < 40) return;
                    if (!reviewLayouts()) return;
                    if (!clickOutsideDone) {
                        if (!reviewClickOutside()) return;
                        clickOutsideDone = true;
                    }
                    advance();
                }
                case 20 -> {
                    if (!ClientConsumptionReview.tick()) return;
                    advance();
                }
                case 21 -> {
                    if (!ClientGrowthReview.tick()) return;
                    FeedMePackages.LOGGER.info("FMP_CLIENT_REVIEW_PASSED {}", RUN);
                    advance(); mc.level.disconnect(); mc.disconnect(new TitleScreen());
                }
                case 22 -> { if (mc.level == null && mc.getSingleplayerServer() == null) { mc.stop(); advance(); } }
                default -> {}
            }
        } catch (Throwable problem) {
            if (!failed) { failed = true; phase = 99; FeedMePackages.LOGGER.error("FMP_CLIENT_REVIEW_FAILED", problem); capture("failure"); if (mc.level != null) mc.level.disconnect(); mc.disconnect(new TitleScreen()); mc.stop(); }
        }
    }
    private static int uiCase;
    /** Narrow, opt-in visual check; never exercises or changes the production inventory transaction. */
    private static void reviewUiPlacement() throws ReflectiveOperationException {
        var mc = Minecraft.getInstance();
        switch (phase) {
            case 0 -> {
                if (mc.getOverlay() != null || mc.screen == null) return;
                mc.options.pauseOnLostFocus = false;
                mc.getWindow().setWindowed(1280, 960);
                mc.options.guiScale().set(2); mc.resizeDisplay();
                mc.options.languageCode = "zh_cn"; mc.getLanguageManager().setSelected("zh_cn");
                work = mc.reloadResourcePacks(); advance();
            }
            case 1 -> {
                mc.createWorldOpenFlows().createFreshLevel("fmp-ui-placement-" + RUN,
                        new LevelSettings("FMP UI placement " + RUN, GameType.SURVIVAL, false,
                                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(7319L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                advance();
            }
            case 2 -> {
                if (mc.player == null || mc.screen != null || mc.getSingleplayerServer() == null || mc.player.tickCount < 20) return;
                server(player -> {
                    player.setGameMode(uiCase == 2 ? GameType.CREATIVE : GameType.SURVIVAL);
                    player.getInventory().clearContent(); player.containerMenu.setCarried(ItemStack.EMPTY);
                    CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow()
                            .getStacks().setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                    var handle = AccessGate.resolve(player).handle();
                    var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(handle.cacheId());
                    var edit = before.state().edit();
                    if (uiCase > 0) for (int i = 1; i < 5; i++) edit.upgrade();
                    int count = uiCase == 0 ? 9 : 36;
                    for (int i = 0; i < count; i++) {
                        var key = ItemVariantKey.of(layoutItem(i), player.registryAccess());
                        edit.filter(i, key); edit.insert(i, key, 64);
                    }
                    ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
                    player.containerMenu.broadcastFullState();
                }); advance();
            }
            case 3 -> {
                mc.options.guiScale().set(uiCase == 3 ? 4 : uiCase == 2 ? 3 : 2); mc.resizeDisplay();
                mc.setScreen(new InventoryScreen(mc.player)); advance();
            }
            case 4 -> {
                if (ticks - changed < 30) return;
                if (LogisticsPanel.visibleCells(mc.screen).isEmpty()) {
                    var layout = uiLayout();
                    if (layout != null && layout.compact()) {
                        click(layout.bounds().x() + 8, layout.bounds().y() + 8); changed = ticks;
                    }
                    return;
                }
                capture("placement-" + uiCase + "-closed");
                var first = LogisticsPanel.visibleCells(mc.screen).getFirst().dot();
                click(first.x() + 1, first.y() + 2); advance();
            }
            case 5 -> {
                if (ticks - changed < 20) return;
                require(uiLayout().slider() != null, "First-cell slider did not open");
                capture("placement-" + uiCase + "-first");
                var last = LogisticsPanel.visibleCells(mc.screen).getLast().dot();
                click(last.x() + 1, last.y() + 2); advance();
            }
            case 6 -> {
                if (ticks - changed < 20) return;
                var layout = uiLayout(); var slider = layout.slider();
                require(slider != null, "Last-cell slider did not open");
                capture("placement-" + uiCase + "-last");
                // Both endpoints must remain interactive where the popup covers the address.
                click(slider.x() + 5, slider.y() + 12); advance();
            }
            case 7 -> {
                if (ticks - changed < 20) return;
                var editing = LogisticsPanel.class.getDeclaredField("returnEditing"); editing.setAccessible(true);
                require(!editing.getBoolean(null), "Slider click leaked into return-address editor");
                require(mc.player.containerMenu.getCarried().isEmpty(), "Slider click took an item from an underlying cell");
                if (uiCase == 1 && mc.screen instanceof InventoryScreen inventory) {
                    inventory.getRecipeBookComponent().toggleVisibility(); mc.screen.init(mc, mc.screen.width, mc.screen.height);
                }
                advance();
            }
            case 8 -> {
                if (ticks - changed < 20) return;
                capture("placement-" + uiCase + "-after-click"); advance();
            }
            case 9 -> {
                if (ticks - changed < 10) return;
                mc.setScreen(null);
                if (++uiCase < 4) { phase = 2; changed = ticks; }
                else { FeedMePackages.LOGGER.info("FMP_UI_PLACEMENT_REVIEW_PASSED {}", RUN);
                    mc.level.disconnect(); mc.disconnect(new TitleScreen()); advance(); }
            }
            case 10 -> { if (mc.level == null && mc.getSingleplayerServer() == null) { mc.stop(); advance(); } }
            default -> {}
        }
    }
    private static PanelLayout uiLayout() throws ReflectiveOperationException {
        var field = LogisticsPanel.class.getDeclaredField("layout"); field.setAccessible(true);
        return (PanelLayout) field.get(null);
    }
    private static int creativeStage, creativeChanged;
    private static boolean creativePlacementDone;
    private static boolean creativeIndependentDone;
    private static boolean independentPlaceDone;
    private static boolean sameTickDone;
    private static boolean clickOutsideDone;
    private static boolean reviewCreativePlacement() {
        var mc = Minecraft.getInstance();
        if (creativeStage != 0 && ticks - creativeChanged < 20) return false;
        switch (creativeStage) {
            case 0 -> { // creative preview take from cell 0 (empty cursor, cell holds 3 stone)
                require(mc.player.containerMenu.getCarried().isEmpty(), "Creative start cursor must be empty");
                clickCellBody(0);
            }
            case 1 -> { // preview: cursor holds 3, cache unchanged, reserved 3
                require(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 3, "Creative preview got wrong cursor amount");
                server(player -> {
                    require(stock(player) == 3, "Creative preview must not deduct cache");
                    require(CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 3, "Creative preview must reserve 3");
                });
                mc.player.closeContainer(); // cancel-timing: close with unplaced preview
            }
            case 2 -> { // cancel via normal player close must not deduct; reservation cleared; real client cursor state
                var carried = mc.player.containerMenu.getCarried();
                FeedMePackages.LOGGER.info("FMP_CREATIVE_CANCEL_CURSOR empty={} count={} item={}", carried.isEmpty(), carried.getCount(), carried);
                require(carried.isEmpty(), "Creative cancel did not auto-clear the client cursor (count=" + carried.getCount() + ")");
                server(player -> {
                    require(stock(player) == 3, "Creative cancel must not deduct cache");
                    require(CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0, "Creative cancel must clear reservation");
                });
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
            }
            case 3 -> {
                require(mc.screen instanceof CreativeModeInventoryScreen, "Creative reopen did not restore the creative screen");
                return true;
            }
            default -> throw new IllegalStateException("Unexpected creative review stage");
        }
        creativeStage++; creativeChanged = ticks; return false;
    }
    private static int t14Stage, t14Changed;
    private static boolean t14Transition, t14Failed;
    private static String t14WorldId;
    private static UUID t14Player, t14CacheId;
    private static MinecraftServer t14OldServer;
    private static final long T14_STOCK = 120;

    private static void reviewT14() {
        var mc = Minecraft.getInstance();
        if (t14Transition) return; // disconnect/openWorld 内部会 runTick → 重入保护
        try {
            if (ticks - t14Changed > 2400) throw new IllegalStateException("T14 review timed out in stage " + t14Stage);
            if (work != null) { if (!work.isDone()) return; work.join(); work = null; }
            switch (t14Stage) {
                case 0 -> { // 语言/缩放/资源
                    if (mc.getOverlay() != null || mc.screen == null) return;
                    mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.resizeDisplay();
                    mc.options.languageCode = "zh_cn"; mc.getLanguageManager().setSelected("zh_cn");
                    work = mc.reloadResourcePacks(); t14Next();
                }
                case 1 -> { // 创建隔离世界
                    t14WorldId = "fmp-t14-" + RUN;
                    mc.createWorldOpenFlows().createFreshLevel(t14WorldId,
                            new LevelSettings("FMP T14 " + RUN, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(7319L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                    t14Next();
                }
                case 2 -> { // 等入服；建立状态：缓存120、背包0、佩戴坠子，记录 UUID/cacheId
                    if (mc.player == null || mc.screen != null || mc.getSingleplayerServer() == null || mc.player.tickCount < 20) return;
                    server(player -> {
                        var level = player.serverLevel();
                        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) level.setBlockAndUpdate(new BlockPos(x, 120, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        player.teleportTo(5, 121, 5); level.setDayTime(6000);
                        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                        AccessGate.resolve(player);
                        player.getInventory().clearContent();
                        var handle = AccessGate.resolve(player).handle();
                        t14Player = player.getUUID(); t14CacheId = handle.cacheId();
                        var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(handle.cacheId());
                        var edit = before.state().edit();
                        var stone = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
                        edit.filter(0, stone); edit.insert(0, stone, (int) T14_STOCK);
                        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
                        player.containerMenu.broadcastFullState();
                    }); t14Next();
                }
                case 3 -> { if (ticks - t14Changed < 30) return; mc.setScreen(new InventoryScreen(mc.player)); t14Next(); }
                case 4 -> { // 光标空点格0 → 拿起 64 预览
                    if (ticks - t14Changed < 25 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    clickCellBody(0); t14Next();
                }
                case 5 -> { // 预览64：光标64 / 缓存120不变 / 预留64
                    if (ticks - t14Changed < 20 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    require(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 64, "T14 preview take amount");
                    server(player -> {
                        require(stock(player) == T14_STOCK, "T14 preview must not deduct");
                        require(CursorReservations.reserved(t14CacheId, 0) == 64, "T14 preview reserve");
                    }); t14Next();
                }
                case 6 -> { if (ticks - t14Changed < 15) return; placeIntoBackpack(0); t14Next(); }
                case 7 -> { // 整份放下：光标空 / 缓存56 / 预留0 / 背包64
                    if (ticks - t14Changed < 20 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "T14 placement cursor");
                    server(player -> {
                        require(stock(player) == T14_STOCK - 64, "T14 placement debit");
                        require(CursorReservations.reserved(t14CacheId, 0) == 0, "T14 placement reserve");
                        require(inventoryStones(player) == 64, "T14 placement backpack");
                    }); t14Next();
                }
                case 8 -> { // 暂留预览56（真实拿起,无落位）
                    if (ticks - t14Changed < 20 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    clickCellBody(0); t14Next();
                }
                case 9 -> { // 暂留：光标56 / 缓存56不变 / 预留56
                    if (ticks - t14Changed < 20 || LogisticsPanel.visibleCells(mc.screen).isEmpty()) return;
                    require(mc.player.containerMenu.getCarried().getCount() == 56, "T14 transient take amount");
                    server(player -> {
                        require(stock(player) == T14_STOCK - 64, "T14 transient must not deduct");
                        require(CursorReservations.reserved(t14CacheId, 0) == 56, "T14 transient reserve");
                    }); t14Next();
                }
                case 10 -> { if (ticks - t14Changed < 15) return; mc.player.closeContainer(); t14Next(); }
                case 11 -> { // 取消暂留：光标空 / 缓存56不变 / 预留0 / 背包64
                    if (ticks - t14Changed < 25) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "T14 cancel cursor");
                    server(player -> {
                        require(stock(player) == T14_STOCK - 64, "T14 cancel must not deduct");
                        require(CursorReservations.reserved(t14CacheId, 0) == 0, "T14 cancel clear reservation");
                        require(inventoryStones(player) == 64, "T14 cancel backpack");
                    }); t14Next();
                }
                case 12 -> { // 原生退出序列：先断连接再 disconnect(保存)
                    if (ticks - t14Changed < 20 || mc.level == null) return;
                    t14OldServer = mc.getSingleplayerServer();
                    t14Transition = true;
                    try { mc.level.disconnect(); mc.disconnect(new TitleScreen()); }
                    finally { t14Transition = false; }
                    t14Next();
                }
                case 13 -> { // 等旧服 shutdown 且客户端 level/player 清空
                    if (ticks - t14Changed < 20) return;
                    if (t14OldServer != null && !t14OldServer.isShutdown()) return;
                    if (mc.level != null || mc.getSingleplayerServer() != null) return;
                    t14Next();
                }
                case 14 -> { // 加载原世界（同 worldId）
                    if (ticks - t14Changed < 30) return;
                    t14Transition = true;
                    try { mc.createWorldOpenFlows().openWorld(t14WorldId, () -> { t14Failed = true; mc.setScreen(new TitleScreen()); }); }
                    finally { t14Transition = false; }
                    t14Next();
                }
                case 15 -> { // 等新服就绪、同一玩家重进
                    if (ticks - t14Changed < 30) return;
                    if (mc.player == null || mc.screen != null || mc.getSingleplayerServer() == null || mc.player.tickCount < 20) return;
                    t14Next();
                }
                case 16 -> { // 重载后服务端验证（异步 work；PASSED 在 case 17 等 work join 成功后）
                    if (ticks - t14Changed < 40) return;
                    server(player -> {
                        require(player.getUUID().equals(t14Player), "T14 reload player UUID changed");
                        require(AccessGate.resolve(player).handle().cacheId().equals(t14CacheId), "T14 reload cacheId changed");
                        require(stock(player) == T14_STOCK - 64, "T14 reload cache stock");
                        require(inventoryStones(player) == 64, "T14 reload backpack");
                        require(CursorReservations.reserved(t14CacheId, 0) == 0, "T14 reload reservation");
                    });
                    require(mc.player.containerMenu.getCarried().isEmpty(), "T14 reload cursor");
                    t14Next();
                }
                case 17 -> { // tick 开头已 join 异步 server work；确认后再报通过，再干净停止
                    if (ticks - t14Changed < 40) return;
                    FeedMePackages.LOGGER.info("FMP_T14_PASSED {}", RUN);
                    mc.stop(); t14Next();
                }
                default -> {}
            }
        } catch (Throwable problem) {
            if (!t14Failed) {
                t14Failed = true; FeedMePackages.LOGGER.error("FMP_T14_FAILED", problem); capture("t14-failure");
                if (mc.level != null) mc.level.disconnect();
                mc.disconnect(new TitleScreen());
            }
        }
    }
    private static void t14Next() { t14Stage++; t14Changed = ticks; }
    private static int inventoryStones(ServerPlayer player) {
        return player.getInventory().items.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
    }

    // T13 (Planner §17/18): an independent creative-source same-variant carry must NOT be withdrawn by
    // an old FMP preview ownership. FMP preview 3 → left-click creative-list dirt (clears the carry) →
    // left-click creative-list stone (fresh independent N) → close reopen → N kept, cache 3, reserve 0.
    private static int t13Stage, t13Changed;
    private static int t13Independent;
    private static boolean reviewCreativeIndependentSource() {
        var mc = Minecraft.getInstance();
        if (t13Stage != 0 && ticks - t13Changed < 20) return false;
        switch (t13Stage) {
            case 0 -> { // select NATURAL_BLOCKS, then FMP take preview 3 (cache 3)
                if (!(mc.screen instanceof CreativeModeInventoryScreen)) throw new IllegalStateException("T13 needs the creative screen");
                try {
                    var select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                    select.setAccessible(true);
                    select.invoke(mc.screen, net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
                            .getHolderOrThrow(net.minecraft.world.item.CreativeModeTabs.NATURAL_BLOCKS).value());
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("T13 selectTab failed", e); }
                clickCellBody(0);
            }
            case 1 -> { // wait carry == 3 preview
                if (!(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 3)) return false;
                server(player -> require(stock(player) == 3 && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 3, "T13 preview state"));
                clickCreativeSlot(Items.DIRT); // clears the carry (empty) -> ownership invalidated
            }
            case 2 -> { // wait carry empty (dirt cleared); then grab a fresh independent stone
                if (!mc.player.containerMenu.getCarried().isEmpty()) return false;
                clickCreativeSlot(Items.STONE);
            }
            case 3 -> { // wait fresh independent stone N on the carry
                var cs = (mc.screen instanceof CreativeModeInventoryScreen) ? (CreativeModeInventoryScreen) mc.screen : null;
                FeedMePackages.LOGGER.info("FMP_T13_DIAG mcCarry={} creativeCarry={}",
                        mc.player.containerMenu.getCarried(), cs == null ? null : cs.getMenu().getCarried());
                int n = mc.player.containerMenu.getCarried().getCount();
                if (n <= 0) return false;
                t13Independent = n;
                mc.player.closeContainer(); // normal close (independent stone returns to inventory)
            }
            case 4 -> { // server confirm + reopen the creative screen
                server(player -> require(stock(player) == 3 && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0,
                        "T13 must not deduct cache or leave a reservation"));
                FeedMePackages.LOGGER.info("FMP_T13_INDEPENDENT_PASSED N={}", t13Independent);
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
            }
            case 5 -> {
                if (!(mc.screen instanceof CreativeModeInventoryScreen)) return false;
                int n = mc.player.containerMenu.getCarried().getCount();
                require(n == t13Independent,
                        "T13 independent stone not preserved on reopen carry (n=" + n + ", expected " + t13Independent + ")");
                mc.player.containerMenu.setCarried(ItemStack.EMPTY); // scenario handoff: T13 verified the preserve; clear for next phase
                return true;
            }
            default -> throw new IllegalStateException("Unexpected T13 stage");
        }
        t13Stage++; t13Changed = ticks; return false;
    }
    private static void t13Next() { t13Stage++; t13Changed = ticks; }
    private static void clickCreativeSlot(net.minecraft.world.item.Item item) {
        var mc = Minecraft.getInstance();
        try {
            var creative = (CreativeModeInventoryScreen) mc.screen;
            var field = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER"); field.setAccessible(true);
            var listContainer = (net.minecraft.world.Container) field.get(null);
            var picker = creative.getMenu();
            var slot = picker.slots.stream().filter(s -> s.container == listContainer && s.getItem().is(item)).findFirst().orElseThrow();
            click(creative.getGuiLeft() + slot.x + 8, creative.getGuiTop() + slot.y + 8);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("T13 click slot failed for " + item, e); }
    }

    private static int gap2Stage, gap2Changed;
    private static int gap2N;
    // Gap2 (Planner §20): fresh independent source stone N placed into a cleared hotbar slot, then close.
    // The cache must stay 3 (independent stone is not the cache-own preview); the old hold must be released
    // (releasePreview fix), never debited as 3−N.
    private static boolean reviewIndependentPlace() {
        var mc = Minecraft.getInstance();
        if (gap2Stage != 0 && ticks - gap2Changed < 20) return false;
        switch (gap2Stage) {
            case 0 -> {
                if (!(mc.screen instanceof CreativeModeInventoryScreen)) throw new IllegalStateException("gap2 needs the creative screen");
                try {
                    var select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                    select.setAccessible(true);
                    select.invoke(mc.screen, net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
                            .getHolderOrThrow(net.minecraft.world.item.CreativeModeTabs.NATURAL_BLOCKS).value());
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("gap2 selectTab failed", e); }
                clickCellBody(0);
            }
            case 1 -> {
                if (!(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 3)) return false;
                server(player -> require(stock(player) == 3 && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 3, "gap2 preview state"));
                clickCreativeSlot(Items.DIRT);
            }
            case 2 -> {
                if (!mc.player.containerMenu.getCarried().isEmpty()) return false;
                clickCreativeSlot(Items.STONE);
            }
            case 3 -> { // fresh independent N; clear the SERVER hotbar slot 0 baseline (not just the client carry)
                int n = mc.player.containerMenu.getCarried().getCount();
                if (n <= 0) return false;
                gap2N = n;
                server(player -> player.getInventory().setItem(0, ItemStack.EMPTY));
            }
            case 4 -> { // place N into the cleared hotbar slot
                if (ticks - gap2Changed < 20) return false; // wait for the server baseline work to join
                placeIntoBackpack(0);
            }
            case 5 -> {
                server(player -> {
                    require(stock(player) == 3, "gap2 independent placement wrongly charged cache (S=" + stock(player) + ", expected 3)");
                    require(CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0, "gap2 left a reservation");
                    require(player.getInventory().getItem(0).getCount() == gap2N,
                            "gap2 stone not in hotbar slot0 (inv0=" + player.getInventory().getItem(0).getCount() + ", expected " + gap2N + ")");
                });
                FeedMePackages.LOGGER.info("FMP_GAP2_INDEPENDENT_PLACE_PASSED N={}", gap2N);
                mc.player.closeContainer();
            }
            case 6 -> {
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
                return true;
            }
            default -> throw new IllegalStateException("Unexpected gap2 stage");
        }
        gap2Stage++; gap2Changed = ticks; return false;
    }

    private static int gap1Stage, gap1Changed;
    private static int gap1N;
    // Gap1 (Planner §20): after a confirmed preview, click dirt then stone IN THE SAME TICK (no wait
    // between, so the per-tick ownership check would miss the replacement) and close. The fresh independent
    // stone N must still be preserved (the operation-boundary hook + releasePreview fix handle it).
    private static boolean reviewSameTick() {
        var mc = Minecraft.getInstance();
        if (gap1Stage != 0 && ticks - gap1Changed < 20) return false;
        switch (gap1Stage) {
            case 0 -> {
                if (!(mc.screen instanceof CreativeModeInventoryScreen)) throw new IllegalStateException("gap1 needs the creative screen");
                if (LogisticsPanel.visibleCells(mc.screen).isEmpty()) return false; // wait panel ready after the previous scenario
                try {
                    var select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", net.minecraft.world.item.CreativeModeTab.class);
                    select.setAccessible(true);
                    select.invoke(mc.screen, net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
                            .getHolderOrThrow(net.minecraft.world.item.CreativeModeTabs.NATURAL_BLOCKS).value());
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("gap1 selectTab failed", e); }
                clickCellBody(0);
            }
            case 1 -> { // wait preview 3 confirmed, then SAME-TICK dirt + stone (no tick between)
                if (!(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 3)) return false;
                server(player -> require(stock(player) == 3 && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 3, "gap1 preview state"));
                clickCreativeSlot(Items.DIRT);
                clickCreativeSlot(Items.STONE);
            }
            case 2 -> { // wait independent N, verify cache 3 / reserve 0, then close
                int n = mc.player.containerMenu.getCarried().getCount();
                if (n <= 0) return false;
                gap1N = n;
                server(player -> {
                    require(stock(player) == 3, "gap1 same-tick wrongly charged cache (S=" + stock(player) + ")");
                    require(CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0, "gap1 same-tick left a reservation");
                });
                FeedMePackages.LOGGER.info("FMP_GAP1_SAMETICK_PASSED N={}", gap1N);
                mc.player.closeContainer();
            }
            case 3 -> {
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
                mc.player.containerMenu.setCarried(ItemStack.EMPTY); // scenario handoff: next phase expects an empty carry
                return true;
            }
            default -> throw new IllegalStateException("Unexpected gap1 stage");
        }
        gap1Stage++; gap1Changed = ticks; return false;
    }

    private static boolean provenanceDone, holdNextGrant, holdNextUpdate;
    private static int provenanceStage, provenanceChanged;
    private static PanelPackets.CursorUpdate delayedGrant;
    private static Consumer<PanelPackets.CursorUpdate> realCursorReceiver;

    private static boolean reviewCreativeProvenance() {
        var mc = Minecraft.getInstance();
        if (provenanceStage != 0 && ticks - provenanceChanged < 20) return false;
        switch (provenanceStage) {
            case 0 -> {
                if (LogisticsPanel.visibleCells(mc.screen).isEmpty()) return false;
                try {
                    var field = dev.scathiard.feedmepackages.network.PanelNetwork.class.getDeclaredField("cursorReceiver");
                    field.setAccessible(true);
                    realCursorReceiver = (Consumer<PanelPackets.CursorUpdate>) field.get(null);
                    dev.scathiard.feedmepackages.network.PanelNetwork.receiveCursorOnClient(packet -> {
                        if (holdNextUpdate || holdNextGrant && packet.remaining() > 0) { delayedGrant = packet; holdNextGrant = false; holdNextUpdate = false; }
                        else realCursorReceiver.accept(packet);
                    });
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                server(player -> {
                    player.getInventory().setItem(0, ItemStack.EMPTY);
                    player.getInventory().setItem(1, ItemStack.EMPTY);
                    player.inventoryMenu.broadcastFullState();
                    require(stock(player) == 3, "provenance seed");
                });
            }
            case 1 -> clickCellBody(0);
            case 2 -> {
                require(mc.player.containerMenu.getCarried().getCount() == 3, "provenance take3");
                clickCreativeSlot(Items.STONE);
            }
            case 3 -> {
                require(mc.player.containerMenu.getCarried().getCount() == 4, "same-item list increment must keep4");
                server(player -> require(stock(player) == 3 && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 3,
                        "list increment canceled the three preview items"));
                var screen = (CreativeModeInventoryScreen)mc.screen;
                var slot = screen.getMenu().slots.stream().filter(x -> x.container == mc.player.getInventory() && x.getContainerSlot() == 0).findFirst().orElseThrow();
                nativeMouse(screen.getGuiLeft()+slot.x+8, screen.getGuiTop()+slot.y+8, 1, 1);
                nativeMouse(screen.getGuiLeft()+slot.x+8, screen.getGuiTop()+slot.y+8, 1, 0);
            }
            case 4 -> {
                require(mc.player.containerMenu.getCarried().getCount() == 3, "right placement must leave preview3");
                server(player -> require(stock(player) == 3 && player.getInventory().getItem(0).getCount() == 1, "real-first placement charged cache"));
                mc.player.closeContainer();
            }
            case 5 -> {
                server(player -> require(stock(player) == 3 && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0, "mixed close changed stock"));
                require(mc.player.containerMenu.getCarried().isEmpty(), "mixed close left preview");
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
            }
            case 6 -> { if (LogisticsPanel.visibleCells(mc.screen).isEmpty()) return false; clickCellBody(0); }
            case 7 -> { require(mc.player.containerMenu.getCarried().getCount() == 3, "second take3"); clickCreativeSlot(Items.STONE); }
            case 8 -> { require(mc.player.containerMenu.getCarried().getCount() == 4, "second list increment"); placeIntoBackpack(1); }
            case 9 -> {
                server(player -> {
                    require(stock(player) == 0 && player.getInventory().getItem(1).getCount() == 4, "whole mixed placement must charge3 and place4");
                    var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer());
                    var record = ledger.find(access.handle().cacheId()); var edit = record.state().edit();
                    edit.insert(0, record.state().cells().getFirst().filter(), 3);
                    ledger.replace(access.handle(), record.state().revision(), record.withState(edit.finish()));
                });
                FeedMePackages.LOGGER.info("FMP_CREATIVE_MIXED_SOURCE_PASSED right-real-first close whole-place4");
            }
            case 10 -> { holdNextGrant = true; clickCellBody(0); }
            case 11 -> {
                if (delayedGrant == null) return false;
                require(mc.player.containerMenu.getCarried().isEmpty(), "withheld grant wrote the cursor anyway");
                mc.player.closeContainer();
            }
            case 12 -> mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
            case 13 -> { if (LogisticsPanel.visibleCells(mc.screen).isEmpty()) return false; clickCreativeSlot(Items.STONE); }
            case 14 -> {
                var independent = mc.player.containerMenu.getCarried().copy();
                require(independent.is(Items.STONE) && independent.getCount() == 1, "new window independent cursor");
                // This is the real decoded S2C grant captured before application, not a made-up snapshot.
                realCursorReceiver.accept(delayedGrant);
                require(ItemStack.matches(mc.player.containerMenu.getCarried(), independent), "old grant overwrote new window cursor");
                server(player -> require(stock(player) == 3 && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0, "late grant revived reservation"));
            }
            case 15 -> {
                FeedMePackages.LOGGER.info("FMP_CREATIVE_DELAYED_GRANT_PASSED actual-S2C withheld close new-window independent1");
                delayedGrant = null; holdNextUpdate = true;
                clickCellBody(0); // deposit the independent1, withhold its real completion reply
            }
            case 16 -> {
                if (delayedGrant == null) return false;
                server(player -> require(stock(player) == 4, "delayed deposit did not commit once"));
                mc.player.closeContainer();
            }
            case 17 -> {
                realCursorReceiver.accept(delayedGrant);
                require(mc.player.containerMenu.getCarried().isEmpty(), "closed pending deposit left a duplicate real item");
                server(player -> require(stock(player) == 4, "closed pending deposit changed cache twice"));
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
            }
            case 18 -> {
                FeedMePackages.LOGGER.info("FMP_CREATIVE_DELAYED_DEPOSIT_PASSED actual-S2C withheld commit close reconcile");
                server(player -> {
                    var foreign = new ItemStack(Items.DIRT);
                    foreign.set(DataComponents.CUSTOM_NAME, Component.literal("large-native-" + "x".repeat(1800)));
                    player.getInventory().setItem(2, foreign); player.inventoryMenu.broadcastFullState();
                });
            }
            case 19 -> { if (LogisticsPanel.visibleCells(mc.screen).isEmpty()) return false; clickCellBody(0); }
            case 20 -> { require(mc.player.containerMenu.getCarried().getCount() == 4, "foreign swap take4"); placeIntoBackpack(2); }
            case 21 -> {
                var foreign = mc.player.containerMenu.getCarried();
                require(foreign.is(Items.DIRT) && foreign.getCount() == 1
                        && foreign.getHoverName().getString().equals("large-native-" + "x".repeat(1800)), "foreign native components changed or failed cache codec");
                server(player -> require(stock(player) == 0 && player.getInventory().getItem(2).is(Items.STONE)
                        && player.getInventory().getItem(2).getCount() == 4, "foreign native swap settlement"));
            }
            case 22 -> {
                dev.scathiard.feedmepackages.network.PanelNetwork.receiveCursorOnClient(realCursorReceiver);
                FeedMePackages.LOGGER.info("FMP_CREATIVE_FOREIGN_CURSOR_PASSED native-swap oversized-components preserved");
                mc.player.containerMenu.setCarried(ItemStack.EMPTY); // verified scenario handoff only
                return true;
            }
            default -> throw new IllegalStateException("Unexpected provenance stage");
        }
        provenanceStage++; provenanceChanged=ticks; return false;
    }

    private static void creativeHotbarClick() {
        var mc = Minecraft.getInstance(); var screen = (CreativeModeInventoryScreen)mc.screen;
        var slot = screen.getMenu().slots.stream().filter(s -> s.container == mc.player.getInventory() && s.getContainerSlot() == 0).findFirst().orElseThrow();
        click(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8);
    }
    /** Real native click that drops the held cursor stack into a backpack slot; settles the t50 reservation. */
    private static void placeIntoBackpack(int containerSlot) {
        var mc = Minecraft.getInstance();
        var screen = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) mc.screen;
        var slot = screen.getMenu().slots.stream()
                .filter(s -> s.container == mc.player.getInventory() && s.getContainerSlot() == containerSlot)
                .findFirst().orElseThrow();
        click(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8);
    }
    private static int layoutStage, layoutIndex, layoutChanged;
    private static final Set<Integer> seenSlots = new HashSet<>();
    private static PanelLayout.Rect lastCell;
    private static ItemStack layoutItem(int index) {
        var item = new ItemStack(Items.STONE);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Layout specimen " + index));
        return item;
    }
    /** Exercise actual render sizes and event routing, not only the pure layout calculation. */
    private static boolean reviewLayouts() {
        var mc = Minecraft.getInstance(); int scale = layoutIndex % 4 + 1;
        switch (layoutStage) {
            case 0 -> {
                if (layoutIndex == 8) {
                    mc.setScreen(null); mc.options.guiScale().set(2); mc.getWindow().setWindowed(854, 480); mc.resizeDisplay();
                    layoutStage = 100; return true;
                }
                mc.setScreen(null);
                if (layoutIndex == 0) mc.getWindow().setWindowed(1280, 960);
                server(player -> {
                    player.setGameMode(layoutIndex < 4 ? GameType.SURVIVAL : GameType.CREATIVE);
                    player.getInventory().clearContent(); player.containerMenu.setCarried(ItemStack.EMPTY);
                    CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                    var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(handle.cacheId());
                    var edit = before.state().edit(); for (int level = 1; level < 5; level++) edit.upgrade();
                    for (int index = 0; index < 36; index++) edit.filter(index, ItemVariantKey.of(layoutItem(index), player.registryAccess()));
                    ledger.replace(handle, before.state().revision(), before.withState(edit.finish())); player.containerMenu.broadcastFullState();
                }); nextLayout();
            }
            case 1 -> {
                if (ticks - layoutChanged < 15) break;
                mc.options.guiScale().set(scale); mc.resizeDisplay(); mc.setScreen(new InventoryScreen(mc.player));
                seenSlots.clear(); nextLayout();
            }
            case 2 -> {
                if (ticks - layoutChanged < 25) break;
                require(mc.getWindow().getGuiScale() == scale, "Requested GUI scale was clamped, not actually tested: " + scale);
                require(!LogisticsPanel.visibleCells(mc.screen).isEmpty(), "Scaled inventory cannot access its cache");
                checkPanelBounds(); nextLayout();
            }
            case 3 -> {
                if (ticks - layoutChanged < 2) break;
                var targets = LogisticsPanel.visibleCells(mc.screen); targets.forEach(cell -> seenSlots.add(cell.slot()));
                var last = targets.stream().filter(cell -> cell.slot() == 35).findFirst();
                if (last.isPresent()) {
                    require(seenSlots.size() == 36, "Some cache cells cannot be reached by scrolling"); lastCell = last.get().bounds();
                    if (layoutIndex < 4) server(player -> { player.containerMenu.setCarried(layoutItem(35).copyWithCount(7)); player.containerMenu.broadcastFullState(); });
                    else mc.player.containerMenu.setCarried(layoutItem(35).copyWithCount(7));
                    nextLayout();
                } else {
                    var b = LogisticsPanel.exclusions(mc.screen).getFirst();
                    var scroll = new ScreenEvent.MouseScrolled.Pre(mc.screen, b.x() + 12, b.y() + 35, 0, -1);
                    NeoForge.EVENT_BUS.post(scroll);
                    require(scroll.isCanceled(), "Cache scroll leaked into the native inventory"); layoutChanged = ticks;
                }
            }
            case 4 -> { if (ticks - layoutChanged >= 20) { nativeMouse(lastCell.x() + 8, lastCell.y() + 8, 1, 1); nextLayout(); } }
            case 5 -> {
                if (ticks - layoutChanged < 20) break;
                require(mc.player.containerMenu.getCarried().getCount() == 6, "Right press did not leave six on the cursor");
                server(player -> require(layoutStock(player) == 1, "Right press did not deposit exactly one"));
                nativeMouse(lastCell.x() + 8, lastCell.y() + 8, 1, 0); nextLayout();
            }
            case 6 -> {
                if (ticks - layoutChanged < 20) break;
                require(mc.player.containerMenu.getCarried().getCount() == 6, "Late right release deposited twice");
                server(player -> require(layoutStock(player) == 1, "Late right release changed the server cache"));
                nativeMouse(lastCell.x() + 8, lastCell.y() + 8, 0, 1);
                nativeMouse(lastCell.x() + 8, lastCell.y() + 8, 0, 0); nextLayout();
            }
            case 7 -> {
                if (ticks - layoutChanged < 20) break;
                require(mc.player.containerMenu.getCarried().isEmpty(), "Last-cell deposit left the cursor unchanged");
                server(player -> require(layoutStock(player) == 7, "Last-cell click did not deposit into cell 36"));
                capture("layout-" + layoutIndex + "-scale" + scale + "-bottom");
                click(lastCell.x() + 8, lastCell.y() + 8); nextLayout();
            }
            case 8 -> {
                if (ticks - layoutChanged < 20) break;
                require(ItemStack.matches(mc.player.containerMenu.getCarried(), layoutItem(35).copyWithCount(7)), "Last-cell withdrawal lost amount or components");
                server(player -> require(layoutStock(player) == 7
                        && CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 35) == 7,
                        "Last-cell take must reserve seven without committing stock"));
                click(lastCell.x() + 8, lastCell.y() + 8); nextLayout();
            }
            case 9 -> {
                if (ticks - layoutChanged < 20) break;
                require(mc.player.containerMenu.getCarried().isEmpty(), "Last-cell redeposit did not clear cursor");
                click(lastCell.x() + 15, lastCell.y() + 3); nextLayout();
            }
            case 10 -> {
                if (ticks - layoutChanged < 10) break;
                checkPanelBounds(); capture("layout-" + layoutIndex + "-scale" + scale + "-slider");
                if (mc.screen instanceof InventoryScreen inventory) {
                    click(inventory.getGuiLeft() + 114, inventory.height / 2 - 13); nextLayout();
                } else { layoutStage = 12; layoutChanged = ticks; }
            }
            case 11 -> {
                if (ticks - layoutChanged < 15) break;
                var inventory = (InventoryScreen) mc.screen;
                require(inventory.getRecipeBookComponent().isVisible(), "Recipe book failed to open at scale " + scale);
                var b = LogisticsPanel.exclusions(mc.screen).getFirst(); int bookX = (inventory.width - 147) / 2 - (inventory.width < 379 ? 0 : 86);
                require(b.x() + b.width() <= bookX || b.x() >= bookX + 147, "Scaled panel overlaps recipe book");
                if (b.width() == 18) click(b.x() + 8, b.y() + 8);
                else click(inventory.getGuiLeft() + 114, inventory.height / 2 - 13);
                nextLayout();
            }
            case 12 -> {
                if (ticks - layoutChanged < 15) break;
                if (mc.screen instanceof InventoryScreen inventory) require(!inventory.getRecipeBookComponent().isVisible(), "Compact expand did not close obstructing book");
                checkPanelBounds(); require(!panelLayout().compact() && !LogisticsPanel.visibleCells(mc.screen).isEmpty(), "Panel cannot expand after book closure");
                FeedMePackages.LOGGER.info("FMP_CLIENT_LAYOUT_PASSED mode={} scale={} framebuffer={}x{} cells=36 real-deposit-withdrawal", layoutIndex < 4 ? "survival" : "creative", scale, mc.getWindow().getWidth(), mc.getWindow().getHeight());
                layoutIndex++; layoutStage = 0; layoutChanged = ticks;
            }
            case 100 -> { return true; }
            default -> throw new IllegalStateException("Unexpected layout stage");
        }
        return false;
    }
    private static void nextLayout() { layoutStage++; layoutChanged = ticks; }
    private static int clickOutsideStage, clickOutsideChanged;
    /** Real click-routing for the test.57 "click outside" behaviours: addr blur-commit and slider
     *  collapse, all through the actual panel event listeners and the server snapshot. */
    private static boolean reviewClickOutside() {
        var mc = Minecraft.getInstance();
        if (clickOutsideStage != 0 && ticks - clickOutsideChanged < 15) return false;
        switch (clickOutsideStage) {
            case 0 -> {
                // Layout review leaves the player in creative; click-outside reads inventory slots and
                // must not create/destroy items, so return to survival with a clean cursor first.
                server(player -> {
                    player.setGameMode(GameType.SURVIVAL);
                    player.containerMenu.setCarried(ItemStack.EMPTY);
                    player.getInventory().clearContent();
                    player.getInventory().setChanged();
                });
                mc.setScreen(new InventoryScreen(mc.player)); clickOutsideChanged = ticks; clickOutsideStage++;
            }
            case 1 -> {
                if (LogisticsPanel.visibleCells(mc.screen).isEmpty()) return false;
                // Open cell 0's slider.
                clickCellDot(0); clickOutsideStage++;
            }
            case 2 -> {
                if (panelLayout().slider() == null) return false;
                var snapshot = panelSnapshot(); var min = snapshot.cells().get(0).minimum(); var max = snapshot.cells().get(0).maximum();
                // Click outside the slider but still inside the panel (e.g. a neighbouring cell body would
                // deposit; use the panel's empty corner instead). The dot of cell 1 keeps selection when
                // clicked, so we click a non-dot cell area to force a pure collapse without a transfer.
                var bounds = LogisticsPanel.exclusions(mc.screen).getFirst();
                click(bounds.x() + bounds.width() - 5, bounds.y() + bounds.height() - 5);
                require(panelLayout().slider() == null, "Click outside the slider did not collapse it");
                var after = panelSnapshot();
                require(after.cells().get(0).minimum() == min && after.cells().get(0).maximum() == max,
                        "Collapse changed the cell thresholds: " + min + "/" + max + " -> " + after.cells().get(0).minimum() + "/" + after.cells().get(0).maximum());
                clickOutsideStage++;
            }
            case 3 -> {
                // Enter address edit, type a fresh value, then click outside the panel (over vanilla
                // inventory) and verify the draft committed and editing ended.
                var bar = panelLayout().returnBar();
                click(bar.x() + 4, bar.y() + bar.height() / 2);
                require(returnEditing(), "Click on the return bar did not begin editing");
                int codePoint = 'A'; var typed = new ScreenEvent.CharacterTyped.Pre(mc.screen, (char) codePoint, 0);
                NeoForge.EVENT_BUS.post(typed); require(typed.isCanceled(), "Address typing leaked to the vanilla edit box");
                clickOutsideStage++;
            }
            case 4 -> {
                require(returnEditing(), "Address draft was lost before the outside click");
                // Outside the panel: over a vanilla inventory slot of the (possibly FMP-replaced) screen.
                var container = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) mc.screen;
                click(container.getGuiLeft() + 8, container.getGuiTop() + 8);
                require(!returnEditing(), "Click outside the panel did not end address editing");
                clickOutsideStage++;
            }
            case 5 -> {
                // Let the SET_RETURN_ADDRESS land, then assert the server stored it (starts with our A).
                var address = serverReturnAddress();
                if (address == null) return false;
                require(address.endsWith("A"), "Outside click did not commit the return address draft: " + address);
                FeedMePackages.LOGGER.info("FMP_CLICK_OUTSIDE_PASSED address={} sliderCollapse=true", address);
                clickOutsideStage++;
            }
            case 6 -> {
                mc.player.closeContainer(); mc.setScreen(null);
                return true;
            }
            default -> throw new IllegalStateException("Unexpected click-outside stage");
        }
        clickOutsideChanged = ticks; return false;
    }
    private static String serverReturnAddress() {
        try {
            var mc = Minecraft.getInstance(); var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
            var future = CompletableFuture.supplyAsync(() -> {
                var player = server.getPlayerList().getPlayer(id);
                return dev.scathiard.feedmepackages.storage.CacheLedger.get(player.getServer()).returnAddress(
                        dev.scathiard.feedmepackages.service.AccessGate.resolve(player).handle().cacheId());
            }, server);
            return future.join();
        } catch (Throwable failure) { return null; }
    }
    private static boolean returnEditing() {
        try { var f = LogisticsPanel.class.getDeclaredField("returnEditing"); f.setAccessible(true); return f.getBoolean(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static void checkPanelBounds() {
        var screen = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) Minecraft.getInstance().screen;
        var b = LogisticsPanel.exclusions(screen).getFirst();
        require(b.x() >= 0 && b.y() >= 0 && b.x() + b.width() <= screen.width && b.y() + b.height() <= screen.height, "Panel extends beyond the actual viewport");
        require(b.x() + b.width() <= screen.getGuiLeft(), "Panel overlaps native inventory slots");
        for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible)
            require(b.x() + b.width() <= widget.getX() || b.x() >= widget.getX() + widget.getWidth()
                            || b.y() + b.height() <= widget.getY() || b.y() >= widget.getY() + widget.getHeight(),
                    "Panel overlaps an existing visible screen control: " + widget.getClass().getName());
        if (ModList.get().isLoaded("jei")) jei("verifyReservedControls", null);
    }
    private static int layoutStock(ServerPlayer player) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().get(35).amount();
    }
    private static void server(Consumer<ServerPlayer> operation) {
        var mc = Minecraft.getInstance(); var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
        work = CompletableFuture.runAsync(() -> operation.accept(server.getPlayerList().getPlayer(id)), server);
    }
    private static void jei(String method, ServerPlayer player) {
        try {
            var type = Class.forName("dev.scathiard.feedmepackages.gametest.JeiClientReview");
            if (player == null) type.getMethod(method).invoke(null); else type.getMethod(method, ServerPlayer.class).invoke(null, player);
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException("JEI client review failed at " + method, failed); }
    }
    private static int stock(ServerPlayer player) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().getFirst().amount();
    }
    private static void nativeMouse(double x, double y, int button, int action) {
        try {
            var mc = Minecraft.getInstance(); var window = mc.getWindow();
            var move = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
            move.setAccessible(true); move.invoke(mc.mouseHandler, window.getWindow(), x * window.getScreenWidth() / window.getGuiScaledWidth(), y * window.getScreenHeight() / window.getGuiScaledHeight());
            var press = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onPress", long.class, int.class, int.class, int.class);
            press.setAccessible(true); press.invoke(mc.mouseHandler, window.getWindow(), button, action, 0);
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException("Native mouse callback failed", failed); }
    }
    static void click(double x, double y) {
        var screen = Minecraft.getInstance().screen;
        var press = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 0); NeoForge.EVENT_BUS.post(press);
        if (!press.isCanceled()) screen.mouseClicked(x, y, 0);
        var release = new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, 0); NeoForge.EVENT_BUS.post(release);
        if (!release.isCanceled()) screen.mouseReleased(x, y, 0);
    }
    /** Panel geometry + client snapshot from reflection, matching the driver's other helpers. */
    private static PanelLayout panelLayout() {
        try { var f = LogisticsPanel.class.getDeclaredField("layout"); f.setAccessible(true); return (PanelLayout) f.get(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static PanelPackets.Snapshot panelSnapshot() {
        try { var f = LogisticsPanel.class.getDeclaredField("snapshot"); f.setAccessible(true); return (PanelPackets.Snapshot) f.get(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> panelScreen() {
        return (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) Minecraft.getInstance().screen;
    }
    /** A held-button press that the panel's own Pre listener routes (cancels the native click when it owns the region). */
    private static void panelPress(double x, double y) {
        var screen = Minecraft.getInstance().screen;
        var press = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 0); NeoForge.EVENT_BUS.post(press);
        if (!press.isCanceled()) screen.mouseClicked(x, y, 0);
    }
    private static void panelDrag(double x, double y) {
        var drag = new ScreenEvent.MouseDragged.Pre(Minecraft.getInstance().screen, x, y, 0, 0, 0); NeoForge.EVENT_BUS.post(drag);
    }
    private static void panelRelease(double x, double y) {
        var screen = Minecraft.getInstance().screen;
        var release = new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, 0); NeoForge.EVENT_BUS.post(release);
        if (!release.isCanceled()) screen.mouseReleased(x, y, 0);
    }
    /** Select a cache cell by its dot so its threshold slider opens, using the real panel event. */
    private static void clickCellDot(int slot) {
        var target = LogisticsPanel.visibleCells(Minecraft.getInstance().screen).stream().filter(c -> c.slot() == slot).findFirst().orElseThrow();
        var d = target.dot(); click(d.x() + 1, d.y() + 2);
    }
    /** Click a cache cell's body (not its dot) so an empty cursor does a TAKE_CURSOR and a held one a DEPOSIT. */
    private static void clickCellBody(int slot) {
        var bounds = LogisticsPanel.visibleCells(Minecraft.getInstance().screen).stream().filter(c -> c.slot() == slot).findFirst().orElseThrow().bounds();
        click(bounds.x() + 8, bounds.y() + 8);
    }
    /** Press the open cell slider's minimum handle and drag it to the track midpoint (value 16 groups at cap 32). */
    private static void dragMinimumToMidpoint(int slot) {
        var slider = panelLayout().slider(); if (slider == null) throw new IllegalStateException("Slider did not open for cell " + slot);
        int groupCap = panelSnapshot().groupCapacity();
        int minimum = Math.max(0, panelSnapshot().cells().get(slot).minimum());
        // Hit the actual artwork endpoint (shared mapping) so press/drag math matches the rendered thumb.
        int minAt = PanelLayout.thumbPx(slider.x(), slider.width(), minimum, groupCap);
        int bandY = slider.y() + PanelLayout.MIN_THUMB_Y + 2;
        int targetX = slider.x() + PanelLayout.TRACK_INSET + (slider.width() - 2 * PanelLayout.TRACK_INSET) / 2;
        panelPress(minAt, bandY); panelDrag(targetX, bandY); panelRelease(targetX, bandY);
    }
    static void capture(String name) {
        var mc = Minecraft.getInstance(); Screenshot.grab(mc.gameDirectory, "fmp-" + RUN + "-" + name + ".png", mc.getMainRenderTarget(),
                message -> FeedMePackages.LOGGER.info("FMP_CLIENT_SCREENSHOT {}", message.getString()));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void advance() { phase++; changed = ticks; FeedMePackages.LOGGER.info("FMP_CLIENT_REVIEW_PHASE {}", phase); }
}
