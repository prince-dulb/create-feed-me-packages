package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.foundation.recipe.RecipeApplier;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.growth.*;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ReceiveService;
import dev.scathiard.feedmepackages.logistics.ParcelAuthentication;
import dev.scathiard.feedmepackages.logistics.ParcelSeal;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GrowthTests {
    private static final UUID PROCESS = UUID.randomUUID();
    private static SmithingMenu open(GameTestHelper helper, ServerPlayer player, ItemStack base, ItemStack link) {
        var table = helper.absolutePos(new BlockPos(1, 1, 1)); helper.getLevel().setBlockAndUpdate(table, Blocks.SMITHING_TABLE.defaultBlockState());
        player.setPos(table.getX() + 1.5, table.getY() + 1, table.getZ() + 1.5);
        var menu = new SmithingMenu(7, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), table)); player.containerMenu = menu;
        menu.getSlot(0).set(FmpRegistries.ASSEMBLY_TEMPLATE.toStack()); menu.getSlot(1).set(base); menu.getSlot(2).set(link); return menu;
    }
    private static CompoundTag saved(ServerPlayer player) {
        return CacheLedger.get(player.getServer()).save(new CompoundTag(), player.registryAccess());
    }
    private static void close(ServerPlayer player, SmithingMenu menu) { menu.removed(player); player.containerMenu = player.inventoryMenu; }

    @GameTest(template = "empty")
    public static void manufacturingAndOtherPlayerPreviewsDoNotMaterializeUnusedStock(GameTestHelper helper) {
        for (int target : new int[]{2, 0}) {
            var smith = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
            ConsumptionTests.seed(smith, 0, new ItemStack(Items.STONE), 16);
            var source = AccessGate.resolve(smith).handle().cacheId();
            var pendant = TestPlayers.necklace(smith).getStackInSlot(0);
            var other = TestPlayers.create(helper, pendant.copy());
            @SuppressWarnings("unchecked") var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                    smith.getServer().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("stone_button")).orElseThrow();
            helper.assertTrue(dev.scathiard.feedmepackages.consumption.CraftingService.place(other, recipe, false, false, true)
                    == dev.scathiard.feedmepackages.consumption.CraftingService.Result.OK, "Manufacturing fixture could not prepare its recipe");
            var view = dev.scathiard.feedmepackages.interaction.CacheActions.open(other);
            var result = dev.scathiard.feedmepackages.interaction.CacheActions.execute(other,
                    new dev.scathiard.feedmepackages.interaction.CacheActions.Intent(view.session(), view.record().state().revision(),
                            dev.scathiard.feedmepackages.interaction.CacheActions.Action.TAKE_CURSOR, 0, 8, -1, ""));
            helper.assertTrue(result == dev.scathiard.feedmepackages.interaction.CacheActions.Result.OK, "Manufacturing fixture could not reserve a cursor");
            TestPlayers.necklace(smith).setStackInSlot(0, ItemStack.EMPTY);
            var menu = open(helper, smith, pendant, (target == 0 ? FmpRegistries.PRIVATE_LINK : FmpRegistries.upgradeLink(target)).toStack());
            menu.createResult(); menu.clicked(3, 0, ClickType.PICKUP, smith);
            helper.assertTrue(!menu.getCarried().isEmpty() && !menu.getCarried().getOrDefault(FmpRegistries.PREVIEW.get(), false), "Manufacturing could not commit");
            other.closeContainer();
            var ledger = CacheLedger.get(smith.getServer()); var id = target == 0 ? ledger.personal(smith.getUUID()) : source;
            helper.assertTrue(id != null && ledger.find(id).state().cells().getFirst().amount() == 16
                            && other.getInventory().items.stream().noneMatch(s -> s.is(Items.STONE) || s.is(Items.STONE_BUTTON))
                            && other.containerMenu.getCarried().isEmpty()
                            && dev.scathiard.feedmepackages.consumption.CraftingReservations.reservedCache(source, 0, null) == 0,
                    "Manufacturing plus cancellation duplicated/lost another player's preview");
            close(smith, menu);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void allOrdinaryLevelsUseNativeResultClicksAndPreserveTheFullCache(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer());
        ItemStack pendant = TestPlayers.necklace(player).getStackInSlot(0); UUID network = UUID.randomUUID(); pendant.set(FmpRegistries.NETWORK.get(), network);
        pendant.set(DataComponents.CUSTOM_NAME, Component.literal("My factory pendant")); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        var original = ledger.find(access.handle().cacheId()); var edit = original.state().edit(); var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        edit.filter(0, key); edit.thresholds(0, 2, -1); edit.request(0, UUID.randomUUID(), player.getUUID(), 60, false); edit.insert(0, key, 40);
        ledger.replace(access.handle(), original.state().revision(), original.withState(edit.finish()));
        for (int level = 2; level <= 5; level++) {
            var menu = open(helper, player, pendant, FmpRegistries.upgradeLink(level).toStack()); var before = saved(player);
            for (int preview = 0; preview < 3; preview++) menu.createResult();
            helper.assertTrue(before.equals(saved(player)) && menu.getSlot(3).getItem().getOrDefault(FmpRegistries.PREVIEW.get(), false), "Smithing preview changed a real cache or became usable");
            helper.assertTrue(!menu.getSlot(3).mayPickup(player), "An uncontrolled slot path can take an uncommitted preview");
            menu.clicked(3, 0, ClickType.PICKUP, player); pendant = menu.getCarried();
            helper.assertTrue(pendant.is(FmpRegistries.PENDANT.get()) && !pendant.has(FmpRegistries.PREVIEW.get()) && network.equals(pendant.get(FmpRegistries.NETWORK.get()))
                    && pendant.getHoverName().getString().equals("My factory pendant"), "Manufacturing changed binding/components or yielded a preview");
            var record = ledger.find(access.handle().cacheId());
            helper.assertTrue(record.state().level() == level && record.state().cells().getFirst().amount() == 40 && record.state().pending(0) == 60,
                    "Upgrade failed to preserve stock, orders or its target level");
            helper.assertTrue(menu.getSlot(0).getItem().is(FmpRegistries.ASSEMBLY_TEMPLATE.get()) && menu.getSlot(1).getItem().isEmpty() && menu.getSlot(2).getItem().isEmpty(), "Wrong manufacturing input consumption");
            before = saved(player); menu.clicked(3, 0, ClickType.PICKUP, player); helper.assertTrue(before.equals(saved(player)), "Repeated result click manufactured again");
            menu.setCarried(ItemStack.EMPTY); close(player, menu);
        }
        TestPlayers.necklace(player).setStackInSlot(0, pendant);
        helper.assertTrue(AccessGate.resolve(player).handle().cacheId().equals(access.handle().cacheId()), "Upgraded pendant changed cache identity");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void smithingRejectsFullDestinationsDropAndCloneWithoutChargingInputs(GameTestHelper helper) {
        var player = TestPlayers.create(helper, ItemStack.EMPTY); var ledger = CacheLedger.get(player.getServer());
        var menu = open(helper, player, FmpRegistries.PENDANT.toStack(), FmpRegistries.upgradeLink(2).toStack());
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        menu.setCarried(new ItemStack(Items.STONE, 64)); var before = saved(player);
        menu.clicked(3, 0, ClickType.PICKUP, player); menu.quickMoveStack(player, 3);
        menu.clicked(3, 0, ClickType.THROW, player); player.getAbilities().instabuild = true; menu.clicked(3, 0, ClickType.CLONE, player);
        helper.assertTrue(before.equals(saved(player)) && !menu.getSlot(1).getItem().isEmpty() && !menu.getSlot(2).getItem().isEmpty(), "Refused result manufactured or spent inputs");
        helper.assertTrue(menu.getCarried().is(Items.STONE) && menu.getCarried().getCount() == 64, "Refusal replaced the occupied cursor");
        player.getAbilities().instabuild = false; player.getInventory().setItem(6, ItemStack.EMPTY);
        var moved = menu.quickMoveStack(player, 3); var actual = player.getInventory().getItem(6);
        helper.assertTrue(!moved.isEmpty() && actual.is(FmpRegistries.PENDANT.get()) && ledger.find(actual.get(FmpRegistries.IDENTITY.get())).state().level() == 2,
                "Native quick-move did not use the only safe destination");
        helper.assertTrue(menu.getSlot(1).getItem().isEmpty() && menu.getSlot(2).getItem().isEmpty(), "Quick-move left reusable manufacturing inputs");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void staleCacheAndNetworkMenuVersionsRefuseTheOldPreview(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(player.getServer());
        var pendant = TestPlayers.necklace(player).getStackInSlot(0); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        var menu = open(helper, player, pendant, FmpRegistries.upgradeLink(2).toStack());
        var record = ledger.find(handle.cacheId()); var edit = record.state().edit(); var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        edit.filter(0, key); edit.insert(0, key, 1); ledger.replace(handle, record.state().revision(), record.withState(edit.finish()));
        var before = saved(player); menu.clicked(3, 0, ClickType.PICKUP, player);
        helper.assertTrue(before.equals(saved(player)) && menu.getCarried().isEmpty(), "Stale preview changed the cache");
        var stalePacket = new ServerboundContainerClickPacket(menu.containerId, menu.getStateId() + 1, 3, 0, ClickType.PICKUP, ItemStack.EMPTY, new Int2ObjectOpenHashMap<>());
        player.connection.handleContainerClick(stalePacket);
        helper.assertTrue(before.equals(saved(player)) && menu.getCarried().isEmpty(), "Old native menu version manufactured a result");
        menu.clicked(3, 0, ClickType.PICKUP, player);
        helper.assertTrue(ledger.find(handle.cacheId()).state().level() == 2 && ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 1,
                "Refreshed result did not preserve the concurrent cache change"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void personalPendantIsOwnerLockedAndDegradesForOthers(GameTestHelper helper) {
        var a = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var ledger = CacheLedger.get(a.getServer()); var aHandle = AccessGate.resolve(a).handle();
        var aPendant = TestPlayers.necklace(a).getStackInSlot(0); TestPlayers.necklace(a).setStackInSlot(0, ItemStack.EMPTY);
        UUID network = UUID.randomUUID(); aPendant.set(FmpRegistries.NETWORK.get(), network);
        var menu = open(helper, a, aPendant, FmpRegistries.PRIVATE_LINK.toStack()); menu.clicked(3, 0, ClickType.PICKUP, a);
        var personal = menu.getCarried(); menu.setCarried(ItemStack.EMPTY); close(a, menu);
        helper.assertTrue(personal.is(FmpRegistries.PERSONAL_PENDANT.get()) && a.getUUID().equals(personal.get(FmpRegistries.OWNER.get())), "Personalization did not lock the pendant to its owner");
        // The owner wears it and reaches their own personal cache.
        TestPlayers.necklace(a).setStackInSlot(0, personal.copy()); var aAccess = AccessGate.resolve(a);
        helper.assertTrue(aAccess.active() && aAccess.handle().cacheId().equals(aHandle.cacheId()), "Owner could not use their personal cache");
        // The owner forges an upgrade directly; the personal cache advances one level with no capacity grant.
        TestPlayers.necklace(a).setStackInSlot(0, ItemStack.EMPTY);
        var up = open(helper, a, personal.copy(), FmpRegistries.upgradeLink(2).toStack()); up.clicked(3, 0, ClickType.PICKUP, a);
        var upgraded = up.getCarried(); up.setCarried(ItemStack.EMPTY); close(a, up);
        helper.assertTrue(ledger.find(aHandle.cacheId()).state().level() == 2, "Owner direct upgrade was blocked");
        // A different player equips the pendant: it degrades to a fresh ordinary pendant and cannot reach the owner's cache.
        var b = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); TestPlayers.necklace(b).setStackInSlot(0, upgraded.copy());
        var bAccess = AccessGate.resolve(b);
        helper.assertTrue(bAccess.active() && !bAccess.handle().cacheId().equals(aHandle.cacheId()), "Non-owner gained access to the owner cache");
        helper.assertTrue(TestPlayers.necklace(b).getStackInSlot(0).is(FmpRegistries.PENDANT.get()), "Non-owner personal pendant did not degrade to ordinary");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ownerlessPersonalPendantIsClaimedByItsFirstWearer(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PERSONAL_PENDANT.toStack()); var ledger = CacheLedger.get(player.getServer());
        var access = AccessGate.resolve(player);
        helper.assertTrue(access.active() && access.handle().playerId().equals(player.getUUID())
                && ledger.personal(player.getUUID()) != null, "Ownerless pendant was not claimed by its first wearer");
        var pendant = TestPlayers.necklace(player).getStackInSlot(0);
        helper.assertTrue(player.getUUID().equals(pendant.get(FmpRegistries.OWNER.get())), "Wearing did not record the claiming owner");
        helper.assertTrue(AccessGate.resolve(player).active() && AccessGate.resolve(player).handle().cacheId().equals(access.handle().cacheId()),
                "Claimed pendant lost its personal cache");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativePersonalizationPreservesBindingOrdersAndItsActualResidual(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); helper.assertTrue(ReceiveService.receive(f.player(), f.box(32)), "Private smithing fixture failed");
        UUID target = f.ledger().personalOrCreate(f.player().getUUID()); var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); UUID network = UUID.randomUUID();
        pendant.set(FmpRegistries.NETWORK.get(), network); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        var menu = open(helper, f.player(), pendant, FmpRegistries.PRIVATE_LINK.toStack()); var before = saved(f.player()); menu.createResult();
        helper.assertTrue(before.equals(saved(f.player())), "Personalization preview moved ownership"); menu.clicked(3, 0, ClickType.PICKUP, f.player());
        var actual = menu.getCarried(); var record = f.ledger().find(target);
        helper.assertTrue(actual.is(FmpRegistries.PERSONAL_PENDANT.get()) && !actual.has(FmpRegistries.IDENTITY.get()) && network.equals(actual.get(FmpRegistries.NETWORK.get())), "Personalization result retained old authority or lost binding");
        int remaining = 0; var contents = PackageItem.getContents(record.residual(f.key())); for (int slot = 0; slot < contents.getSlots(); slot++) remaining += contents.getStackInSlot(slot).getCount();
        helper.assertTrue(f.ledger().find(f.handle().cacheId()) == null && record.state().cells().getFirst().amount() == ReceiveTests.FULL && record.state().pending(0) == 32
                && remaining == 24, "Smithing only committed part of personalization"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedMergeAndClosingSmithingPreserveAllInputs(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 1); UUID target = f.ledger().personalOrCreate(f.player().getUUID()); var original = f.ledger().find(target); var edit = original.state().edit();
        edit.filter(0, f.key()); edit.thresholds(0, 2, -1); edit.insert(0, f.key(), Integer.MAX_VALUE);
        f.ledger().replace(new CacheHandle(target, f.player().getUUID(), null), original.state().revision(), original.withState(edit.finish()));
        var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        var before = saved(f.player()); var menu = open(helper, f.player(), pendant, FmpRegistries.PRIVATE_LINK.toStack());
        helper.assertTrue(menu.getSlot(3).getItem().isEmpty() && before.equals(saved(f.player())), "Non-fitting private cache produced a result or changed state");
        menu.clicked(3, 0, ClickType.PICKUP, f.player()); helper.assertTrue(before.equals(saved(f.player())), "Clicking refused private merge changed the ledger");
        // Give the native close path enough physical inventory slots; it must return, not manufacture, the inputs.
        for (int slot = 0; slot < 3; slot++) f.player().getInventory().setItem(slot, ItemStack.EMPTY); close(f.player(), menu);
        helper.assertTrue(before.equals(saved(f.player())) && f.player().getInventory().countItem(FmpRegistries.PENDANT.get()) == 1
                && f.player().getInventory().countItem(FmpRegistries.PRIVATE_LINK.get()) == 1
                && f.player().getInventory().countItem(FmpRegistries.ASSEMBLY_TEMPLATE.get()) == 1, "Native close lost inputs or performed personalization");
        menu.clicked(3, 0, ClickType.PICKUP, f.player()); helper.assertTrue(before.equals(saved(f.player())), "Closed menu retained manufacturing authority"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void firstPersonalizationThenOwnerUpgradesStayOrdered(GameTestHelper helper) {
        var player = TestPlayers.create(helper, ItemStack.EMPTY); var ledger = CacheLedger.get(player.getServer());
        var before = saved(player); var menu = open(helper, player, FmpRegistries.PENDANT.toStack(), FmpRegistries.PRIVATE_LINK.toStack());
        helper.assertTrue(before.equals(saved(player)) && ledger.personal(player.getUUID()) == null, "Fresh private preview registered an owner or a cache");
        menu.clicked(3, 0, ClickType.PICKUP, player); var pendant = menu.getCarried(); var id = ledger.personal(player.getUUID());
        helper.assertTrue(pendant.is(FmpRegistries.PERSONAL_PENDANT.get()) && id != null && ledger.find(id).state().level() == 1
                && player.getUUID().equals(pendant.get(FmpRegistries.OWNER.get())), "Fresh private manufacturing did not lock its owner");
        menu.setCarried(ItemStack.EMPTY); close(player, menu);
        menu = open(helper, player, pendant, FmpRegistries.upgradeLink(3).toStack()); before = saved(player);
        helper.assertTrue(menu.getSlot(3).getItem().isEmpty(), "Owner upgrade skipped the required single-step chain");
        menu.clicked(3, 0, ClickType.PICKUP, player); helper.assertTrue(before.equals(saved(player)), "Wrong-level click advanced the cache");
        menu.getSlot(2).set(FmpRegistries.upgradeLink(2).toStack()); menu.clicked(3, 8, ClickType.SWAP, player);
        helper.assertTrue(player.getInventory().getItem(8).is(FmpRegistries.PERSONAL_PENDANT.get()) && ledger.find(id).state().level() == 2,
                "Owner hotbar upgrade was blocked");
        pendant = player.getInventory().removeItemNoUpdate(8); close(player, menu);
        menu = open(helper, player, pendant, FmpRegistries.upgradeLink(2).toStack());
        helper.assertTrue(menu.getSlot(3).getItem().isEmpty() && ledger.find(id).state().level() == 2, "A same-level step double-advanced or committed another grant");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void safeOffhandResultAndVanillaSmithingRemainIndependent(GameTestHelper helper) {
        var player = TestPlayers.create(helper, ItemStack.EMPTY); var menu = open(helper, player, FmpRegistries.PENDANT.toStack(), FmpRegistries.upgradeLink(2).toStack());
        player.getInventory().offhand.set(0, new ItemStack(Items.SHIELD)); var before = saved(player);
        menu.clicked(3, 40, ClickType.SWAP, player); helper.assertTrue(before.equals(saved(player)) && player.getOffhandItem().is(Items.SHIELD), "Offhand manufacturing replaced an occupied slot");
        player.getInventory().offhand.set(0, ItemStack.EMPTY); menu.clicked(3, 40, ClickType.SWAP, player);
        helper.assertTrue(player.getOffhandItem().is(FmpRegistries.PENDANT.get()) && !player.getOffhandItem().has(FmpRegistries.PREVIEW.get()), "Safe offhand manufacturing returned no usable pendant");
        close(player, menu); menu = open(helper, player, new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.NETHERITE_INGOT));
        menu.getSlot(0).set(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)); before = saved(player);
        helper.assertTrue(!GrowthService.applies(menu) && menu.getSlot(3).getItem().is(Items.NETHERITE_PICKAXE) && menu.getSlot(3).mayPickup(player), "FMP blocked a vanilla smithing preview");
        menu.clicked(3, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.NETHERITE_PICKAXE) && menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty()
                && menu.getSlot(2).getItem().isEmpty() && before.equals(saved(player)), "FMP changed vanilla smithing consumption or its own ledger"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ownerLockedPersonalManufacturingSurvivesAnotherJvm(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var ledger = CacheLedger.get(server);
        var probe = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(GrowthProbe::new, GrowthProbe::load), "fmp_reference_manufacturing_probe");
        boolean prior = !probe.data.isEmpty();
        helper.assertTrue(prior || !Boolean.getBoolean("fmp.requirePriorPersistence"), "Prior manufacturing JVM evidence is required but missing");
        if (prior) {
            helper.assertTrue(!PROCESS.equals(probe.data.getUUID("writer")) && probe.data.getInt("schema") >= 2 && probe.data.getInt("schema") <= CacheLedger.SCHEMA, "Manufacturing evidence is not from another JVM of this model");
            var priorPlayer = TestPlayers.create(helper, probe.data.getUUID("player"), ItemStack.EMPTY);
            helper.assertTrue(server.getPlayerList().load(priorPlayer).isPresent(), "Manufactured pendant player file could not be loaded");
            var pendant = TestPlayers.necklace(priorPlayer).getStackInSlot(0);
            helper.assertTrue(pendant.is(FmpRegistries.PERSONAL_PENDANT.get()) && probe.data.getUUID("owner").equals(pendant.get(FmpRegistries.OWNER.get())), "Personal pendant owner did not survive JVM");
            var handle = AccessGate.resolve(priorPlayer).handle();
            helper.assertTrue(handle.cacheId().equals(probe.data.getUUID("target")) && handle.playerId().equals(priorPlayer.getUUID()), "Personal ownership did not survive JVM");
            helper.assertTrue(ledger.find(handle.cacheId()).state().level() == 2, "Owner direct upgrade did not survive JVM");
            helper.assertTrue(ReceiveService.receive(priorPlayer, priorPlayer.getInventory().getItem(12)) && priorPlayer.getInventory().getItem(12).isEmpty()
                    && ledger.find(handle.cacheId()).state().cells().getFirst().amount() == 37, "Late authenticated package lost its route across JVM");
            TestPlayers.necklace(priorPlayer).setStackInSlot(0, priorPlayer.getInventory().getItem(13));
            helper.assertTrue(!AccessGate.resolve(priorPlayer).active(), "Revoked saved ordinary copy regained access");
        }
        // A fresh player each pass leaves a replayable owner-lock fixture.
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var handle = AccessGate.resolve(player).handle(); var original = ledger.find(handle.cacheId()); var edit = original.state().edit();
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()); UUID order = UUID.randomUUID();
        edit.filter(0, key); edit.thresholds(0, 1, -1); edit.request(0, order, player.getUUID(), 37, false);
        ledger.replace(handle, original.state().revision(), original.withState(edit.finish()));
        var box = PackageItem.containing(List.of(new ItemStack(Items.STONE, 37))); var address = SupplyService.address(player); PackageItem.addAddress(box, address);
        ParcelAuthentication.apply(ledger, player.registryAccess(), box, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), player.getUUID(), order,
                key.encoded(), ledger.find(handle.cacheId()).state().cells().getFirst().filterRevision(), address, false, ""));
        var ordinary = TestPlayers.necklace(player).getStackInSlot(0); var revokedCopy = ordinary.copy(); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        var menu = open(helper, player, ordinary, FmpRegistries.PRIVATE_LINK.toStack()); menu.clicked(3, 0, ClickType.PICKUP, player);
        var pendant = menu.getCarried(); menu.setCarried(ItemStack.EMPTY); close(player, menu);
        var target = ledger.personal(player.getUUID());
        helper.assertTrue(player.getUUID().equals(pendant.get(FmpRegistries.OWNER.get())) && target != null, "Personalization did not lock the owner");
        menu = open(helper, player, pendant, FmpRegistries.upgradeLink(2).toStack()); menu.clicked(3, 0, ClickType.PICKUP, player);
        pendant = menu.getCarried(); menu.setCarried(ItemStack.EMPTY); close(player, menu); TestPlayers.necklace(player).setStackInSlot(0, pendant);
        helper.assertTrue(ledger.find(target).state().level() == 2, "Owner personal cache did not reach level 2");
        player.getInventory().setItem(12, box); player.getInventory().setItem(13, revokedCopy);
        FoundationTests.saveNativePlayer(player);
        var next = new CompoundTag(); next.putUUID("writer", PROCESS); next.putUUID("player", player.getUUID()); next.putUUID("owner", player.getUUID());
        next.putUUID("source", handle.cacheId()); next.putUUID("target", target); next.putInt("schema", CacheLedger.SCHEMA);
        probe.data = next; probe.setDirty();
        FeedMePackages.LOGGER.info("FMP_MANUFACTURING_PERSISTENCE {} schema={} owner-lock/level-2/late-parcel/revoked/player-files", prior ? "PRIOR_JVM_PASSED" : "FIRST_WRITE", CacheLedger.SCHEMA);
        helper.succeed();
    }

    private static final class GrowthProbe extends SavedData {
        private CompoundTag data = new CompoundTag();
        private static GrowthProbe load(CompoundTag tag, HolderLookup.Provider registries) { var probe = new GrowthProbe(); probe.data = tag.copy(); return probe; }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { return data.copy(); }
    }

    @GameTest(template = "empty")
    public static void generatedRecipesUseRealCreateSequencesAndNeverAssembleUncommittedPendants(GameTestHelper helper) {
        var level = helper.getLevel(); var manager = level.getRecipeManager();
        long count = manager.getRecipes().stream().filter(holder -> holder.id().getNamespace().equals(FeedMePackages.MOD_ID)
                && List.of("crafting/", "mechanical_crafting/", "sequenced_assembly/", "smithing/").stream().anyMatch(holder.id().getPath()::startsWith)).count();
        helper.assertTrue(count == 20, "A generated manufacturing recipe was missing or duplicated: " + count);
        for (int target : new int[] {0, 2, 3, 4, 5}) {
            var output = target == 0 ? FmpRegistries.PRIVATE_LINK : FmpRegistries.upgradeLink(target);
            var id = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "sequenced_assembly/" + (target == 0 ? "private_link" : "upgrade_link_" + target));
            var sequence = (SequencedAssemblyRecipe)manager.byKey(id).orElseThrow().value(); helper.assertTrue(sequence.getLoops() == 1, "Growth cost was replaced with repeated sequence loops");
            boolean success = false, scrap = false;
            for (int attempt = 0; attempt < 80; attempt++) {
                ItemStack part = target == 0 ? new ItemStack(Items.ENDER_PEARL) : FmpRegistries.LINK_FRAMES.get(target - 2).toStack(); level.random.setSeed(attempt * 7919L + target);
                for (int step = 0; step < sequence.getSequence().size(); step++) {
                    part = nativeStep(helper, part, sequence.getSequence().get(step).getRecipe());
                    if (step < sequence.getSequence().size() - 1)
                        helper.assertTrue(part.has(AllDataComponents.SEQUENCED_ASSEMBLY) && part.get(AllDataComponents.SEQUENCED_ASSEMBLY).step() == step + 1, "Create did not advance its actual transitional component");
                }
                success |= part.is(output.get()); scrap |= !part.is(output.get());
                helper.assertTrue(!part.has(FmpRegistries.IDENTITY.get()) && !part.has(FmpRegistries.OWNER.get()), "Probabilistic intermediate gained cache authority");
            }
            helper.assertTrue(success && scrap, "Create sequence did not exercise both configured success and scrap");
            if (target == 0) continue;
            var smith = (PendantSmithingRecipe)manager.byKey(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "smithing/supply_chain_pendant_to_" + target)).orElseThrow().value();
            var input = new SmithingRecipeInput(FmpRegistries.ASSEMBLY_TEMPLATE.toStack(), FmpRegistries.PENDANT.toStack(), FmpRegistries.upgradeLink(target).toStack());
            helper.assertTrue(smith.matches(input, level) && smith.assemble(input, level.registryAccess()).isEmpty(), "Generic recipe execution created an uncommitted pendant");
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked") // Each recipe supplies its own runtime type and class; Create verifies the match.
    private static <R extends ProcessingRecipe<?, ?>> ItemStack nativeStep(GameTestHelper helper, ItemStack current, R step) {
        RecipeType<R> type = (RecipeType<R>) step.getType();
        Class<R> recipeClass = (Class<R>) step.getClass();
        var recipe = SequencedAssemblyRecipe.getRecipe(helper.getLevel(), current, type, recipeClass).orElseThrow();
        var outputs = RecipeApplier.applyRecipeOn(helper.getLevel(), current, recipe.value(), false);
        helper.assertTrue(outputs.size() == 1 && outputs.getFirst().getCount() == 1, "Unexpected native sequence output shape");
        return outputs.getFirst();
    }
}
