package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import top.theillusivec4.curios.api.CuriosApi;
import net.neoforged.fml.ModList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Test source only: real native screen clicks, menu packets, result sync and Curios tick claim. */
final class ClientGrowthReview {
    private static int stage, ticks, changed, cycle;
    private static CompletableFuture<Void> work;
    private static UUID cacheId, network;
    private static dev.scathiard.feedmepackages.storage.CacheRecord travelRecord;
    private static final int[] TARGETS = {2, 0, 3};
    static boolean tick() {
        ticks++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        var mc = Minecraft.getInstance();
        switch (stage) {
            case 0 -> { mc.player.closeContainer(); next(); }
            case 1 -> {
                if (!waited(20)) break;
                server(player -> {
                    player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.containerMenu.setCarried(ItemStack.EMPTY);
                    var pendant = FmpRegistries.PENDANT.toStack(); network = UUID.randomUUID(); pendant.set(FmpRegistries.NETWORK.get(), network);
                    pendant.set(DataComponents.CUSTOM_NAME, Component.literal("Assembly review"));
                    var necklace = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks();
                    necklace.setStackInSlot(0, pendant); var access = AccessGate.resolve(player); cacheId = access.handle().cacheId();
                    var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(cacheId); var edit = before.state().edit();
                    var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()); edit.filter(0, key); edit.insert(0, key, 37);
                    ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
                    necklace.setStackInSlot(0, ItemStack.EMPTY); player.getInventory().setItem(0, pendant); player.getInventory().setItem(1, FmpRegistries.ASSEMBLY_TEMPLATE.toStack());
                    open(player);
                }); next();
            }
            case 2 -> {
                if (!waited(20) || !(mc.screen instanceof SmithingScreen)) break;
                require(LogisticsPanel.exclusions(mc.screen).isEmpty(), "Unworn manufacturing exposed cache access");
                clickSlot(31); clickSlot(1); clickSlot(32); clickSlot(0); clickSlot(33); clickSlot(2); next();
            }
            case 3 -> {
                if (!waited(20)) break;
                var menu = mc.player.containerMenu;
                require(menu instanceof SmithingMenu && menu.getSlot(3).getItem().getOrDefault(FmpRegistries.PREVIEW.get(), false), "Native smithing result preview did not sync");
                server(player -> {
                    var record = CacheLedger.get(player.getServer()).find(cacheId);
                    require(record.state().level() == (cycle == 0 ? 1 : 2) && record.state().cells().getFirst().amount() == 37, "A client recipe preview changed real stock or level");
                });
                ClientReview.capture("growth-" + cycle + "-preview"); clickSlot(3); next();
            }
            case 4 -> {
                if (!waited(20)) break;
                var result = mc.player.containerMenu.getCarried();
                require(result.is(cycle == 0 ? FmpRegistries.PENDANT.get() : FmpRegistries.PERSONAL_PENDANT.get())
                        && !result.has(FmpRegistries.PREVIEW.get()) && network.equals(result.get(FmpRegistries.NETWORK.get()))
                        && result.getHoverName().getString().equals("Assembly review"), "Real result click failed to sync its usable result and original components");
                server(player -> {
                    var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(cacheId); var menu = player.containerMenu;
                    require(record.state().level() == 2 && record.state().cells().getFirst().amount() == 37, "Client manufacturing lost stock or applied the wrong level");
                    require(menu.getSlot(0).getItem().is(FmpRegistries.ASSEMBLY_TEMPLATE.get()) && menu.getSlot(1).getItem().isEmpty() && menu.getSlot(2).getItem().isEmpty(), "Client manufacturing did not consume exactly its two inputs");
                    if (cycle > 0) require(player.getUUID().equals(record.owner()) && cacheId.equals(ledger.personal(player.getUUID())), "Client personalization did not commit ownership");
                    if (cycle == 2) require(player.getUUID().equals(record.owner()) && record.state().level() == 2, "Client private upgrade did not commit to its owner");
                }); clickSlot(31); next();
            }
            case 5 -> { if (!waited(15)) break; mc.player.closeContainer(); next(); }
            case 6 -> {
                if (!waited(20)) break;
                server(player -> {
                    require(player.getInventory().countItem(FmpRegistries.ASSEMBLY_TEMPLATE.get()) == 1, "Closing client smithing duplicated or lost its reusable template");
                    require(player.getInventory().getItem(0).is(cycle == 0 ? FmpRegistries.PENDANT.get() : FmpRegistries.PERSONAL_PENDANT.get()), "Client return click lost the manufactured pendant");
                }); next();
            }
            case 7 -> {
                cycle++;
                if (cycle < TARGETS.length) { server(ClientGrowthReview::open); stage = 2; changed = ticks; }
                else {
                    server(player -> {
                        var pendant = player.getInventory().removeItemNoUpdate(0);
                        require(CacheLedger.get(player.getServer()).find(cacheId).state().level() == 2, "Inventory-only grant activated before wear");
                        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().setStackInSlot(0, pendant);
                    }); next();
                }
            }
            case 8 -> {
                if (!waited(20)) break;
                server(player -> {
                    var record = CacheLedger.get(player.getServer()).find(cacheId); var pendant = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks().getStackInSlot(0);
                    require(record.state().level() == 3 && record.state().cells().getFirst().amount() == 37 && player.getUUID().equals(pendant.get(FmpRegistries.OWNER.get())), "Owned personal upgrade was not reflected after wear");
                }); mc.setScreen(new InventoryScreen(mc.player)); next();
            }
            case 9 -> {
                if (!waited(20)) break;
                require(!LogisticsPanel.exclusions(mc.screen).isEmpty(), "Upgraded personal cache did not return to the real inventory panel");
                ClientReview.capture("growth-personal-level3"); FeedMePackages.LOGGER.info("FMP_CLIENT_GROWTH_PASSED native-input-clicks/preview/result/close/private-claim stock=37 level=3"); next();
            }
            case 10 -> { if (!ModList.get().isLoaded("jei")) { stage = 14; changed = ticks; break; } jei("openGrowthSmithing"); next(); }
            case 11 -> {
                if (!waited(20)) break; jei("verifyGrowthPage"); ClientReview.capture("growth-jei-smithing"); mc.screen.onClose(); next();
            }
            case 12 -> { if (!waited(10)) break; jei("openGrowthSequence"); next(); }
            case 13 -> {
                if (!waited(20)) break; jei("verifyGrowthPage"); ClientReview.capture("growth-jei-sequence5"); mc.screen.onClose();
                server(player -> require(CacheLedger.get(player.getServer()).find(cacheId).state().cells().getFirst().amount() == 37, "JEI growth preview changed the real stock"));
                FeedMePackages.LOGGER.info("FMP_CLIENT_GROWTH_JEI_PASSED registered-smithing=9 actual-smithing+sequence-pages"); next();
            }
            case 14 -> {
                mc.player.closeContainer();
                server(player -> {
                    travelRecord = CacheLedger.get(player.getServer()).find(cacheId);
                    player.setGameMode(GameType.CREATIVE); player.getAbilities().flying = true; player.onUpdateAbilities();
                    player.teleportTo(player.getServer().getLevel(net.minecraft.world.level.Level.NETHER), 8, 130, 8, 0, 0);
                }); next();
            }
            case 15 -> {
                if (!waited(40) || mc.level == null || !mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER)) break;
                server(ClientGrowthReview::verifyTravel); mc.setScreen(new InventoryScreen(mc.player)); next();
            }
            case 16 -> {
                if (!waited(20)) break; verifyTravelHints(); ClientReview.capture("growth-travel-nether"); mc.player.closeContainer();
                server(player -> {
                    player.teleportTo(player.getServer().overworld(), 5, 121, 5, 0, 0);
                    player.setGameMode(GameType.SURVIVAL);
                }); next();
            }
            case 17 -> {
                if (!waited(40) || mc.level == null || !mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) break;
                server(ClientGrowthReview::verifyTravel); mc.setScreen(new InventoryScreen(mc.player)); next();
            }
            case 18 -> {
                if (!waited(20)) break; verifyTravelHints(); ClientReview.capture("growth-travel-overworld");
                FeedMePackages.LOGGER.info("FMP_CLIENT_DIMENSION_PASSED actual-nether+overworld same-cache/stock/hints/panel"); return true;
            }
            default -> throw new IllegalStateException("Unknown client growth review stage");
        }
        return false;
    }
    private static void open(ServerPlayer player) {
        int target = TARGETS[cycle]; player.getInventory().setItem(2, target == 0 ? FmpRegistries.PRIVATE_LINK.toStack() : FmpRegistries.upgradeLink(target).toStack());
        var table = new BlockPos(6, 121, 5); player.serverLevel().setBlockAndUpdate(table, Blocks.SMITHING_TABLE.defaultBlockState());
        player.teleportTo(5, 121, 5); player.openMenu(Blocks.SMITHING_TABLE.defaultBlockState().getMenuProvider(player.serverLevel(), table));
    }
    private static void verifyTravel(ServerPlayer player) {
        var access = AccessGate.resolve(player);
        require(access.active() && cacheId.equals(access.handle().cacheId()) && CacheLedger.get(player.getServer()).find(cacheId) == travelRecord,
                "Native dimension transfer changed cache identity, stock, thresholds or orders");
    }
    private static void verifyTravelHints() {
        var mc = Minecraft.getInstance();
        require(!LogisticsPanel.exclusions(mc.screen).isEmpty(), "Cache panel did not reopen after native dimension transfer");
        require(dev.scathiard.feedmepackages.client.ClientMaterials.active()
                && dev.scathiard.feedmepackages.client.ClientMaterials.stacks().stream().filter(stack -> stack.is(Items.STONE)).mapToInt(ItemStack::getCount).sum() == 37,
                "Read-only consumption hints disappeared or duplicated after native dimension transfer");
    }
    private static void clickSlot(int index) {
        var screen = (AbstractContainerScreen<?>) Minecraft.getInstance().screen; var slot = screen.getMenu().getSlot(index);
        ClientReview.click(screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8);
    }
    private static void server(Consumer<ServerPlayer> operation) {
        var mc = Minecraft.getInstance(); var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
        work = CompletableFuture.runAsync(() -> operation.accept(server.getPlayerList().getPlayer(id)), server);
    }
    private static boolean waited(int amount) { return ticks - changed >= amount; }
    private static void next() { stage++; changed = ticks; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void jei(String method) {
        try { Class.forName("dev.scathiard.feedmepackages.gametest.JeiClientReview").getMethod(method).invoke(null); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("JEI growth review failed at " + method, failure); }
    }
}
