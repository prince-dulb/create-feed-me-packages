package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.logistics.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.UUID;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SupplyTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void nativeRestockBillsOnlyTheCraftedRecipeNotItsPreparation(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0); var player = f.player(); var network = UUID.randomUUID();
        player.getInventory().setItem(2, ItemStack.EMPTY);
        TestPlayers.necklace(player).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
        var before = f.record(); var edit = before.state().edit(); edit.reset(0);
        edit.filter(0, dev.scathiard.feedmepackages.item.ItemVariantKey.of(new ItemStack(Items.OAK_LOG), player.registryAccess()));
        edit.thresholds(0, 1, -1); f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        BlockPos packing = new BlockPos(2, 1, 2), chestPos = packing.south(), linkPos = packing.above();
        helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
        helper.setBlock(packing, AllBlocks.PACKAGER.get().defaultBlockState().setValue(PackagerBlock.FACING, Direction.NORTH));
        helper.setBlock(linkPos, AllBlocks.STOCK_LINK.get().defaultBlockState().setValue(PackagerLinkBlock.FACE, AttachFace.FLOOR));
        var chest = (ChestBlockEntity)helper.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 64)); chest.setItem(1, new ItemStack(Items.OAK_LOG, 64)); chest.setChanged();
        int[] stage = {0};
        helper.onEachTick(() -> {
            if (helper.getTick() < 3) return;
            var packager = (PackagerBlockEntity)helper.getBlockEntity(packing);
            if (stage[0] == 0) {
                var link = (PackagerLinkBlockEntity)helper.getBlockEntity(linkPos);
                LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
                SupplyService.tick(player); stage[0] = 1;
            }
            var handler = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, helper.absolutePos(packing), Direction.NORTH);
            if (handler == null) return;
            if (stage[0] == 1) {
                if (!PackageItem.isPackage(handler.extractItem(0, 1, true))) return;
                var box = handler.extractItem(0, 1, false);
                helper.assertTrue(ReceiveService.receive(player, box) && box.isEmpty() && f.record().state().cells().getFirst().amount() == 64,
                        "Initial native 64-log delivery was not received");
                @SuppressWarnings("unchecked") var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                        player.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
                helper.assertTrue(dev.scathiard.feedmepackages.consumption.CraftingService.place(player, recipe, false, false, true)
                        == dev.scathiard.feedmepackages.consumption.CraftingService.Result.OK, "Preparation failed after delivery");
                SupplyService.tick(player);
                helper.assertTrue(f.record().state().orders().isEmpty() && f.record().state().cells().getFirst().amount() == 64,
                        "Preparing the recipe issued another real purchase");
                // Also probe a reentrant supply callback during a maximum native preparation.
                dev.scathiard.feedmepackages.consumption.CraftingService.place(player, recipe, true, false, true);
                try (var scope = dev.scathiard.feedmepackages.consumption.CraftingReservations.begin(player, player.containerMenu, 0, 0, net.minecraft.world.inventory.ClickType.PICKUP)) {
                    helper.assertTrue(f.record().state().cells().getFirst().amount() == 0, "Maximum preparation was not materialized for the native call");
                    SupplyService.tick(player);
                    helper.assertTrue(f.record().state().orders().isEmpty(), "Reentrant supply observed a temporary crafting debit as a purchase deficit");
                    player.containerMenu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
                }
                player.closeContainer();
                helper.assertTrue(f.record().state().cells().getFirst().amount() == 63 && f.record().state().requestable(0) == 1
                        && player.getInventory().items.stream().filter(s -> s.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum() == 4,
                        "One real craft and close charged more than one log");
                stage[0] = 2;
            }
            if (stage[0] == 2) {
                SupplyService.tick(player);
                helper.assertTrue(f.record().state().pending(0) <= 1 && f.record().state().cells().getFirst().amount() == 63,
                        "Repeated supply ticks ordered an unused next recipe");
                if (!PackageItem.isPackage(handler.extractItem(0, 1, true))) return;
                var box = handler.extractItem(0, 1, false); var contents = PackageItem.getContents(box); int delivered = 0;
                for (int i = 0; i < contents.getSlots(); i++) delivered += contents.getStackInSlot(i).getCount();
                helper.assertTrue(delivered == 1 && ReceiveService.receive(player, box) && box.isEmpty(), "Restock delivered more than the one consumed log");
                SupplyService.tick(player);
                int remaining = chest.getItem(0).getCount() + chest.getItem(1).getCount();
                helper.assertTrue(remaining == 63 && f.record().state().cells().getFirst().amount() == 64 && f.record().state().orders().isEmpty()
                        && packager.heldBox.isEmpty() && packager.queuedExitingPackages.isEmpty(), "Native source/cache/order conservation failed");
                FeedMePackages.LOGGER.info("FMP_CRAFT_RESTOCK_PASSED initialDelivery=64 realConsumption=1 restock=1 cache=64 factory=63");
                stage[0] = 3; helper.succeed();
            }
        });
    }
    /** A physical network source for the native flight tests, not a fabricated/sealed test parcel. */
    static java.util.function.Supplier<ItemStack> outboundFromNetwork(GameTestHelper helper, ReceiveTests.Fixture fixture) {
        var network = UUID.randomUUID(); BlockPos packing = new BlockPos(3, 1, 1), chestPos = packing.south(), linkPos = packing.above();
        helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
        helper.setBlock(packing, AllBlocks.PACKAGER.get().defaultBlockState().setValue(PackagerBlock.FACING, Direction.NORTH));
        helper.setBlock(linkPos, AllBlocks.STOCK_LINK.get().defaultBlockState().setValue(PackagerLinkBlock.FACE, AttachFace.FLOOR));
        var chest = (ChestBlockEntity)helper.getBlockEntity(chestPos); chest.setItem(0, new ItemStack(Items.STONE, 64)); chest.setChanged();
        var configured = new boolean[1];
        return () -> {
            helper.assertTrue(helper.getTick() >= 3, "Waiting for the physical packager connection");
            var packager = (PackagerBlockEntity)helper.getBlockEntity(packing);
            if (!configured[0]) {
                var before = fixture.record(); var edit = before.state().edit(); edit.reset(0); edit.thresholds(0, 2, -1);
                fixture.ledger().replace(fixture.handle(), before.state().revision(), before.withState(edit.finish()));
                TestPlayers.necklace(fixture.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
                var link = (PackagerLinkBlockEntity)helper.getBlockEntity(linkPos);
                LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
                configured[0] = true;
            }
            SupplyService.tick(fixture.player());
            var handler = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, helper.absolutePos(packing), Direction.NORTH);
            helper.assertTrue(handler != null, "Native packager has no outward item capability");
            var view = handler.extractItem(0, 1, true);
            helper.assertTrue(PackageItem.isPackage(view), "Waiting for the native requested parcel to finish packing");
            var seal = view.get(FmpRegistries.PARCEL_SEAL.get());
            helper.assertTrue(seal != null && seal.cacheId().equals(fixture.handle().cacheId()) && fixture.record().state().pending(0) == 64,
                    "Native outbound parcel lost its cache target or request reservation");
            helper.assertTrue(chest.getItem(0).isEmpty() && packager.queuedExitingPackages.isEmpty(), "Network produced the wrong number of parcels");
            var actual = handler.extractItem(0, 1, false);
            helper.assertTrue(ItemStack.matches(actual, view) && packager.heldBox.isEmpty(), "Native output extraction did not transfer exactly one real parcel");
            return actual;
        };
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void realPackagerSplitsAndSealsExactStock(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0); var network = UUID.randomUUID();
        TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), network);
        var before = f.record(); var edit = before.state().edit(); edit.reset(0);
        for (int i = 1; i < 5; i++) edit.upgrade();
        edit.thresholds(0, 16, -1); f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        BlockPos packingPos = new BlockPos(2, 1, 2), chestPos = packingPos.south(), linkPos = packingPos.above();
        helper.setBlock(chestPos, Blocks.CHEST.defaultBlockState());
        helper.setBlock(packingPos, AllBlocks.PACKAGER.get().defaultBlockState().setValue(PackagerBlock.FACING, Direction.NORTH));
        helper.setBlock(linkPos, AllBlocks.STOCK_LINK.get().defaultBlockState().setValue(PackagerLinkBlock.FACE, AttachFace.FLOOR));
        var chest = (ChestBlockEntity)helper.getBlockEntity(chestPos);
        for (int i = 0; i < 16; i++) chest.setItem(i, new ItemStack(Items.STONE, 64));
        var special = new ItemStack(Items.STONE, 16); special.set(DataComponents.CUSTOM_NAME, Component.literal("Not this variant"));
        chest.setItem(16, special.copy()); chest.setChanged();
        helper.runAfterDelay(3, () -> {
            var packager = (PackagerBlockEntity)helper.getBlockEntity(packingPos);
            var link = (PackagerLinkBlockEntity)helper.getBlockEntity(linkPos);
            LogisticallyLinkedBehaviour.remove(link.behaviour); link.behaviour.freqId = network; LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
            helper.assertTrue(link.getPackager() == packager && packager.targetInventory.hasInventory(), "Native fixture is not connected");
            SupplyService.tick(f.player());
            var boxes = new ArrayList<ItemStack>();
            if (!packager.heldBox.isEmpty()) boxes.add(packager.heldBox);
            for (var queued : packager.queuedExitingPackages) {
                helper.assertTrue(queued.count == 1, "Unexpected native queue multiplicity"); boxes.add(queued.stack);
            }
            helper.assertTrue(boxes.size() == 2, "1024 items did not become two native packages");
            var parcelIds = new HashSet<UUID>(); int count = 0;
            for (var box : boxes) {
                var seal = box.get(FmpRegistries.PARCEL_SEAL.get());
                helper.assertTrue(seal != null && seal.cacheId().equals(f.handle().cacheId()), "Actual native package lacks fixed cache route");
                helper.assertTrue(ParcelAuthentication.valid(f.ledger(), f.player().registryAccess(), box, seal), "Native package seal is invalid");
                helper.assertTrue(parcelIds.add(seal.parcelId()), "Two split parcels share a receipt identity");
                var contents = PackageItem.getContents(box);
                for (int i = 0; i < contents.getSlots(); i++) count += contents.getStackInSlot(i).getCount();
            }
            helper.assertTrue(count == 1024 && f.record().state().pending(0) == 1024, "Dispatched and reserved quantities disagree");
            for (int i = 0; i < 16; i++) helper.assertTrue(chest.getItem(i).isEmpty(), "Native packager did not consume source stock");
            helper.assertTrue(ItemStack.matches(chest.getItem(16), special), "Network packed the wrong component variant");
            SupplyService.tick(f.player());
            helper.assertTrue(packager.queuedExitingPackages.size() == 1 && f.record().state().orders().size() == 1, "Repeated demand issued duplicate packages");
            for (var box : boxes) helper.assertTrue(ReceiveService.receive(f.player(), box), "Actual created parcel could not be received");
            helper.assertTrue(f.record().state().cells().getFirst().amount() == 1024 && f.record().state().orders().isEmpty(), "Native round trip lost or duplicated stock");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void missingNetworkDoesNotFabricateOrders(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0); var before = f.record(); var edit = before.state().edit(); edit.reset(0);
        f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish()));
        TestPlayers.necklace(f.player()).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        SupplyService.tick(f.player()); SupplyService.tick(f.player());
        helper.assertTrue(f.record().state().orders().isEmpty() && f.record().state().arrow(0) == CacheState.Arrow.UNSUPPLIED, "Unavailable network fabricated a dispatch");
        helper.succeed();
    }
}
