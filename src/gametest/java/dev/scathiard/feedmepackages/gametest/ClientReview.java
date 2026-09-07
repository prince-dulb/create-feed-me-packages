package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.client.PanelLayout;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
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
                    if (ticks - changed < 20) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "Survival deposit did not clear real cursor");
                    server(player -> require(stock(player) == 64, "Survival UI deposit did not reach server cache"));
                    var bounds = LogisticsPanel.exclusions(mc.screen).getFirst(); capture("01-survival");
                    click(bounds.x() + 12, bounds.y() + 34); advance();
                }
                case 6 -> {
                    if (ticks - changed < 20) return;
                    require(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 64, "Survival withdrawal lost cursor stack");
                    server(player -> require(stock(player) == 0, "Survival UI withdrawal did not deduct cache"));
                    var bounds = LogisticsPanel.exclusions(mc.screen).getFirst(); click(bounds.x() + 12, bounds.y() + 34); advance();
                }
                case 7 -> {
                    if (ticks - changed < 20) return;
                    server(player -> {
                        require(stock(player) == 64, "Second deposit failed");
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
                    if (ticks - changed < 25) return;
                    var bounds = LogisticsPanel.exclusions(mc.screen).getFirst(); click(bounds.x() + 19, bounds.y() + 27); advance();
                }
                case 9 -> {
                    if (ticks - changed < 10) return;
                    capture("02-expanded-level5");
                    var bounds = LogisticsPanel.exclusions(mc.screen).getFirst(); click(bounds.x() + 20, bounds.y() + 46); advance();
                }
                case 10 -> {
                    if (ticks - changed < 20) return;
                    server(player -> {
                        var record = CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId());
                        require(record.state().cells().getFirst().minimum() == 1024, "Slider intent did not reach server threshold");
                        player.setGameMode(GameType.CREATIVE);
                    }); mc.setScreen(null); advance();
                }
                case 11 -> { if (ticks - changed < 10) return; mc.setScreen(new InventoryScreen(mc.player)); advance(); }
                case 12 -> {
                    if (ticks - changed < 25 || !(mc.screen instanceof CreativeModeInventoryScreen) || LogisticsPanel.exclusions(mc.screen).isEmpty()) return;
                    mc.player.containerMenu.setCarried(new ItemStack(Items.STONE, 3));
                    var bounds = LogisticsPanel.exclusions(mc.screen).getFirst(); click(bounds.x() + 12, bounds.y() + 34); advance();
                }
                case 13 -> {
                    if (ticks - changed < 20) return;
                    require(mc.player.containerMenu.getCarried().isEmpty(), "Creative full-state sync did not update cursor");
                    server(player -> require(stock(player) == 67, "Creative UI deposit did not reach cache")); capture("03-creative");
                    var bounds = LogisticsPanel.exclusions(mc.screen).getFirst(); click(bounds.x() + 12, bounds.y() + 34); advance();
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
    private static int creativeStage, creativeChanged;
    private static boolean reviewCreativePlacement() {
        var mc = Minecraft.getInstance();
        if (creativeStage != 0 && ticks - creativeChanged < 20) return false;
        switch (creativeStage) {
            case 0 -> {
                require(mc.player.containerMenu.getCarried().is(Items.STONE) && mc.player.containerMenu.getCarried().getCount() == 64, "Creative withdrawal cursor failed");
                server(player -> require(stock(player) == 3 && player.containerMenu.getCarried().isEmpty(), "Creative handoff retained a second server cursor"));
                creativeHotbarClick();
            }
            case 1 -> {
                require(mc.player.containerMenu.getCarried().isEmpty() && mc.player.getInventory().getItem(0).getCount() == 64, "Native creative placement failed");
                mc.player.closeContainer();
            }
            case 2 -> {
                server(player -> require(player.getInventory().items.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum() == 64
                        && stock(player) == 3 && player.containerMenu.getCarried().isEmpty(), "Creative close duplicated or lost the actual withdrawal"));
                mc.setScreen(new InventoryScreen(mc.player));
            }
            case 3 -> {
                require(mc.screen instanceof CreativeModeInventoryScreen && mc.player.containerMenu.getCarried().isEmpty()
                        && mc.player.getInventory().getItem(0).getCount() == 64, "Creative reopening changed native inventory ownership");
                creativeHotbarClick();
            }
            case 4 -> {
                require(mc.player.containerMenu.getCarried().getCount() == 64 && mc.player.getInventory().getItem(0).isEmpty(), "Native creative pickup failed");
                var bounds = LogisticsPanel.exclusions(mc.screen).getFirst(); click(bounds.x() + 12, bounds.y() + 34);
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
    static void capture(String name) {
        var mc = Minecraft.getInstance(); Screenshot.grab(mc.gameDirectory, "fmp-" + RUN + "-" + name + ".png", mc.getMainRenderTarget(),
                message -> FeedMePackages.LOGGER.info("FMP_CLIENT_SCREENSHOT {}", message.getString()));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void advance() { phase++; changed = ticks; FeedMePackages.LOGGER.info("FMP_CLIENT_REVIEW_PHASE {}", phase); }
}
