package dev.scathiard.feedmepackages.gametest;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CacheActions.Action;
import dev.scathiard.feedmepackages.interaction.CacheActions.Result;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ReceiveService;
import dev.scathiard.feedmepackages.network.PanelPackets;
import dev.scathiard.feedmepackages.network.PanelNetwork;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InteractionTests {
    @GameTest(template = "empty")
    public static void blockHitDoesNotAccidentallyClearHeldBinding(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 10); var player = f.player(); var before = f.record();
        var pendant = TestPlayers.necklace(player).getStackInSlot(0); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        var network = UUID.randomUUID(); pendant.set(FmpRegistries.NETWORK.get(), network); player.setItemInHand(InteractionHand.MAIN_HAND, pendant);
        player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 30, 2.5))); player.setXRot(0); player.xRotO = 0; player.setYRot(0); player.yRotO = 0;
        var target = net.minecraft.core.BlockPos.containing(player.getEyePosition().add(0, 0, 2));
        helper.getLevel().setBlockAndUpdate(target, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var ray = net.minecraft.world.item.Item.getPlayerPOVHitResult(helper.getLevel(), player, net.minecraft.world.level.ClipContext.Fluid.NONE);
        helper.assertTrue(ray.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && ray.getBlockPos().equals(target),
                "Fixture did not aim at its real block: eye=" + player.getEyePosition() + " vector=" + player.getLookAngle()
                        + " hit=" + ray.getType() + ":" + ray.getBlockPos() + " target=" + target);
        helper.assertTrue(pendant.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult() == net.minecraft.world.InteractionResult.PASS
                && network.equals(pendant.get(FmpRegistries.NETWORK.get())) && f.record() == before, "A block hit cleared binding or changed cache data");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void curioEquipRejectsSecondPendantBeforeCreatingAnyCache(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        TestPlayers.necklace(player).grow(2);
        for (var candidate : java.util.List.of(FmpRegistries.PENDANT.toStack(), FmpRegistries.PERSONAL_PENDANT.toStack())) {
            var curio = top.theillusivec4.curios.api.CuriosApi.getCurio(candidate).orElseThrow();
            var replacement = new top.theillusivec4.curios.api.SlotContext("necklace", player, 0, false, true);
            var extra = new top.theillusivec4.curios.api.SlotContext("necklace", player, 2, false, true);
            helper.assertTrue(curio.canEquip(replacement) && !curio.canEquip(extra), "Curios accepted a second pendant or blocked a same-slot replacement");
            helper.assertTrue(!curio.canEquip(new top.theillusivec4.curios.api.SlotContext("necklace", player, 2, true, true)), "Cosmetic equip granted a functional entry");
        }
        var ordinary = TestPlayers.necklace(player).getStackInSlot(0); ordinary.set(FmpRegistries.DISABLED.get(), true);
        var second = FmpRegistries.PENDANT.toStack(); TestPlayers.necklace(player).setStackInSlot(2, second);
        helper.assertTrue(AccessGate.resolve(player).status() == AccessGate.Status.MULTIPLE_TERMINALS
                && !ordinary.has(FmpRegistries.IDENTITY.get()) && !second.has(FmpRegistries.IDENTITY.get()), "Multiple gate ran after cache creation or honored the retired disable flag");
        TestPlayers.necklace(player).setStackInSlot(2, ItemStack.EMPTY);
        helper.assertTrue(AccessGate.resolve(player).active(), "Removing extra pendant did not restore ordinary access");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void singleDepositUsesLegalServerAmountInSurvivalAndCreative(GameTestHelper helper) {
        for (boolean creative : new boolean[]{false, true}) {
            var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 2); var player = f.player(); var sent = TestPlayers.nativePackets(player);
            if (creative) player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            var source = new ItemStack(Items.STONE, 32);
            for (int step = 0; step < 3; step++) {
                if (!creative) player.containerMenu.setCarried(source.copy());
                var command = intent(player, Action.DEPOSIT, 0, 1, -1, "");
                var result = creative ? CacheActions.executeCreative(player, command, f.key().encoded(), source.getCount()) : CacheActions.execute(player, command);
                helper.assertTrue(result == (step < 2 ? Result.OK : Result.NO_SPACE) && stock(player, 0) == Math.min(ReceiveTests.FULL, ReceiveTests.FULL - 1 + step), "Right-click did not deposit exactly one up to capacity");
                if (step < 2) {
                    source.shrink(1);
                    if (creative) {
                        var packet = sent.stream().filter(p -> p instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket)
                                .map(p -> (net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket)p).toList().getLast();
                        helper.assertTrue(ItemStack.matches(packet.getCarriedItem(), source) && player.containerMenu.getCarried().isEmpty(), "Creative single deposit duplicated or lost cursor ownership");
                    } else helper.assertTrue(ItemStack.matches(player.containerMenu.getCarried(), source), "Survival single deposit changed the wrong quantity");
                    var replay = creative ? CacheActions.executeCreative(player, command, f.key().encoded(), source.getCount()) : CacheActions.execute(player, command);
                    helper.assertTrue(replay == Result.STALE, "Single deposit replay applied twice");
                }
            }
            if (creative) player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.containerMenu.setCarried(new ItemStack(Items.EGG, 16));
            helper.assertTrue(execute(player, Action.DEPOSIT, 1, 1, "") == Result.OK && stock(player, 1) == 1 && player.containerMenu.getCarried().getCount() == 15, "Empty cell single deposit ignored native 16-stack item");
            for (int bad : new int[]{-1, 2, Integer.MAX_VALUE}) helper.assertTrue(execute(player, Action.DEPOSIT, 1, bad, "") == Result.INVALID_REQUEST && stock(player, 1) == 1, "Unrecognized deposit amount accepted");
            helper.assertTrue(execute(player, Action.DEPOSIT, 1, 0, "") == Result.OK && stock(player, 1) == 16 && player.containerMenu.getCarried().isEmpty(), "Left deposit no longer fills from the real stack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeWithdrawalThenNativePlacementAndCloseDoesNotDuplicate(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 1); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var command = intent(player, Action.TAKE_CURSOR, 0, 1, -1, "");
        helper.assertTrue(CacheActions.executeCreative(player, command, "", 0) == Result.OK
                && f.record().state().cells().getFirst().amount() == 1, "Creative withdrawal failed or deducted on preview");
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.STONE)));
        player.connection.handleContainerClose(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0));
        int total = player.getInventory().items.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(total == 1 && f.record().state().cells().getFirst().amount() == 0 && player.containerMenu.getCarried().isEmpty(),
                "Creative native placement/close duplicated material: inventory=" + total);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        helper.assertTrue(player.getInventory().items.stream().filter(s -> s.is(Items.STONE)).mapToInt(ItemStack::getCount).sum() == 1,
                "Creative duplicate persisted into survival");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bindingUsesDifferentNativeNetworkDevicesWithoutChangingCache(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); var before = f.record();
        var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        f.player().setItemInHand(InteractionHand.MAIN_HAND, pendant);
        var linkPos = new net.minecraft.core.BlockPos(1, 1, 1); var tickerPos = new net.minecraft.core.BlockPos(3, 1, 1);
        helper.setBlock(linkPos, com.simibubi.create.AllBlocks.STOCK_LINK.get().defaultBlockState());
        helper.setBlock(tickerPos, com.simibubi.create.AllBlocks.STOCK_TICKER.get().defaultBlockState());
        helper.runAfterDelay(3, () -> {
            UUID prior = null;
            for (var position : java.util.List.of(linkPos, tickerPos)) {
                var absolute = helper.absolutePos(position);
                var behavior = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(helper.getLevel(), absolute,
                        com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.TYPE);
                helper.assertTrue(behavior != null, "Native fixture does not expose a logistics network behavior");
                var context = new net.minecraft.world.item.context.UseOnContext(f.player(), InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(absolute), net.minecraft.core.Direction.UP, absolute, false));
                f.player().setShiftKeyDown(false);
                helper.assertTrue(pendant.getItem().useOn(context) == net.minecraft.world.InteractionResult.PASS
                        && java.util.Objects.equals(prior, pendant.get(FmpRegistries.NETWORK.get())), "Non-sneaking use changed network binding");
                f.player().setShiftKeyDown(true);
                helper.assertTrue(pendant.getItem().useOn(context).consumesAction() && behavior.freqId.equals(pendant.get(FmpRegistries.NETWORK.get())),
                        "Sneaking use did not bind the actual device network");
                helper.assertTrue(pendant.hasFoil() && !pendant.isEnchanted(), "Network binding did not derive its foil without adding enchantments");
                prior = behavior.freqId;
            }
            f.player().setXRot(-90); f.player().xRotO = -90;
            f.player().setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(2, 40, 2)));
            var airUse = pendant.getItem().use(helper.getLevel(), f.player(), InteractionHand.MAIN_HAND).getResult();
            helper.assertTrue(airUse.consumesAction() && !pendant.has(FmpRegistries.NETWORK.get()),
                    "Air use could not clear the binding: result=" + airUse + " ray=" + f.player().pick(5, 1, false).getType()
                            + " held=" + f.player().getMainHandItem() + " position=" + f.player().position());
            f.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); TestPlayers.necklace(f.player()).setStackInSlot(0, pendant);
            helper.assertTrue(execute(f.player(), Action.CLEAR_NETWORK, 0, 0, "") == Result.INVALID_REQUEST, "Retired unbind command still enabled");
            helper.assertTrue(!pendant.hasFoil(), "Cleared binding retained a synthetic foil");
            helper.assertTrue(f.record() == before && AccessGate.resolve(f.player()).active(), "Binding or clearing changed cache stock, orders or identity");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void duplicatesConflictsAndOrdinaryTransfersUseOneAuthoritativeCache(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); UUID network = UUID.randomUUID();
        pendant.set(FmpRegistries.NETWORK.get(), network); TestPlayers.necklace(f.player()).grow(1);
        var duplicate = pendant.copy(); TestPlayers.necklace(f.player()).setStackInSlot(1, duplicate);
        helper.assertTrue(AccessGate.resolve(f.player()).status() == AccessGate.Status.MULTIPLE_TERMINALS, "Abnormal same-ID copies retained access");
        helper.assertTrue(execute(f.player(), Action.TAKE_CURSOR, 0, 7, "") == Result.NOT_ACTIVE && f.record().state().cells().getFirst().amount() == 32, "Multiple pendants changed stock");
        duplicate.set(FmpRegistries.NETWORK.get(), UUID.randomUUID());
        helper.assertTrue(AccessGate.resolve(f.player()).status() == AccessGate.Status.MULTIPLE_TERMINALS
                && execute(f.player(), Action.TAKE_CURSOR, 0, 1, "") == Result.NOT_ACTIVE, "Network conflict did not pause cache business");
        helper.assertTrue(execute(f.player(), Action.TERMINAL_ENABLED, 1, 0, "") == Result.INVALID_REQUEST, "Retired disable command bypassed unique wear");
        var second = FmpRegistries.PENDANT.toStack(); second.set(FmpRegistries.NETWORK.get(), network); TestPlayers.necklace(f.player()).setStackInSlot(1, second);
        helper.assertTrue(AccessGate.resolve(f.player()).status() == AccessGate.Status.MULTIPLE_TERMINALS
                && execute(f.player(), Action.TAKE_CURSOR, 0, 1, "") == Result.NOT_ACTIVE, "Same-network different-cache ambiguity silently selected a cache");
        helper.assertTrue(!second.has(FmpRegistries.IDENTITY.get()), "Rejected multiple pendants created a cache identity");
        TestPlayers.necklace(f.player()).setStackInSlot(1, ItemStack.EMPTY);
        helper.assertTrue(AccessGate.resolve(f.player()).active(), "Removing the extra pendant did not restore access");
        var stable = f.record(); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        var recipient = TestPlayers.create(helper, pendant);
        helper.assertTrue(!AccessGate.resolve(f.player()).active() && AccessGate.resolve(recipient).handle().cacheId().equals(f.handle().cacheId())
                && f.record() == stable, "Ordinary pendant handoff changed its complete cache");
        TestPlayers.necklace(recipient).setStackInSlot(0, ItemStack.EMPTY);
        var lost = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), 0, 0, 0, pendant); lost.discard();
        helper.assertTrue(!AccessGate.resolve(recipient).active() && f.record() == stable, "Destroyed last legal entry manufactured another access path");
        TestPlayers.necklace(recipient).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        helper.assertTrue(!AccessGate.resolve(recipient).handle().cacheId().equals(f.handle().cacheId()), "New pendant recovered a lost ordinary cache");
        helper.succeed();
    }

    private static CacheActions.Intent intent(ServerPlayer player, Action action, int slot, int first, int second, String template) {
        var view = CacheActions.open(player);
        return new CacheActions.Intent(view.session(), view.record() == null ? -1 : view.record().state().revision(), action, slot, first, second, template);
    }
    private static Result execute(ServerPlayer player, Action action, int slot, int first, String template) {
        return CacheActions.execute(player, intent(player, action, slot, first, -1, template));
    }
    private static int stock(ServerPlayer player, int slot) {
        var access = AccessGate.resolve(player); return CacheLedger.get(player.getServer()).find(access.handle().cacheId()).state().cells().get(slot).amount();
    }

    @GameTest(template = "empty")
    public static void depositsAndSmallWithdrawalsStayOnCursor(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        player.containerMenu.setCarried(new ItemStack(Items.STONE, 64));
        helper.assertTrue(execute(player, Action.DEPOSIT, 0, 0, "") == Result.OK, "First deposit failed");
        player.containerMenu.setCarried(new ItemStack(Items.STONE, 4));
        helper.assertTrue(execute(player, Action.DEPOSIT, 0, 0, "") == Result.OK && stock(player, 0) == 68, "Repeat deposit changed or rejected same filter");
        helper.assertTrue(execute(player, Action.TAKE_CURSOR, 0, 64, "") == Result.OK && stock(player, 0) == 68 && player.containerMenu.getCarried().getCount() == 64, "Full stack transfer is wrong");
        // A cursor preview only becomes a real deduction when the items leave the cursor. Move all 64
        // into the backpack (empty bag slot 0 -> cursor clears) then let the panel settle via snapshot.
        player.getInventory().setItem(0, player.containerMenu.getCarried()); player.containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(player);
        helper.assertTrue(stock(player, 0) == 4, "Placed preview did not deduct (expected S=4)");
        helper.assertTrue(execute(player, Action.TAKE_CURSOR, 0, 64, "") == Result.OK && player.containerMenu.getCarried().getCount() == 4 && stock(player, 0) == 4, "Small remainder was dropped or lost");
        player.getInventory().setItem(1, player.containerMenu.getCarried()); player.containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(player);
        helper.assertTrue(stock(player, 0) == 0, "Placed remainder did not deduct (expected S=0)");
        helper.assertTrue(player.getInventory().getItem(0).getCount() + player.getInventory().getItem(1).getCount() == 68, "Cursor withdrawal did not conserve total");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inventoryInsertionHonorsNativeLimitsAndNoRoom(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        // Level 2: groupCapacity=4, egg(16) capacity=64 — matches the 4×16 deposit sequence
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        var edit = before.state().edit(); edit.upgrade(); ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        for (int i = 0; i < 4; i++) {
            player.containerMenu.setCarried(new ItemStack(Items.EGG, 16));
            helper.assertTrue(execute(player, Action.DEPOSIT, 0, 0, "") == Result.OK, "Native stack deposit failed");
        }
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        player.getInventory().setItem(0, new ItemStack(Items.EGG, 15)); player.getInventory().setItem(1, ItemStack.EMPTY);
        var move = intent(player, Action.TAKE_INVENTORY, 0, Integer.MAX_VALUE, -1, "");
        helper.assertTrue(CacheActions.execute(player, move) == Result.OK, "Partial insertion failed");
        // Shift-take settles immediately: the inserted 17 (slot0 15->16 + slot1 empty->16) is deducted now.
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 16 && player.getInventory().getItem(1).getCount() == 16 && stock(player, 0) == 47, "Native 16 stack limit was ignored");
        helper.assertTrue(CacheActions.execute(player, move) == Result.STALE && stock(player, 0) == 47, "Replayed transfer consumed twice");
        helper.assertTrue(execute(player, Action.TAKE_INVENTORY, 0, 64, "") == Result.NO_SPACE && stock(player, 0) == 47, "No-room operation discarded stock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void retiredGhostIsRejectedAndRealFiltersRemainSafe(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()).encoded();
        helper.assertTrue(execute(player, Action.SET_GHOST, 0, 0, key) == Result.INVALID_REQUEST && stock(player, 0) == 0, "Retired ghost command set a filter");
        player.containerMenu.setCarried(new ItemStack(Items.STONE, 3)); execute(player, Action.DEPOSIT, 0, 0, "");
        player.containerMenu.setCarried(new ItemStack(Items.STONE));
        helper.assertTrue(execute(player, Action.DEPOSIT, 1, 1, "") == Result.DUPLICATE_FILTER && stock(player, 1) == 0, "Duplicate real variant accepted");
        helper.assertTrue(execute(player, Action.CLEAR_FILTER, 0, 0, "") == Result.FILTER_OCCUPIED, "Nonempty filter cleared");
        var other = ItemVariantKey.of(new ItemStack(Items.DIRT), player.registryAccess()).encoded();
        helper.assertTrue(execute(player, Action.SET_GHOST, 0, 0, other) == Result.INVALID_REQUEST && stock(player, 0) == 3, "Retired ghost replaced a filter");
        player.containerMenu.setCarried(new ItemStack(Items.DIRT));
        helper.assertTrue(execute(player, Action.DEPOSIT, 0, 1, "") == Result.FILTER_OCCUPIED && stock(player, 0) == 3, "Nonempty real replacement discarded material");
        helper.assertTrue(CacheActions.execute(player, intent(player, Action.THRESHOLDS, 0, 129, -1, "")) == Result.INVALID_REQUEST, "Out-of-range threshold accepted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void closedAndUnwornSessionsCannotAct(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32);
        var command = intent(f.player(), Action.TAKE_CURSOR, 0, 16, -1, "");
        f.player().closeContainer();
        helper.assertTrue(CacheActions.execute(f.player(), command) == Result.STALE && f.record().state().cells().getFirst().amount() == 32, "Closed menu retained action authority");
        command = intent(f.player(), Action.TAKE_CURSOR, 0, 16, -1, "");
        var pendant = TestPlayers.necklace(f.player()).getStackInSlot(0); TestPlayers.necklace(f.player()).setStackInSlot(0, ItemStack.EMPTY);
        helper.assertTrue(CacheActions.execute(f.player(), command) == Result.NOT_ACTIVE, "Unequipped player could use old panel");
        CacheActions.validateOpenContext(f.player()); TestPlayers.necklace(f.player()).setStackInSlot(0, pendant);
        helper.assertTrue(CacheActions.execute(f.player(), command) == Result.STALE && f.record().state().cells().getFirst().amount() == 32, "Reequip revived invalidated intent");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void residualIsNotExposedByRetiredManualIntent(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); var box = f.box(32);
        helper.assertTrue(ReceiveService.receive(f.player(), box), "Residual setup failed");
        helper.assertTrue(f.record().state().pending(0) == 32, "One 32-item arrival must leave the other half of the 64-item request pending");
        var before = f.record();
        helper.assertTrue(execute(f.player(), Action.TAKE_RESIDUAL, 0, 0, "") == Result.INVALID_REQUEST && f.record() == before,
                "Retired manual residual command changed ownership");
        f.player().getInventory().setItem(0, ItemStack.EMPTY);
        ReceiveService.resume(f.player());
        helper.assertTrue(f.record() == before && f.player().getInventory().getItem(0).isEmpty(), "A valid full-cache remainder was ejected unnecessarily");
        helper.assertTrue(f.record().state().pending(0) == 32, "Manual opening repeated arrival accounting or erased an undelivered fragment");
        f.extract(24); ReceiveService.resume(f.player());
        helper.assertTrue(f.residual().isEmpty() && f.record().state().cells().getFirst().amount() == ReceiveTests.FULL, "Background resume lost material");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeCursorHasExplicitServerPermission(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var sent = TestPlayers.nativePackets(player);
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess()).encoded();
        var deposit = intent(player, Action.DEPOSIT, 0, 0, -1, "");
        player.getAbilities().instabuild = false;
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 32) == Result.INVALID_REQUEST && stock(player, 0) == 0, "Survival forged a creative item source");
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 65) == Result.INVALID_ITEM && stock(player, 0) == 0, "Illegal creative stack accepted");
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 32) == Result.OK && stock(player, 0) == 32
                && player.containerMenu.getCarried().isEmpty(), "Authorized creative cursor failed to deposit");
        helper.assertTrue(CacheActions.executeCreative(player, deposit, key, 32) == Result.STALE && stock(player, 0) == 32, "Creative replay changed the cache twice");
        var take = intent(player, Action.TAKE_CURSOR, 0, 16, -1, "");
        helper.assertTrue(CacheActions.execute(player, take) == Result.INVALID_REQUEST && stock(player, 0) == 32,
                "Creative caller bypassed native cursor ownership with a survival-shaped command");
        helper.assertTrue(CacheActions.executeCreative(player, take, "", 0) == Result.OK && stock(player, 0) == 32
                && player.containerMenu.getCarried().isEmpty(), "Creative withdrawal created another server-owned cursor");
        var packet = sent.stream().filter(p -> p instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket)
                .map(p -> (net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket)p).toList().getLast();
        helper.assertTrue(packet.getCarriedItem().is(Items.STONE) && packet.getCarriedItem().getCount() == 16,
                "Creative withdrawal was not handed to the native client cursor");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelWindowAndServerSessionAreIndependent(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(f.player(), new PanelPackets.Query(window, f.player().containerMenu.containerId, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 16, -1, "");
        var wrongWindow = new PanelPackets.Command(UUID.randomUUID(), 1, take, false, "", 0);
        helper.assertTrue(PanelNetwork.command(f.player(), wrongWindow) == null && stock(f.player(), 0) == 32, "Wrong window submitted an operation");
        var packet = new PanelPackets.Command(window, 1, take, false, "", 0);
        var reply = PanelNetwork.command(f.player(), packet);
        helper.assertTrue(reply.result() == Result.OK && reply.acknowledged() == 1 && stock(f.player(), 0) == 32, "Network action did not acknowledge its actual commit");
        // Settle the cursor preview by placing all 16 into the backpack and letting the panel validate.
        f.player().getInventory().setItem(0, f.player().containerMenu.getCarried()); f.player().containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(f.player());
        helper.assertTrue(stock(f.player(), 0) == 16, "Network take did not settle (expected S=16)");
        helper.assertTrue(PanelNetwork.command(f.player(), packet).result() == Result.STALE && stock(f.player(), 0) == 16, "Network replay consumed twice");
        f.player().tickCount += 4; UUID nextWindow = UUID.randomUUID();
        var next = PanelNetwork.query(f.player(), new PanelPackets.Query(nextWindow, f.player().containerMenu.containerId, true));
        PanelNetwork.query(f.player(), new PanelPackets.Query(window, 0, false));
        var preference = new CacheActions.Intent(next.session(), next.revision(), Action.PREFERENCE, 0, 1, -1, "");
        helper.assertTrue(PanelNetwork.command(f.player(), new PanelPackets.Command(nextWindow, 2, preference, false, "", 0)).result() == Result.INVALID_REQUEST,
                "Late close from old window revoked the new window");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releasePreviewIsAcceptedThroughWire(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 32); UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(f.player(), new PanelPackets.Query(window, f.player().containerMenu.containerId, true));
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 16, -1, "");
        helper.assertTrue(PanelNetwork.command(f.player(), new PanelPackets.Command(window, 1, take, false, "", 0)).result() == Result.OK
                && stock(f.player(), 0) == 32 && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 16,
                "Preview take through wire failed");
        // Sequence 0 must be rejected as STALE and the hold stays (old release must not cancel a live hold).
        var stale = new PanelPackets.Command(window, 0, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, -1, -1, ""), false, "", 0);
        helper.assertTrue(PanelNetwork.command(f.player(), stale).result() == Result.STALE
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 16,
                "Sequence-0 release was not STALE / released the hold");
        // An incrementing sequence is accepted: hold -> 0, cache stock unchanged.
        var ok = new PanelPackets.Command(window, 2, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, -1, -1, ""), false, "", 0);
        helper.assertTrue(PanelNetwork.command(f.player(), ok).result() == Result.OK
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0
                && stock(f.player(), 0) == 32, "Release was not accepted / did not clear hold / altered cache");
        // Replay of the same release sequence is STALE (replay guard retained).
        helper.assertTrue(PanelNetwork.command(f.player(), ok).result() == Result.STALE, "Release replay was not STALE");
        f.player().containerMenu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void releasePreviewAcceptedForCreativeHold(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        UUID window = UUID.randomUUID();
        var view = PanelNetwork.query(player, new PanelPackets.Query(window, player.containerMenu.containerId, true));
        // Real creative TAKE through the wire (creativeCursor=true) -> creative hold P=1, S stays 3.
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 1, -1, "");
        helper.assertTrue(PanelNetwork.command(player, new PanelPackets.Command(window, 1, take, true, "", 0)).result() == Result.OK
                && stock(player, 0) == 3 && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1,
                "Creative take through wire failed");
        // RELEASE_PREVIEW release of the creative hold -> reserved 0, cache stays 3.
        var rel = new PanelPackets.Command(window, 2, new CacheActions.Intent(view.session(), view.revision(), Action.RELEASE_PREVIEW, -1, -1, -1, ""), false, "", 0);
        helper.assertTrue(PanelNetwork.command(player, rel).result() == Result.OK
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0
                && stock(player, 0) == 3, "Creative hold release failed to clear the reservation");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeDropSettlesThroughWire(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var view = CacheActions.open(player);
        var take = new CacheActions.Intent(view.session(), view.record().state().revision(), Action.TAKE_CURSOR, 0, 1, -1, "");
        helper.assertTrue(CacheActions.executeCreative(player, take, "", 0) == Result.OK
                && stock(player, 0) == 3
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 1, "preview take failed");
        // Negative slot = creative discard: creativeDropped debits the preview and drops a real ItemEntity,
        // so the cache is NOT silently reduced with no world entity (the risk my negative-slot cancel caused).
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(-1, new ItemStack(Items.STONE)));
        helper.assertTrue(stock(player, 0) == 2
                && dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 0,
                "creative drop did not settle cache/hold (S=" + stock(player, 0) + ")");
        int dropped = 0;
        for (var e : helper.getLevel().getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.getItem().is(Items.STONE)) dropped += ie.getItem().getCount();
        }
        helper.assertTrue(dropped >= 1, "creative drop did not leave the real item (dropped=" + dropped + ")");
        player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void panelWireIsBoundedAndRoundTripsLargeTemplates(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); var access = AccessGate.resolve(player);
        var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        var edit = before.state().edit(); for (int level = 1; level < 5; level++) edit.upgrade();
        for (int i = 0; i < 36; i++) {
            var item = new ItemStack(Items.STONE); item.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(860) + i));
            var key = ItemVariantKey.of(item, player.registryAccess()); edit.filter(i, key); edit.insert(i, key, 2048);
        }
        ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        var view = PanelNetwork.query(player, new PanelPackets.Query(UUID.randomUUID(), 0, true));
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            PanelPackets.Snapshot.CODEC.encode(wire, view);
            helper.assertTrue(wire.readableBytes() < PanelPackets.S2C_LIMIT, "Largest current cache exceeded snapshot budget");
            var decoded = PanelPackets.Snapshot.CODEC.decode(wire);
            helper.assertTrue(decoded.equals(view) && decoded.cells().size() == 36, "Wire lost or truncated exact templates");
        } finally { wire.release(); }
        var oversized = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            oversized.writeZero(PanelPackets.C2S_LIMIT + 1); boolean refused = false;
            try { PanelPackets.Command.CODEC.decode(oversized); } catch (DecoderException expected) { refused = true; }
            helper.assertTrue(refused, "Oversized command reached allocation/interpretation");
        } finally { oversized.release(); }
        var trailing = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            PanelPackets.Query.CODEC.encode(trailing, new PanelPackets.Query(UUID.randomUUID(), 0, true)); trailing.writeByte(0);
            boolean refused = false; try { PanelPackets.Query.CODEC.decode(trailing); } catch (DecoderException expected) { refused = true; }
            helper.assertTrue(refused, "Trailing packet data accepted");
        } finally { trailing.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void multiplePlayersKeepSnapshotsAndIdleWorkBounded(GameTestHelper helper) {
        var fixtures = new java.util.ArrayList<ReceiveTests.Fixture>();
        var windows = new java.util.ArrayList<UUID>();
        var views = new java.util.ArrayList<PanelPackets.Snapshot>();
        var initial = new java.util.ArrayList<dev.scathiard.feedmepackages.storage.CacheRecord>();
        for (int p = 0; p < 32; p++) {
            var f = ReceiveTests.setup(helper, p + 1); fixtures.add(f); windows.add(UUID.randomUUID()); views.add(null);
            var before = f.record(); var edit = before.state().edit();
            for (int level = 1; level < 5; level++) edit.upgrade();
            for (int slot = 1; slot < 36; slot++) {
                var stack = new ItemStack(Items.STONE);
                stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(860) + slot));
                var key = ItemVariantKey.of(stack, f.player().registryAccess()); edit.filter(slot, key); edit.insert(slot, key, 2048);
            }
            f.ledger().replace(f.handle(), before.state().revision(), before.withState(edit.finish())); initial.add(f.record());
        }
        helper.assertTrue(fixtures.stream().map(f -> f.handle().cacheId()).distinct().count() == 32, "Players share a supposedly independent cache");
        int replies = 0, hints = 0;
        long started = System.nanoTime();
        for (int tick = 0; tick < 200; tick++) for (int p = 0; p < fixtures.size(); p++) {
            var f = fixtures.get(p); f.player().tickCount = tick;
            dev.scathiard.feedmepackages.logistics.SupplyService.tick(f.player());
            var view = PanelNetwork.query(f.player(), new PanelPackets.Query(windows.get(p), 0, true));
            if (view != null) {
                replies++; views.set(p, view);
                helper.assertTrue(view.cells().size() == 36 && view.cells().getFirst().amount() == p + 1,
                        "Another player's stock leaked into a snapshot");
            }
            var hint = dev.scathiard.feedmepackages.network.MaterialHints.next(f.player());
            if (hint != null) { hints++; helper.assertTrue(tick == 0 && hint.full(), "Idle hints were repeatedly transmitted"); }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        helper.assertTrue(replies == 32 * 50 && hints == 32, "Query rate limiting or idle material suppression failed");
        for (int p = 0; p < fixtures.size(); p++) helper.assertTrue(fixtures.get(p).record() == initial.get(p), "Idle work dirtied a cache or emitted a new request");
        var f = fixtures.getFirst(); var view = views.getFirst();
        var take = new CacheActions.Intent(view.session(), view.revision(), Action.TAKE_CURSOR, 0, 1, -1, "");
        var result = PanelNetwork.command(f.player(), new PanelPackets.Command(windows.getFirst(), 1, take, false, "", 0));
        helper.assertTrue(result != null && result.result() == Result.OK && f.player().containerMenu.getCarried().getCount() == 1,
                "A real command failed after the multi-player query load");
        f.player().getInventory().setItem(0, f.player().containerMenu.getCarried()); f.player().containerMenu.setCarried(ItemStack.EMPTY);
        CacheActions.snapshot(f.player());
        helper.assertTrue(f.record().state().cells().getFirst().amount() == 0, "Bounded consumption take did not deduct");
        var delta = dev.scathiard.feedmepackages.network.MaterialHints.next(f.player());
        helper.assertTrue(delta != null && !delta.full() && delta.templates().isEmpty() && delta.amounts().getFirst() == 0,
                "A single-player consumption did not produce its bounded count-only delta");
        for (int p = 1; p < fixtures.size(); p++) helper.assertTrue(fixtures.get(p).record() == initial.get(p)
                && dev.scathiard.feedmepackages.network.MaterialHints.next(fixtures.get(p).player()) == null, "Consumption affected another player");
        FeedMePackages.LOGGER.info("FMP_LOAD_OBSERVATION players=32 cells=36 logicalTicks=200 queries=6400 replies={} initialHints={} elapsedMs={} simulated-server-players/no-network-throughput-claim",
                replies, hints, elapsedMillis);
        helper.succeed();
    }
}
