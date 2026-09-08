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

/** Explicit opt-in, test-source-only driver. Uses real client/server networking and container event routing. */
@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientReview {
    private static int phase, ticks, changed;
    private static CompletableFuture<Void> work;
    private static final String RUN = Long.toString(System.currentTimeMillis());
    private static boolean failed;
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        if (Boolean.getBoolean("fmp.clientReview")) event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(ClientReview::tick));
    }
    private static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance(); ticks++;
        try {
            if (work != null) { if (!work.isDone()) return; work.join(); work = null; }
            if (ticks - changed > 2400) throw new IllegalStateException("Client review timed out in phase " + phase);
            if (Boolean.getBoolean("fmp.uiPlacementReview")) {
                reviewUiPlacement();
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
                    if (ticks - changed < 20 || !reviewCreativePlacement()) return;
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
            case 2 -> { // cancel must not deduct; reservation cleared
                server(player -> {
                    require(stock(player) == 3, "Creative cancel must not deduct cache");
                    require(CursorReservations.reserved(AccessGate.resolve(player).handle().cacheId(), 0) == 0, "Creative cancel must clear reservation");
                });
                mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
            }
            case 3 -> {
                require(mc.screen instanceof CreativeModeInventoryScreen, "Creative reopen did not restore the creative screen");
                mc.player.containerMenu.setCarried(ItemStack.EMPTY); // creative cursor persists client-side; clear for the next scenario
                return true;
            }
            default -> throw new IllegalStateException("Unexpected creative review stage");
        }
        creativeStage++; creativeChanged = ticks; return false;
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
                server(player -> require(layoutStock(player) == 0, "Last-cell withdrawal did not deduct exactly seven"));
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
                checkPanelBounds(); require(LogisticsPanel.exclusions(mc.screen).getFirst().width() == PanelLayout.WIDTH, "Panel cannot expand after book closure");
                FeedMePackages.LOGGER.info("FMP_CLIENT_LAYOUT_PASSED mode={} scale={} framebuffer={}x{} cells=36 real-deposit-withdrawal", layoutIndex < 4 ? "survival" : "creative", scale, mc.getWindow().getWidth(), mc.getWindow().getHeight());
                layoutIndex++; layoutStage = 0; layoutChanged = ticks;
            }
            case 100 -> { return true; }
            default -> throw new IllegalStateException("Unexpected layout stage");
        }
        return false;
    }
    private static void nextLayout() { layoutStage++; layoutChanged = ticks; }
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
        int minAt = slider.x() + PanelLayout.TRACK_INSET + (minimum * (slider.width() - 2 * PanelLayout.TRACK_INSET) / Math.max(1, groupCap));
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
