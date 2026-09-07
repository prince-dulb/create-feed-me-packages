package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.network.MaterialHints;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.inventory.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.registries.RegisterEvent;
import java.util.*;
import java.util.function.Predicate;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ConsumptionTests {
    private static ProbeWeapon probe;
    @SubscribeEvent public static void testItem(RegisterEvent event) {
        event.register(Registries.ITEM, h -> {
            h.register(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "test_stone_launcher"), probe = new ProbeWeapon());
            h.register(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "test_milk_capsule"), capsule = new Item(new Item.Properties().stacksTo(16).craftRemainder(Items.BUCKET)));
        });
    }
    private static Item capsule;
    /** Test-only unrelated weapon using the vanilla base protocol. It is never included in delivery jars. */
    private static final class ProbeWeapon extends ProjectileWeaponItem {
        ProbeWeapon() { super(new Item.Properties().stacksTo(1)); }
        @Override public Predicate<ItemStack> getAllSupportedProjectiles() { return s -> s.is(Items.STONE); }
        @Override public int getDefaultProjectileRange() { return 8; }
        @Override protected void shootProjectile(LivingEntity shooter, Projectile projectile, int index, float velocity, float inaccuracy, float angle, LivingEntity target) {}
        static List<ItemStack> drawReal(ItemStack weapon, ItemStack ammo, LivingEntity player) { return draw(weapon, ammo, player); }
    }
    static void seed(ServerPlayer player, int slot, ItemStack stack, int count) {
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId());
        var edit = record.state().edit(); var key = ItemVariantKey.of(stack, player.registryAccess()); edit.filter(slot, key); edit.insert(slot, key, count);
        ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
    }
    static int stock(ServerPlayer player, int slot) {
        return CacheLedger.get(player.getServer()).find(AccessGate.resolve(player).handle().cacheId()).state().cells().get(slot).amount();
    }
    private static void enchant(ServerPlayer player, ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        stack.enchant(player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), 1);
    }
    @GameTest(template = "empty")
    public static void materialSimulationFallbackAndOneCommit(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var stone = new ItemStack(Items.STONE);
        seed(player, 0, stone, 10); player.getInventory().setItem(0, new ItemStack(Items.STONE, 3));
        var plan = MaterialTransaction.open(player).orElseThrow();
        helper.assertTrue(plan.available(stone) == 13 && plan.take(stone, 8), "Combined source simulation failed");
        helper.assertTrue(stock(player, 0) == 10 && player.getInventory().getItem(0).getCount() == 3, "Simulation consumed material");
        helper.assertTrue(plan.commit() && stock(player, 0) == 5 && player.getInventory().getItem(0).isEmpty(), "Backpack-first fallback consumed wrong source");
        helper.assertTrue(!plan.commit(), "Material plan committed twice");
        CacheLedger.get(player.getServer()).setCacheFirst(player.getUUID(), true); player.getInventory().setItem(0, new ItemStack(Items.STONE, 3));
        var second = MaterialTransaction.open(player).orElseThrow(); helper.assertTrue(second.take(stone, 4) && second.commit(), "Fixed-priority commit failed");
        helper.assertTrue(stock(player, 0) == 4 && player.getInventory().getItem(0).isEmpty(), "Inactive preference changed backpack-first consumption");
        var shortPlan = MaterialTransaction.open(player).orElseThrow(); helper.assertTrue(!shortPlan.take(stone, 5) && shortPlan.available(stone) == 4, "Shortage left a partial simulation");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void materialPlansRecheckSourcesPreferenceAndWear(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var stone = new ItemStack(Items.STONE); seed(player, 0, stone, 8);
        var plan = MaterialTransaction.open(player).orElseThrow(); plan.take(stone, 1);
        player.getInventory().setItem(2, new ItemStack(Items.DIRT)); helper.assertTrue(!plan.commit() && stock(player, 0) == 8, "Changed inventory escaped snapshot validation");
        plan = MaterialTransaction.open(player).orElseThrow(); plan.take(stone, 1); CacheLedger.get(player.getServer()).setCacheFirst(player.getUUID(), true);
        helper.assertTrue(plan.valid() && !plan.cacheFirst(), "Inactive preference changed source selection");
        plan = MaterialTransaction.open(player).orElseThrow(); plan.take(stone, 1); var pendant = TestPlayers.necklace(player).getStackInSlot(0); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        helper.assertTrue(!plan.commit() && MaterialTransaction.open(player).isEmpty(), "Unwear permitted consumption");
        TestPlayers.necklace(player).setStackInSlot(0, pendant); helper.assertTrue(stock(player, 0) == 8, "Rejected consumption lost cache stock"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nativeBowQueryCancelAndSuccessfulShot(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.ARROW), 10);
        var bow = new ItemStack(Items.BOW); player.setItemInHand(InteractionHand.MAIN_HAND, bow);
        for (int i = 0; i < 20; i++) helper.assertTrue(player.getProjectile(bow).is(Items.ARROW), "Cache-only arrow unavailable to native query");
        helper.assertTrue(stock(player, 0) == 10, "Query deducted ammo");
        bow.getItem().releaseUsing(bow, player.level(), player, bow.getUseDuration(player) - 1);
        helper.assertTrue(stock(player, 0) == 10, "Cancelled/weak charge deducted ammo");
        bow.getItem().releaseUsing(bow, player.level(), player, bow.getUseDuration(player) - 20);
        helper.assertTrue(stock(player, 0) == 9 && player.getInventory().items.stream().noneMatch(s -> s.is(Items.ARROW)), "Native bow did not consume exactly one cached arrow"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void crossbowChargesOnceAndPreservesExactAmmo(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var arrow = new ItemStack(Items.SPECTRAL_ARROW);
        arrow.set(DataComponents.CUSTOM_NAME, Component.literal("Exact cached ammunition")); seed(player, 0, arrow, 6);
        var crossbow = new ItemStack(Items.CROSSBOW); enchant(player, crossbow, Enchantments.MULTISHOT); player.setItemInHand(InteractionHand.MAIN_HAND, crossbow);
        crossbow.getItem().releaseUsing(crossbow, player.level(), player, crossbow.getUseDuration(player) - 5);
        helper.assertTrue(!CrossbowItem.isCharged(crossbow) && stock(player, 0) == 6, "Cancelled crossbow charged or consumed");
        crossbow.getItem().releaseUsing(crossbow, player.level(), player, crossbow.getUseDuration(player) - 30);
        var charged = crossbow.get(DataComponents.CHARGED_PROJECTILES);
        helper.assertTrue(charged != null && charged.getItems().size() == 3 && stock(player, 0) == 5, "Multishot charge must consume only one source arrow");
        helper.assertTrue(ItemStack.isSameItemSameComponents(charged.getItems().getFirst(), arrow), "Charged arrow components changed");
        helper.assertTrue(charged.getItems().get(1).has(DataComponents.INTANGIBLE_PROJECTILE), "Native intangible multishot rule was lost");
        ((CrossbowItem) Items.CROSSBOW).performShooting(player.level(), player, InteractionHand.MAIN_HAND, crossbow, 3.15f, 1, null);
        helper.assertTrue(!CrossbowItem.isCharged(crossbow) && stock(player, 0) == 5, "Crossbow firing consumed ammunition again"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void infiniteAndCreativeKeepNativeAmmoAccounting(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.ARROW), 4);
        var bow = new ItemStack(Items.BOW); enchant(player, bow, Enchantments.INFINITY);
        var query = player.getProjectile(bow); var drawn = ProbeWeapon.drawReal(bow, query, player);
        helper.assertTrue(drawn.size() == 1 && drawn.getFirst().has(DataComponents.INTANGIBLE_PROJECTILE) && stock(player, 0) == 4, "Infinity consumed cached normal ammo");
        helper.assertTrue(ProbeWeapon.drawReal(bow, query, player).isEmpty(), "One query credential was replayable");
        player.getAbilities().instabuild = true; var crossbow = new ItemStack(Items.CROSSBOW); enchant(player, crossbow, Enchantments.MULTISHOT);
        helper.assertTrue(ProbeWeapon.drawReal(crossbow, player.getProjectile(crossbow), player).size() == 3 && stock(player, 0) == 4, "Creative multishot consumed cache"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void invalidatedQueryCannotMakeFreeMultishotCopies(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.ARROW), 4);
        var crossbow = new ItemStack(Items.CROSSBOW); enchant(player, crossbow, Enchantments.MULTISHOT); var query = player.getProjectile(crossbow);
        var pendant = TestPlayers.necklace(player).getStackInSlot(0); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        helper.assertTrue(ProbeWeapon.drawReal(crossbow, query, player).isEmpty(), "Invalid main shot still generated intangible copies");
        TestPlayers.necklace(player).setStackInSlot(0, pendant); helper.assertTrue(stock(player, 0) == 4, "Invalid shot consumed ammo");
        query = player.getProjectile(crossbow); seed(player, 1, new ItemStack(Items.STONE), 1);
        helper.assertTrue(ProbeWeapon.drawReal(crossbow, query, player).isEmpty(), "Old cache revision authorized ammo"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void unrelatedWeaponUsesPredicateAndSourcePriority(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.STONE), 5); seed(player, 1, new ItemStack(Items.ARROW), 5);
        var weapon = new ItemStack(probe); player.getInventory().setItem(4, new ItemStack(Items.STONE, 2));
        var first = ProbeWeapon.drawReal(weapon, player.getProjectile(weapon), player);
        helper.assertTrue(first.size() == 1 && first.getFirst().is(Items.STONE) && stock(player, 0) == 5 && player.getInventory().getItem(4).getCount() == 1, "Backpack-first custom predicate ignored");
        CacheLedger.get(player.getServer()).setCacheFirst(player.getUUID(), true);
        var second = ProbeWeapon.drawReal(weapon, player.getProjectile(weapon), player);
        helper.assertTrue(second.size() == 1 && second.getFirst().is(Items.STONE) && stock(player, 0) == 5 && stock(player, 1) == 5 && player.getInventory().getItem(4).isEmpty(), "Inactive preference bypassed backpack-first on the unrelated weapon"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void materialHintsUseBoundedDeltasAndClearOnUnwear(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.ARROW), 20);
        var full = MaterialHints.next(player); helper.assertTrue(full.full() && full.active() && full.amounts().getFirst() == 20, "Initial hints missing");
        helper.assertTrue(MaterialHints.next(player) == null, "Unchanged materials resent full templates");
        var plan = MaterialTransaction.open(player).orElseThrow(); plan.take(new ItemStack(Items.ARROW), 1); plan.commit(); var delta = MaterialHints.next(player);
        helper.assertTrue(!delta.full() && delta.templates().isEmpty() && delta.generation().equals(full.generation()) && delta.amounts().getFirst() == 19, "Count update unnecessarily resent templates");
        var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try { MaterialHints.Message.CODEC.encode(b, delta); helper.assertTrue(b.readableBytes() < 200 && MaterialHints.Message.CODEC.decode(b).equals(delta), "Delta wire failed or oversized"); } finally { b.release(); }
        TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY); var off = MaterialHints.next(player);
        helper.assertTrue(off.full() && !off.active() && off.amounts().isEmpty(), "Unwear retained available material hints"); helper.succeed();
    }

    @SuppressWarnings("unchecked") private static RecipeHolder<CraftingRecipe> recipe(ServerPlayer player, String id) {
        return (RecipeHolder<CraftingRecipe>) player.getServer().getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow();
    }
    private static int inventoryCount(ServerPlayer player, Item item) { return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    @GameTest(template = "empty")
    public static void preparingAndClosingRecipeNeverConsumesCachedMaterial(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 64);
        helper.assertTrue(CraftingService.place(player, recipe(player, "minecraft:oak_planks"), false, false, true) == CraftingService.Result.OK,
                "Recipe preparation failed");
        helper.assertTrue(stock(player, 0) == 64, "Preparation already consumed cache: " + stock(player, 0));
        player.closeContainer();
        helper.assertTrue(stock(player, 0) == 64 && inventoryCount(player, Items.OAK_LOG) == 0, "Cancel materialized unconsumed recipe input");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void realCraftCreatesOnlyItsOwnRestockDeficit(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 64);
        var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId());
        var edit = record.state().edit(); edit.thresholds(0, 1, -1); ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
        long revision = ledger.find(handle.cacheId()).state().revision();
        var recipe = recipe(player, "minecraft:oak_planks"); CraftingService.place(player, recipe, false, false, true);
        helper.assertTrue(ledger.find(handle.cacheId()).state().revision() == revision && ledger.find(handle.cacheId()).state().requestable(0) == 0,
                "Preparation dirtied the cache or requested extra stock");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(stock(player, 0) == 63 && ledger.find(handle.cacheId()).state().requestable(0) == 1
                && player.containerMenu.getCarried().getCount() == 4 && CraftingService.grid(player.containerMenu).getItem(0).getCount() == 1,
                "Refilling the preview charged a second recipe");
        player.closeContainer();
        helper.assertTrue(stock(player, 0) == 63 && inventoryCount(player, Items.OAK_LOG) == 0 && inventoryCount(player, Items.OAK_PLANKS) == 4,
                "Closing after a real craft moved an unused source"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reservationBlocksOtherConsumersAndSameCacheCopyUntilCancelled(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 64);
        var stale = MaterialTransaction.open(player).orElseThrow(); stale.take(new ItemStack(Items.OAK_LOG), 1);
        CraftingService.place(player, recipe(player, "minecraft:oak_planks"), true, false, true);
        var other = TestPlayers.create(helper, TestPlayers.necklace(player).getStackInSlot(0).copy());
        helper.assertTrue(!stale.commit() && MaterialTransaction.open(player).orElseThrow().available(new ItemStack(Items.OAK_LOG)) == 0
                && MaterialTransaction.open(other).orElseThrow().available(new ItemStack(Items.OAK_LOG)) == 0, "Reserved stock was offered to another consumer");
        var view = CacheActions.open(other);
        helper.assertTrue(CacheActions.execute(other, new CacheActions.Intent(view.session(), view.record().state().revision(),
                CacheActions.Action.TAKE_CURSOR, 0, 64, -1, "")) == CacheActions.Result.NO_SPACE, "Same-cache duplicate bypassed the lease");
        player.closeContainer();
        helper.assertTrue(MaterialTransaction.open(other).orElseThrow().available(new ItemStack(Items.OAK_LOG)) == 64,
                "Cancellation left a permanent reservation"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void repeatPreparationAndManualTakingHaveDistinctOwnership(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 64);
        var recipe = recipe(player, "minecraft:oak_planks");
        for (int i = 0; i < 5; i++) helper.assertTrue(CraftingService.place(player, recipe, false, false, true) == CraftingService.Result.OK, "Repeated preparation failed");
        helper.assertTrue(stock(player, 0) == 64 && CraftingService.grid(player.containerMenu).getItem(0).getCount() == 5, "Preparation increment consumed stock");
        player.containerMenu.clicked(1, 1, ClickType.PICKUP, player);
        helper.assertTrue(stock(player, 0) == 61 && player.containerMenu.getCarried().getCount() == 3
                && CraftingService.grid(player.containerMenu).getItem(0).getCount() == 2, "Manual half-stack extraction did not consume exactly the extracted amount");
        player.closeContainer(); helper.assertTrue(stock(player, 0) == 61 && inventoryCount(player, Items.OAK_LOG) == 3,
                "Cancel returned remaining preview as extra real material"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unwearBeforeClickAndExternalSourceChangesInvalidatePreparation(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 4);
        var recipe = recipe(player, "minecraft:oak_planks"); CraftingService.place(player, recipe, false, false, true);
        var pendant = TestPlayers.necklace(player).getStackInSlot(0); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.containerMenu.getCarried().isEmpty() && CraftingService.grid(player.containerMenu).isEmpty(), "Stale result remained craftable after unwear");
        TestPlayers.necklace(player).setStackInSlot(0, pendant); helper.assertTrue(stock(player, 0) == 4, "Unwear consumed source");
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG)); CraftingService.place(player, recipe, false, false, true);
        player.getInventory().setItem(0, ItemStack.EMPTY); CraftingReservations.validate(player);
        helper.assertTrue(CraftingService.grid(player.containerMenu).isEmpty() && stock(player, 0) == 4, "Invalid backpack source silently switched to cache"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryCannotReuseSpaceOwnedByUnspentReservation(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64)); player.getInventory().setItem(1, ItemStack.EMPTY);
        helper.assertTrue(CraftingService.place(player, recipe(player, "minecraft:oak_planks"), true, false, true) == CraftingService.Result.OK,
                "Backpack maximum preparation failed");
        helper.assertTrue(inventoryCount(player, Items.OAK_LOG) == 64, "Preparation moved a whole backpack stack");
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 64 && inventoryCount(player, Items.OAK_LOG) == 48
                && CraftingService.grid(player.containerMenu).getItem(0).getCount() == 48,
                "Native shift output stole space needed by unspent material: wood=" + inventoryCount(player, Items.OAK_LOG) + ", planks=" + inventoryCount(player, Items.OAK_PLANKS));
        player.closeContainer(); helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 64 && inventoryCount(player, Items.OAK_LOG) == 48,
                "Closing after a full-backpack craft lost or duplicated material"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void directQuickMoveAndReservedBackpackEditingUseNativeOwnership(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 4);
        var recipe = recipe(player, "minecraft:oak_planks"); CraftingService.place(player, recipe, false, false, true);
        player.containerMenu.quickMoveStack(player, 0);
        helper.assertTrue(stock(player, 0) == 3 && inventoryCount(player, Items.OAK_PLANKS) == 4, "Direct native quickMove bypassed source ownership");
        player.closeContainer(); player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2));
        CraftingService.place(player, recipe, false, false, true); player.containerMenu.clicked(36, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.containerMenu.getCarried().is(Items.OAK_LOG) && player.containerMenu.getCarried().getCount() == 2
                && CraftingService.grid(player.containerMenu).isEmpty() && stock(player, 0) == 3,
                "Editing a reserved backpack stack did not cancel its preview"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nonMutatingPreparationPacketCannotBeReplayed(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 8);
        var window = UUID.randomUUID(); var view = dev.scathiard.feedmepackages.network.PanelNetwork.query(player,
                new dev.scathiard.feedmepackages.network.PanelPackets.Query(window, player.containerMenu.containerId, true));
        var intent = new CacheActions.Intent(view.session(), view.revision(), CacheActions.Action.FILL_RECIPE, 0, 0, -1, "minecraft:oak_planks");
        var packet = new dev.scathiard.feedmepackages.network.PanelPackets.Command(window, 1, intent, false, "", 0);
        helper.assertTrue(dev.scathiard.feedmepackages.network.PanelNetwork.command(player, packet).result() == CacheActions.Result.OK
                && dev.scathiard.feedmepackages.network.PanelNetwork.command(player, packet).result() == CacheActions.Result.STALE
                && stock(player, 0) == 8 && CraftingService.grid(player.containerMenu).getItem(0).getCount() == 1,
                "Replayed preparation changed leases despite an unchanged cache revision");
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        helper.assertTrue(CraftingService.grid(player.containerMenu).isEmpty() && stock(player, 0) == 8, "Game mode change retained a survival recipe lease");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standardAmmoCannotConsumeReservedBackpackOrCacheSources(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.STONE), 2);
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 3)); var weapon = new ItemStack(probe);
        var recipe = recipe(player, "minecraft:stone_button"); CraftingService.place(player, recipe, true, false, true);
        helper.assertTrue(player.getProjectile(weapon).isEmpty() && stock(player, 0) == 2 && inventoryCount(player, Items.STONE) == 3,
                "Standard projectile query bypassed a fully reserved source");
        player.closeContainer(); CraftingService.place(player, recipe, false, false, true);
        helper.assertTrue(ProbeWeapon.drawReal(weapon, player.getProjectile(weapon), player).size() == 1 && stock(player, 0) == 2 && inventoryCount(player, Items.STONE) == 2,
                "Partly reserved backpack did not expose only its unreserved material");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.containerMenu.getCarried().is(Items.STONE_BUTTON) && stock(player, 0) == 2 && inventoryCount(player, Items.STONE) == 1,
                "Craft after ammo use consumed the lease twice or changed source priority");
        player.closeContainer(); helper.succeed();
    }

    private static void table(GameTestHelper helper, ServerPlayer player) {
        var relative = new BlockPos(1, 1, 1); helper.setBlock(relative, Blocks.CRAFTING_TABLE); var pos = helper.absolutePos(relative);
        player.setPos(pos.getX(), pos.getY() + 1, pos.getZ());
        player.containerMenu = new CraftingMenu(37, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), pos));
    }
    @GameTest(template = "empty")
    public static void recipeBookTransfersMixedSourcesAndManualCraftRefills(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_PLANKS), 7);
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS)); var recipe = recipe(player, "minecraft:stick"); player.awardRecipes(List.of(recipe));
        helper.assertTrue(CraftingService.place(player, recipe, false, true, false) == CraftingService.Result.OK && stock(player, 0) == 7
                && CraftingService.grid(player.containerMenu).isEmpty() && inventoryCount(player, Items.OAK_PLANKS) == 1, "Recipe preview consumed or moved ingredients");
        ((InventoryMenu)player.containerMenu).handlePlacement(false, recipe, player);
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().is(Items.STICK) && stock(player, 0) == 7 && inventoryCount(player, Items.OAK_PLANKS) == 1, "Native book preparation consumed inventory or cache");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.containerMenu.getCarried().is(Items.STICK) && player.containerMenu.getCarried().getCount() == 4 && stock(player, 0) == 6
                && inventoryCount(player, Items.OAK_PLANKS) == 0, "A real craft consumed more than one recipe or used the wrong source");
        player.closeContainer();
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 0 && stock(player, 0) == 6, "Closing grid materialized unused preparation"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void continuousCraftingHasConservationAndPerClickBound(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 128);
        helper.assertTrue(CraftingService.place(player, recipe(player, "minecraft:oak_planks"), false, false, true) == CraftingService.Result.OK, "Initial crafting fill failed");
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 256 && stock(player, 0) == 64 && CraftingService.grid(player.containerMenu).isEmpty(), "Continuous craft was unbounded or not conserved"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void fullAndPartiallyFullResultDestinationStopsBeforeConsumption(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 8);
        CraftingService.place(player, recipe(player, "minecraft:oak_planks"), false, false, true);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(stock(player, 0) == 8 && CraftingService.grid(player.containerMenu).getItem(0).getCount() == 1, "Full inventory still consumed crafting input");
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 63)); player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(inventoryCount(player, Items.OAK_PLANKS) == 63 && stock(player, 0) == 8 && player.containerMenu.getSlot(0).getItem().getCount() == 4, "Partial result movement caused a fallback drop"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void cakeUsesThreeByThreeAndKeepsBucketsWithoutRestockOverwrite(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        // Level 3: groupCapacity=8, milk(stackSize=1) capacity=8 ≥ 6 needed for seed
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        var edit = before.state().edit(); for (int lvl = 1; lvl < 3; lvl++) edit.upgrade(); ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        table(helper, player);
        seed(player, 0, new ItemStack(Items.MILK_BUCKET), 6); seed(player, 1, new ItemStack(Items.SUGAR), 4);
        seed(player, 2, new ItemStack(Items.EGG), 2); seed(player, 3, new ItemStack(Items.WHEAT), 6);
        helper.assertTrue(CraftingService.place(player, recipe(player, "minecraft:cake"), false, false, true) == CraftingService.Result.OK && player.containerMenu.getSlot(0).getItem().is(Items.CAKE), "3x3 recipe did not fill from cache");
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        var grid = CraftingService.grid(player.containerMenu);
        helper.assertTrue(player.containerMenu.getCarried().is(Items.CAKE) && grid.getItems().stream().filter(s -> s.is(Items.BUCKET)).mapToInt(ItemStack::getCount).sum() == 3, "Cake recipe did not preserve its three buckets");
        helper.assertTrue(stock(player, 0) == 3 && stock(player, 1) == 2 && stock(player, 2) == 1 && stock(player, 3) == 3, "Bucket-occupied grid caused partial cache restocking"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void stackedRemainderAndOutputAreSimulatedTogether(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var grid = CraftingService.grid(player.containerMenu);
        grid.setItem(0, new ItemStack(capsule, 2));
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        helper.assertTrue(player.containerMenu.getSlot(0).getItem().is(Items.SUGAR), "Remainder fixture recipe missing");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.containerMenu.getCarried().isEmpty() && grid.getItem(0).getCount() == 2, "No-room bucket remainder still consumed input");
        player.getInventory().setItem(1, ItemStack.EMPTY); player.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(player.getInventory().getItem(1).isEmpty() && grid.getItem(0).getCount() == 2, "One empty slot was double-counted for result and remainder");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.containerMenu.getCarried().is(Items.SUGAR) && inventoryCount(player, Items.BUCKET) == 1 && grid.getItem(0).getCount() == 1, "Safe cursor result + inventory remainder failed"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void gridClearingFailureAndStalePlacementLeaveBothSources(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(player, 0, new ItemStack(Items.OAK_LOG), 4);
        var grid = CraftingService.grid(player.containerMenu); grid.setItem(0, new ItemStack(Items.STONE));
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        var recipe = recipe(player, "minecraft:oak_planks");
        helper.assertTrue(CraftingService.place(player, recipe, false, false, true) == CraftingService.Result.NO_SPACE && stock(player, 0) == 4 && grid.getItem(0).is(Items.STONE), "Failed old-grid clearing changed source state");
        player.getInventory().setItem(0, ItemStack.EMPTY); var plan = CraftingService.simulate(player, recipe, false, false);
        helper.assertTrue(plan.result() == CraftingService.Result.OK, "Placement simulation failed with clearance room");
        grid.setItem(0, new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(!plan.plan().commit() && stock(player, 0) == 4 && player.getInventory().getItem(0).isEmpty(), "Changed grid accepted stale material transaction");
        var view = CacheActions.open(player); var intent = new CacheActions.Intent(view.session(), view.record().state().revision(), CacheActions.Action.FILL_RECIPE, 0, 0, -1, recipe.id().toString());
        CacheActions.close(player); helper.assertTrue(CacheActions.execute(player, intent) == CacheActions.Result.STALE, "Closed recipe context could transfer cache material"); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void exactRecipeVariantsAndMaxTransferUseLegalStacks(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var named = new ItemStack(Items.OAK_PLANKS); named.set(DataComponents.CUSTOM_NAME, Component.literal("Selected variant"));
        seed(player, 0, new ItemStack(Items.OAK_PLANKS), 4); seed(player, 1, named, 4); player.getInventory().setItem(0, named.copy());
        helper.assertTrue(CraftingService.place(player, recipe(player, "minecraft:stick"), false, false, true) == CraftingService.Result.OK, "Exact matching failed");
        helper.assertTrue(stock(player, 0) == 4 && stock(player, 1) == 4 && CraftingService.grid(player.containerMenu).getItems().stream().filter(s -> !s.isEmpty()).allMatch(s -> ItemStack.isSameItemSameComponents(s, named)), "Component variants were consumed or collapsed during preparation");
        var other = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); seed(other, 0, new ItemStack(Items.OAK_LOG), 128);
        helper.assertTrue(CraftingService.place(other, recipe(other, "minecraft:oak_planks"), true, false, true) == CraftingService.Result.OK
                && CraftingService.grid(other.containerMenu).getItem(0).getCount() == 64 && stock(other, 0) == 128, "Maximum preparation consumed stock or exceeded native grid stack limit"); helper.succeed();
    }
}
