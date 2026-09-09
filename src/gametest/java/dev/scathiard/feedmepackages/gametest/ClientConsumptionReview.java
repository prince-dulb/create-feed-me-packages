package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Actual client/server packets and engine inputs, only in the explicitly enabled isolated review driver. */
final class ClientConsumptionReview {
    private static int stage, tick, changed;
    private static CompletableFuture<Void> work;
    private static UUID cacheId;
    private static boolean bookOpened;
    static boolean tick() {
        tick++; if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        var mc = Minecraft.getInstance();
        switch (stage) {
            case 0 -> { mc.player.closeContainer(); next(); }
            case 1 -> { if (!waited(20)) break; server(ClientConsumptionReview::setup); next(); }
            case 2 -> {
                if (!waited(20)) break;
                require(ClientMaterials.active() && mc.player.getProjectile(mc.player.getMainHandItem()).is(Items.ARROW), "Closed-GUI client did not see cache-only ammunition");
                mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); next();
            }
            case 3 -> {
                if (!waited(25)) break;
                require(mc.player.isUsingItem(), "Cache-only bow use did not start the client animation");
                mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player); next();
            }
            case 4 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 1) == 7 && ConsumptionTests.stock(p, 0) == 12, "Real bow network shot used wrong quantities"));
                mc.setScreen(new InventoryScreen(mc.player)); next();
            }
            case 5 -> {
                if (!waited(20)) break;
                var screen = (InventoryScreen) mc.screen;
                if (!bookOpened) {
                    if (!screen.getRecipeBookComponent().isVisible()) ClientReview.click(screen.getGuiLeft() + 114, screen.height / 2 - 13);
                    bookOpened = true; changed = tick; break;
                }
                require(screen.getRecipeBookComponent().isVisible(), "Recipe-book button did not keep the real page visible");
                var recipe = recipe();
                boolean craftable = mc.player.getRecipeBook().getCollection(RecipeBookCategories.CRAFTING_SEARCH).stream()
                        .anyMatch(c -> c.getRecipes().stream().anyMatch(r -> r.id().equals(recipe.id())) && c.isCraftable(recipe));
                require(craftable, "Vanilla recipe book cannot see cache-only materials");
                var bookX = (screen.width - 147) / 2 - (screen.width < 379 ? 0 : 86);
                for (var area : LogisticsPanel.exclusions(screen))
                    require(area.x() + area.width() <= bookX || area.x() >= bookX + 147, "Cache panel overlapped the visible recipe book");
                ClientReview.capture("05-recipe-book"); clickBookRecipe(screen, recipe.id()); next();
            }
            case 6 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).getItem(0).is(Items.OAK_LOG), "Native recipe-book preparation consumed cache material"));
                require(mc.player.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "Real recipe result did not sync to client");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 0, 0, ClickType.PICKUP, mc.player); next();
            }
            case 7 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 0) == 11 && p.containerMenu.getCarried().getCount() == 4 && CraftingService.grid(p.containerMenu).getItem(0).is(Items.OAK_LOG), "Client crafting click or preview refill charged extra stock"));
                mc.player.closeContainer(); next();
            }
            case 8 -> {
                if (!waited(20)) break;
                if (!ModList.get().isLoaded("jei")) return true;
                server(ClientConsumptionReview::setup); next();
            }
            case 9 -> { if (!waited(20)) break; mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 10 -> { if (!waited(20)) break; require(LogisticsPanel.recipeReady(), "Recipe transfer session was not ready"); jei("openTransfer"); next(); }
            case 11 -> {
                if (!waited(20)) break;
                jei("previewTransfer"); server(p -> require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).isEmpty(), "JEI previews moved physical material")); next();
            }
            case 12 -> { if (!waited(10)) break; ClientReview.capture("06-jei-recipe"); jei("clickTransfer"); next(); }
            case 13 -> {
                if (!waited(20)) break;
                require(mc.screen instanceof InventoryScreen && mc.player.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "JEI plus did not return to the filled original grid");
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).getItem(0).getCount() == 1, "Actual JEI plus consumed the reserved cached log");
                });
                stage = 20; changed = tick;
            }
            case 20 -> {
                if (!waited(10)) break;
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 0, 0, ClickType.PICKUP, mc.player);
                next();
            }
            case 21 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 0) == 11 && p.containerMenu.getCarried().is(Items.OAK_PLANKS)
                        && p.containerMenu.getCarried().getCount() == 4, "Actual JEI-filled craft did not consume exactly one log"));
                mc.player.closeContainer(); next();
            }
            case 22 -> {
                if (!waited(20)) break;
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 11 && CraftingService.grid(p.containerMenu).isEmpty()
                            && p.getInventory().items.stream().filter(v -> v.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum() == 4
                            && p.getInventory().items.stream().noneMatch(v -> v.is(Items.OAK_LOG)),
                            "Closing after JEI craft materialized unused preparation or lost output");
                    FeedMePackages.LOGGER.info("FMP_JEI_REAL_CRAFT_PASSED cache=11 output=4 unused=0");
                    TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY);
                    p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG)); p.containerMenu.broadcastFullState();
                });
                stage = 14; changed = tick;
            }
            case 14 -> { if (!waited(30)) break; require(!ClientMaterials.active(), "Unwear did not clear client material hints"); mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 15 -> { if (!waited(20)) break; jei("openTransfer"); next(); }
            case 16 -> { if (!waited(20)) break; jei("previewTransfer"); next(); }
            case 17 -> { if (!waited(10)) break; jei("clickTransfer"); next(); }
            case 18 -> {
                if (!waited(20)) break;
                server(p -> {
                    var grid = CraftingService.grid(p.containerMenu);
                    int logs = 0;
                    for (int i = 0; i < grid.getContainerSize(); i++) {
                        var stack = grid.getItem(i);
                        require(stack.isEmpty() || stack.is(Items.OAK_LOG), "Unworn JEI moved an unexpected ingredient");
                        if (stack.is(Items.OAK_LOG)) logs += stack.getCount();
                    }
                    // JEI's native centered layout may place a 1x1 input at another legal grid position.
                    require(logs == 1 && p.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS)
                            && p.containerMenu.getSlot(0).getItem().getCount() == 4, "Unworn JEI did not retain native backpack transfer: " + grid.getItems());
                    require(CacheLedger.get(p.getServer()).find(cacheId).state().cells().getFirst().amount() == 11, "Unworn native transfer consumed cache");
                }); next();
            }
            case 19 -> { if (waited(10)) return true; }
            default -> throw new IllegalStateException("Unexpected consumption review stage");
        }
        return false;
    }
    private static void setup(ServerPlayer player) {
        dev.scathiard.feedmepackages.consumption.CraftingReservations.forget(player);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.containerMenu.setCarried(ItemStack.EMPTY);
        CraftingService.grid(player.containerMenu).clearContent();
        TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        ConsumptionTests.seed(player, 0, new ItemStack(Items.OAK_LOG), 12); ConsumptionTests.seed(player, 1, new ItemStack(Items.ARROW), 8);
        cacheId = AccessGate.resolve(player).handle().cacheId(); player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        player.awardRecipes(List.of(player.getServer().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow()));
        player.containerMenu.broadcastFullState();
    }
    @SuppressWarnings("unchecked") private static RecipeHolder<CraftingRecipe> recipe() {
        return (RecipeHolder<CraftingRecipe>) Minecraft.getInstance().level.getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
    }
    private static boolean waited(int count) { return tick - changed >= count; }
    private static void clickBookRecipe(InventoryScreen screen, ResourceLocation id) {
        try {
            var field = net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class.getDeclaredField("recipeBookPage"); field.setAccessible(true);
            var page = field.get(screen.getRecipeBookComponent());
            var buttons = page.getClass().getDeclaredField("buttons"); buttons.setAccessible(true);
            for (var value : (List<?>)buttons.get(page)) {
                var button = (net.minecraft.client.gui.screens.recipebook.RecipeButton)value;
                if (button.visible && button.getRecipe().id().equals(id)) {
                    ClientReview.click(button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2); return;
                }
            }
            throw new IllegalStateException("Requested recipe was not visible on the actual book page");
        } catch (ReflectiveOperationException problem) { throw new IllegalStateException("Cannot inspect source-verified recipe-book buttons", problem); }
    }
    private static void next() { stage++; changed = tick; FeedMePackages.LOGGER.info("FMP_CLIENT_CONSUMPTION_STAGE {}", stage); }
    private static void require(boolean ok, String error) { if (!ok) throw new IllegalStateException(error); }
    private static void server(Consumer<ServerPlayer> operation) {
        var mc = Minecraft.getInstance(); var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        work = CompletableFuture.runAsync(() -> operation.accept(server.getPlayerList().getPlayer(id)), server);
    }
    private static void jei(String name) {
        try { Class.forName("dev.scathiard.feedmepackages.gametest.JeiClientReview").getMethod(name).invoke(null); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("JEI transfer review failed at " + name, exception); }
    }
}
