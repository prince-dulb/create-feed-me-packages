package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.client.PanelLayout;
import dev.scathiard.feedmepackages.compat.jei.FmpJeiPlugin;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import mezz.jei.api.constants.VanillaTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.resources.ResourceLocation;

/** Optional test module; invoked by name only when JEI is actually loaded. */
public final class JeiClientReview {
    private static PanelLayout.CellBox destination;
    private static ItemStack expected;
    private static int sourceX, sourceY;
    private static IRecipeLayoutDrawable<RecipeHolder<CraftingRecipe>> transferLayout;
    private static int transferX, transferY;
    public static void verifyReservedControls() throws Exception {
        var overlay = FmpJeiPlugin.runtime().orElseThrow().getBookmarkOverlay();
        var panel = LogisticsPanel.exclusions(Minecraft.getInstance().screen).getFirst();
        for (String name : new String[]{"bookmarkButton", "historyButton"}) {
            var field = overlay.getClass().getDeclaredField(name); field.setAccessible(true); var button = field.get(overlay);
            int x = (int) button.getClass().getMethod("getX").invoke(button), y = (int) button.getClass().getMethod("getY").invoke(button);
            int w = (int) button.getClass().getMethod("getWidth").invoke(button), h = (int) button.getClass().getMethod("getHeight").invoke(button);
            if (panel.x() + panel.width() > x && panel.x() < x + w && panel.y() + panel.height() > y && panel.y() < y + h)
                throw new IllegalStateException("FMP overlaps the actual JEI " + name + " rectangle");
        }
    }
    public static void begin() throws Exception {
        var mc = Minecraft.getInstance(); var runtime = FmpJeiPlugin.runtime().orElseThrow();
        var screen = (AbstractContainerScreen<?>) mc.screen;
        var exclusion = LogisticsPanel.exclusions(screen).getFirst();
        if (runtime.getScreenHelper().getGuiExclusionAreas(screen).noneMatch(r -> r.getX() == exclusion.x() && r.getY() == exclusion.y()))
            throw new IllegalStateException("Registered JEI exclusion does not include FMP");
        if (runtime.getScreenHelper().getGhostIngredientHandlers(screen).stream().anyMatch(h -> h.getClass().getName().startsWith("dev.scathiard.feedmepackages")))
            throw new IllegalStateException("Retired FMP ghost handler is still registered");
        for (int step = 0; step < 18 && LogisticsPanel.visibleCells(screen).stream().noneMatch(cell -> cell.slot() >= 9); step++) {
            var b = LogisticsPanel.exclusions(screen).getFirst();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.ScreenEvent.MouseScrolled.Pre(screen, b.x() + 12, b.y() + 35, 0, -1));
        }
        destination = LogisticsPanel.visibleCells(screen).stream().filter(cell -> cell.slot() >= 9).findFirst().orElseThrow();
        var occupied = Set.of(Items.STONE, Items.IRON_INGOT, Items.COPPER_INGOT, Items.REDSTONE, Items.ANDESITE, Items.GLASS, Items.HOPPER, Items.RAIL, Items.OAK_PLANKS);
        if (!runtime.getIngredientListOverlay().isListDisplayed()) throw new IllegalStateException("JEI item list is not visible");
        for (int y = 30; y < screen.height - 25; y += 9) for (int x = screen.getGuiLeft() + screen.getXSize() + 4; x < screen.width - 5; x += 9) {
            move(x, y);
            var ingredient = runtime.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (ingredient == null || occupied.contains(ingredient.getItem())) continue;
            try { ItemVariantKey.of(ingredient, mc.player.registryAccess()); } catch (IllegalArgumentException excluded) { continue; }
            expected = ingredient.copyWithCount(1); sourceX = x; sourceY = y; press(1); return;
        }
        throw new IllegalStateException("Could not find a visible, safe JEI ingredient for the drag");
    }
    public static void finish() throws Exception {
        move(destination.bounds().x() + 8, destination.bounds().y() + 8); press(0);
        var mc = Minecraft.getInstance();
        if (mc.screen == FmpJeiPlugin.runtime().orElseThrow().getRecipesGui()) mc.screen.onClose();
    }
    public static void clickOnly() throws Exception {
        var mc = Minecraft.getInstance(); var original = mc.screen;
        // The arbitrary drag ingredient can have no producing recipe (e.g. oak logs).
        // Choose an actual visible ingredient with a known vanilla producing recipe.
        var screen = (AbstractContainerScreen<?>) original;
        boolean found = false;
        search: for (int y = 30; y < screen.height - 25; y += 9) for (int x = screen.getGuiLeft() + screen.getXSize() + 4; x < screen.width - 5; x += 9) {
            move(x, y);
            var underMouse = FmpJeiPlugin.runtime().orElseThrow().getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
            if (underMouse != null && underMouse.is(Items.OAK_PLANKS)) { found = true; break search; }
        }
        if (!found) throw new IllegalStateException("No visible oak planks for recipe click regression");
        press(1); press(0);
        if (mc.screen != FmpJeiPlugin.runtime().orElseThrow().getRecipesGui()) throw new IllegalStateException("Ordinary JEI click no longer opens recipes");
        mc.screen.onClose();
        if (mc.screen != original) throw new IllegalStateException("JEI did not return to the original supply screen");
    }
    public static void verify(ServerPlayer player) {
        var key = ItemVariantKey.of(expected, player.registryAccess());
        var state = CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state();
        var cell = state.cells().get(destination.slot());
        if (state.find(key) >= 0 || cell.filter() != null || cell.amount() != 0)
            throw new IllegalStateException("Disabled JEI drag still changed a cache filter or its real contents");
    }
    @SuppressWarnings("unchecked")
    public static void openTransfer() {
        var mc = Minecraft.getInstance(); var runtime = FmpJeiPlugin.runtime().orElseThrow();
        var recipe = (RecipeHolder<CraftingRecipe>)mc.level.getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
        var category = runtime.getRecipeManager().getRecipeCategory(RecipeTypes.CRAFTING);
        runtime.getRecipesGui().showRecipes(category, List.of(recipe), List.of());
        if (mc.screen != runtime.getRecipesGui()) throw new IllegalStateException("JEI did not open requested recipe");
    }
    @SuppressWarnings("unchecked")
    public static void previewTransfer() throws Exception {
        var mc = Minecraft.getInstance(); var runtime = FmpJeiPlugin.runtime().orElseThrow();
        // Read the real displayed layout via a source-verified JEI diagnostic method; click its actual button later.
        var getter = mc.screen.getClass().getMethod("getRecipeLayoutUnderMouse", double.class, double.class);
        transferLayout = null;
        search: for (int y = 20; y < mc.screen.height - 20; y += 8) for (int x = 20; x < mc.screen.width - 20; x += 8) {
            var found = (Optional<?>) getter.invoke(mc.screen, (double)x, (double)y);
            if (found.isEmpty()) continue;
            var withButtons = found.get();
            var layout = (IRecipeLayoutDrawable<?>)withButtons.getClass().getMethod("getRecipeLayout").invoke(withButtons);
            if (layout.getRecipe() instanceof RecipeHolder<?> holder && holder.id().equals(ResourceLocation.withDefaultNamespace("oak_planks"))) {
                transferLayout = (IRecipeLayoutDrawable<RecipeHolder<CraftingRecipe>>)layout; break search;
            }
        }
        if (transferLayout == null) throw new IllegalStateException("Actual JEI recipe layout was not found");
        var handler = runtime.getRecipeTransferManager().getRecipeTransferHandler(mc.player.containerMenu, transferLayout.getRecipeCategory()).orElseThrow();
        if (!handler.getClass().getName().endsWith("FmpRecipeTransfer")) throw new IllegalStateException("JEI registered the wrong transfer handler");
        for (int i = 0; i < 3; i++) if (handler.transferRecipe(mc.player.containerMenu, transferLayout.getRecipe(), transferLayout.getRecipeSlotsView(), mc.player, false, false) != null)
            throw new IllegalStateException("JEI transfer preview rejected the available ingredient");
        var area = transferLayout.getSideButtonArea(0); var rect = transferLayout.getRect();
        transferX = area.getX() + rect.getX() + area.getWidth() / 2;
        transferY = area.getY() + rect.getY() + area.getHeight() / 2;
    }
    public static void clickTransfer() throws Exception { move(transferX, transferY); press(1); press(0); }
    private static Object growthRecipe;
    public static void openGrowthSmithing() {
        var runtime = FmpJeiPlugin.runtime().orElseThrow(); var manager = runtime.getRecipeManager();
        var recipes = manager.createRecipeLookup(RecipeTypes.SMITHING).get().filter(r -> r.id().getNamespace().equals("create_feed_me_packages")).toList();
        if (recipes.size() != 9) throw new IllegalStateException("JEI did not register all nine FMP smithing recipes: " + recipes.size());
        var recipe = recipes.stream().filter(r -> r.id().getPath().equals("smithing/personal_supply_chain_pendant_to_3")).findFirst().orElseThrow();
        growthRecipe = recipe; runtime.getRecipesGui().showRecipes(manager.getRecipeCategory(RecipeTypes.SMITHING), List.of(recipe), List.of());
    }
    @SuppressWarnings("unchecked") // Create's category uses RecipeHolder<T>; JEI's UID-only lookup erases T.
    public static void openGrowthSequence() {
        var runtime = FmpJeiPlugin.runtime().orElseThrow(); var manager = runtime.getRecipeManager();
        var registered = manager.getRecipeType(ResourceLocation.fromNamespaceAndPath("create", "sequenced_assembly")).orElseThrow();
        if (registered.getRecipeClass() != RecipeHolder.class) throw new IllegalStateException("Create changed its sequence category recipe representation");
        var type = (mezz.jei.api.recipe.RecipeType<RecipeHolder<SequencedAssemblyRecipe>>)registered;
        var recipe = manager.createRecipeLookup(type).get().filter(r -> r.value().getResultItem(Minecraft.getInstance().level.registryAccess()).is(FmpRegistries.upgradeLink(5).get())).findFirst().orElseThrow();
        growthRecipe = recipe; runtime.getRecipesGui().showRecipes(manager.getRecipeCategory(type), List.of(recipe), List.of());
    }
    public static void verifyGrowthPage() throws Exception {
        var mc = Minecraft.getInstance(); var runtime = FmpJeiPlugin.runtime().orElseThrow();
        if (mc.screen != runtime.getRecipesGui()) throw new IllegalStateException("JEI growth recipe did not open a real page");
        var getter = mc.screen.getClass().getMethod("getRecipeLayoutUnderMouse", double.class, double.class);
        for (int y = 20; y < mc.screen.height - 20; y += 8) for (int x = 20; x < mc.screen.width - 20; x += 8) {
            var found = (Optional<?>)getter.invoke(mc.screen, (double)x, (double)y); if (found.isEmpty()) continue;
            var layout = (IRecipeLayoutDrawable<?>)found.get().getClass().getMethod("getRecipeLayout").invoke(found.get());
            if (layout.getRecipe().equals(growthRecipe)) {
                if (growthRecipe instanceof RecipeHolder<?> holder && holder.value() instanceof dev.scathiard.feedmepackages.growth.PendantSmithingRecipe recipe) {
                    var expectedResult = recipe.getResultItem(mc.level.registryAccess());
                    boolean visibleResult = layout.getRecipeSlotsView().getSlotViews(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT).stream()
                            .flatMap(slot -> slot.getDisplayedItemStack().stream()).anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, expectedResult));
                    if (!visibleResult) throw new IllegalStateException("Actual JEI smithing page has no visible preview result");
                    if (!expectedResult.getOrDefault(FmpRegistries.PREVIEW.get(), false) || expectedResult.has(FmpRegistries.IDENTITY.get())
                            || expectedResult.has(FmpRegistries.OWNER.get())) throw new IllegalStateException("JEI display acquired cache authority");
                }
                return;
            }
        }
        throw new IllegalStateException("JEI growth page did not render its requested recipe layout");
    }
    private static void move(double x, double y) throws Exception {
        var mc = Minecraft.getInstance(); var w = mc.getWindow();
        Method callback = MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class); callback.setAccessible(true);
        callback.invoke(mc.mouseHandler, w.getWindow(), x * w.getScreenWidth() / w.getGuiScaledWidth(), y * w.getScreenHeight() / w.getGuiScaledHeight());
    }
    private static void press(int action) throws Exception {
        var mc = Minecraft.getInstance();
        Method callback = MouseHandler.class.getDeclaredMethod("onPress", long.class, int.class, int.class, int.class); callback.setAccessible(true);
        callback.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0, action, 0);
    }
}
