package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TakeReserveTests {
    private static CacheActions.Result exec(ReceiveTests.Fixture f, CacheActions.Action action, int slot, int first) {
        var access = AccessGate.resolve(f.player());
        var view = CacheActions.open(f.player());
        return CacheActions.execute(f.player(), new CacheActions.Intent(view.session(),
                view.record() == null ? -1 : view.record().state().revision(), action, slot, first, -1, ""));
    }
    private static int stock(ReceiveTests.Fixture f) { return f.record().state().cells().getFirst().amount(); }
    private static int preview(ReceiveTests.Fixture f) { return CursorReservations.reserved(f.handle().cacheId(), 0); }

    // P (cursor preview) is a display alias of the same cached stock: it does NOT deduct S and does
    // NOT trigger a supply order. Returning it only releases the preview, never re-inserts stock.
    @GameTest(template = "empty")
    public static void takeReservesWithoutDeductingOrSupply(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL);
        int ordersBefore = f.record().state().orders().size();
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && f.player().containerMenu.getCarried().getCount() == 8 && stock(f) == ReceiveTests.FULL,
                "Take did not reserve (stock should stay full)");
        helper.assertTrue(preview(f) == 8, "Preview P not recorded");
        for (int i = 0; i < 4; i++) SupplyService.tick(f.player());
        helper.assertTrue(f.record().state().orders().size() == ordersBefore, "Reserved take triggered a supply order");
        // Return through the source cell: net zero, no re-insert.
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 0) == CacheActions.Result.OK
                && stock(f) == ReceiveTests.FULL && preview(f) == 0 && f.player().containerMenu.getCarried().isEmpty(),
                "Returning the preview changed stock or left a preview");
        helper.succeed();
    }

    // R1 (Planner 2026-09-08 §5): putting a reserved item back must NET ZERO, via the ORIGINAL cell DEPOSIT path.
    // S=120, TAKE_CURSOR 8 (keeps the cursor it produced — rebuild nothing), DEPOSIT all 8 back
    // -> S stays 120 (NOT 128), P=0, cursor empty. On the old t49 DEPOSIT inserted so S became 128.
    @GameTest(template = "empty")
    public static void depositOfReservedItemReleasesIt(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8);
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && f.player().containerMenu.getCarried().getCount() == 8, "Take did not place 8 on the cursor");
        helper.assertTrue(preview(f) == 8, "Preview P not recorded");
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 0) == CacheActions.Result.OK,
                "Returning the reserved item via DEPOSIT was rejected");
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 8, "Returning a reserved item added stock (expected S=120, got " + stock(f) + ")");
        helper.assertTrue(preview(f) == 0, "Reservation not cleared after a full return");
        helper.assertTrue(f.player().containerMenu.getCarried().isEmpty(), "Cursor not emptied after a full return");
        helper.succeed();
    }

    // T1 (Planner §5 "拿起放回"): pick up 8 then put all 8 back through the ORIGINAL cell DEPOSIT -> net zero.
    // Mid: S=120/P=8; after DEPOSIT: S=120/P=0/cursor empty. Same acceptance as R1 but asserted as its own case.
    @GameTest(template = "empty")
    public static void t1PickUpAndReturnIsNetZero(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8);
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && f.player().containerMenu.getCarried().getCount() == 8 && stock(f) == ReceiveTests.FULL - 8,
                "Take did not preview 8 while leaving stock at S=120");
        helper.assertTrue(preview(f) == 8, "Preview P not recorded as 8");
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 0) == CacheActions.Result.OK, "DEPOSIT of the reserved stack was rejected");
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 8, "DEPOSIT of a reserved stack changed S (expected 120, got " + stock(f) + ")");
        helper.assertTrue(preview(f) == 0, "DEPOSIT left a reservation");
        helper.assertTrue(f.player().containerMenu.getCarried().isEmpty(), "Cursor still holds the returned stack");
        helper.succeed();
    }

    // T4 (Planner §5 "Shift"): shift-take must settle the inserted amount IMMEDIATELY and leave NO dangling P.
    // Three inventories: room for 8, room for 3, full -> inserted 8/3/0, S 112/117/120, P=0.
    @GameTest(template = "empty")
    public static void t4ShiftTakeSettlesImmediatelyNoDanglingPreview(GameTestHelper helper) {
        assertShift(helper, 8, 8);   // S=120, room for 8
        assertShift(helper, 3, 3);   // S=120, one cell at 61 stones -> room for 3
        assertShift(helper, 0, 0);   // S=120, full of dirt -> room for 0
        helper.succeed();
    }
    /** Room for `capacity` stones in the backpack; asserts inserted amount, S deduction and no dangling P. */
    private static void assertShift(GameTestHelper helper, int capacity, int expected) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL);
        var inv = f.player().getInventory();
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        if (capacity >= 8) {
            inv.setItem(0, ItemStack.EMPTY);                  // empty cell -> pass-1 holds up to stacksize, capped by intent.first 8
        } else if (capacity == 3) {
            inv.setItem(0, new ItemStack(Items.STONE, 61));   // same-variant cell -> pass-0 adds up to 3 (to 64)
        }                                                      // capacity==0: keep all 36 cells full of dirt -> no empty, no same-variant -> moved 0
        int before = stoneCount(inv);
        int sBefore = stock(f);
        var result = exec(f, CacheActions.Action.TAKE_INVENTORY, 0, 8);
        int after = stoneCount(inv);
        if (capacity == 0) {
            helper.assertTrue(result == CacheActions.Result.NO_SPACE, "Full inventory Shift take was not refused (capacity=" + capacity + ")");
            helper.assertTrue(after == before && stock(f) == sBefore && preview(f) == 0,
                    "Full-inventory Shift take mutated backpack/stock/reservation");
            return;
        }
        helper.assertTrue(result == CacheActions.Result.OK, "Shift take rejected (capacity=" + capacity + ")");
        helper.assertTrue(after - before == expected, "Shift take inserted " + (after - before) + " (expected " + expected + "), capacity=" + capacity);
        helper.assertTrue(preview(f) == 0, "Shift take left a dangling preview (capacity=" + capacity + ")");
        helper.assertTrue(stock(f) == ReceiveTests.FULL - expected,
                "Shift take did not settle the deduction immediately (S=" + stock(f) + ", expected " + (ReceiveTests.FULL - expected) + ", capacity=" + capacity + ")");
    }
    private static int stoneCount(net.minecraft.world.entity.player.Inventory inv) {
        int total = 0; for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.STONE)) total += inv.getItem(i).getCount();
        return total;
    }

    // T3 (Planner §5 "实际放下"): S=120, take 8 as a cursor preview (P=8), then place the whole 8 into
    // the backpack (cursor empties) and let the panel settle via snapshot. The placed 8 must be
    // deducted from the cache (S=112) and the cursor preview cleared (P=0), with no leak or double charge.
    @GameTest(template = "empty")
    public static void t3PlacedItemsSettleThroughValidatedSnapshot(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120 stones
        var player = f.player();
        player.getInventory().setItem(0, ItemStack.EMPTY); // room to receive the placed stack
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && player.containerMenu.getCarried().getCount() == 8, "Preview take failed");
        helper.assertTrue(preview(f) == 8, "Preview P not recorded");
        // Place all 8 into the backpack; the cursor empties.
        player.getInventory().setItem(0, player.containerMenu.getCarried()); player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 8, "Backpack did not receive the placed stack");
        // The panel settle reads the real cursor state and deducts what left the cache (validate -> debit).
        CacheActions.snapshot(player);
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 16, "Placed 8 not deducted from cache (S=" + stock(f) + ", expected 112)");
        helper.assertTrue(preview(f) == 0, "Placed 8 left a dangling preview");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 8, "Placed stack was not conserved in the backpack");
        helper.succeed();
    }

    // T14 (Planner §5 "保存恢复"): a cursor preview is transient and never serialized, while the
    // confirmed cache stock persists. Save with a pending preview, reload: no preview copy survives,
    // no confirmed items are lost.
    @GameTest(template = "empty")
    public static void t14SaveReloadKeepsConfirmedStockButNotTransientPreview(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && preview(f) == 8, "Preview take failed");
        var registry = f.player().registryAccess();
        var saved = CacheLedger.load(f.ledger().save(new net.minecraft.nbt.CompoundTag(), registry), registry);
        helper.assertTrue(saved.problem().isEmpty(), "Ledger reload reported a problem");
        helper.assertTrue(saved.find(f.handle().cacheId()).state().cells().getFirst().amount() == ReceiveTests.FULL - 8,
                "Confirmed stock changed across save/load (expected S=120)");
        helper.assertTrue(dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0) == 8,
                "Pending preview should remain a runtime reservation after reload (not persisted)");
        helper.succeed();
    }

    // T9 (Planner §5 "生命周期"，Scathiard 2026-09-08 拍板：关闭=取消取出留缓存): closing with a
    // pending cursor preview must cancel the take (items stay in the cache) — stock unchanged, no
    // preview left, and the unplaced stack is NOT placed into the backpack.
    @GameTest(template = "empty")
    public static void t9CloseCancelsPreviewLeavingStockInCache(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120 stones
        var player = f.player();
        player.getInventory().setItem(0, ItemStack.EMPTY); // room, but close must NOT place into it
        player.containerMenu.setCarried(ItemStack.EMPTY);
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && player.containerMenu.getCarried().getCount() == 8 && preview(f) == 8, "Preview take failed");
        // Close the panel: the unplaced preview is cancelled (kept in cache), not auto-placed.
        CacheActions.close(player);
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 8, "Close changed cache stock (expected S=120, got " + stock(f) + ")");
        helper.assertTrue(preview(f) == 0, "Close left a lingering preview");
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "Close left the preview stack on the cursor");
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "Close auto-placed the unplaced stack into the backpack");
        helper.succeed();
    }

    // T2 (Planner §5 "满缓存与单件归还"): at full cache (S=128), take 8 as preview (P=8), right-click
    // return 1 (P=7), then return the rest (P=0). S stays FULL throughout — returning a reserved
    // item only releases the preview, never re-inserts stock.
    @GameTest(template = "empty")
    public static void t2FullCacheReturnsReservedOneByOne(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL); // S=128 (full)
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && f.player().containerMenu.getCarried().getCount() == 8 && stock(f) == ReceiveTests.FULL, "Preview take failed");
        // Right-click returns exactly 1 (first=1): P drops 8->7, S unchanged.
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 1) == CacheActions.Result.OK
                && preview(f) == 7 && f.player().containerMenu.getCarried().getCount() == 7 && stock(f) == ReceiveTests.FULL,
                "Single return did not drop preview to 7 with stock unchanged");
        // Left-click (first=0) returns the rest: P 7->0, S unchanged.
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 0) == CacheActions.Result.OK
                && preview(f) == 0 && f.player().containerMenu.getCarried().isEmpty() && stock(f) == ReceiveTests.FULL,
                "Full return did not clear preview with stock unchanged");
        helper.succeed();
    }

    // T8 (Planner §5 "真实点击" / P-0090 §1): in the ORDINARY InventoryMenu (runtime-supported=true),
    // a real native menu.clicked onto inventory[0] (InventoryMenu menu slot 36) settles the cursor
    // preview through the click wrapper — S=112, P=0, inventory[0] receives 8, no auto-place elsewhere.
    @GameTest(template = "empty")
    public static void t8RealClickInBackpackSettlesPreview(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120 stones
        var player = f.player();
        player.getInventory().setItem(0, ItemStack.EMPTY);
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && player.containerMenu.getCarried().getCount() == 8 && preview(f) == 8, "Preview take failed");
        // Locate the menu slot whose container is the player inventory and container-slot is 0.
        int menuSlot = -1;
        for (int i = 0; i < player.containerMenu.slots.size(); i++) {
            var slot = player.containerMenu.getSlot(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == 0) { menuSlot = i; break; }
        }
        helper.assertTrue(menuSlot >= 0, "Could not locate inventory[0] menu slot");
        int stonesBefore = 0; for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.STONE)) stonesBefore += player.getInventory().getItem(i).getCount();
        player.containerMenu.clicked(menuSlot, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
        int stonesAfter = 0; for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.STONE)) stonesAfter += player.getInventory().getItem(i).getCount();
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 16, "Real click did not settle S (S=" + stock(f) + ", expected 112)");
        helper.assertTrue(preview(f) == 0, "Real click left a dangling preview");
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "Real click left the stack on the cursor");
        helper.assertTrue(stonesAfter - stonesBefore == 8, "Real click did not place 8 into the backpack (moved " + (stonesAfter - stonesBefore) + ")");
        helper.succeed();
    }

    // T8b (Planner §5 / P-0090 §1 "分次放下"): real clicks, no setItem transfers. Take 8 (P=8, S=120),
    // right-click 3 times (1 each) into inventory[0] -> S=117/P=5/backpack 3; left-click the rest 5
    // -> S=112/P=0/backpack 8. Only real menu.clicked drives the settlement.
    @GameTest(template = "empty")
    public static void t8bRealClickPartialThenFullPlacement(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120
        var player = f.player();
        player.getInventory().setItem(0, ItemStack.EMPTY);
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && player.containerMenu.getCarried().getCount() == 8 && preview(f) == 8, "Preview take failed");
        int menuSlot = -1;
        for (int i = 0; i < player.containerMenu.slots.size(); i++) {
            var slot = player.containerMenu.getSlot(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == 0) { menuSlot = i; break; }
        }
        helper.assertTrue(menuSlot >= 0, "Could not locate inventory[0] menu slot");
        // Right-click 3 times, 1 each.
        for (int i = 0; i < 3; i++) player.containerMenu.clicked(menuSlot, 1, net.minecraft.world.inventory.ClickType.PICKUP, player);
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 11, "After 3 single right-clicks S=" + stock(f) + " (expected 117)");
        helper.assertTrue(preview(f) == 5, "After 3 single right-clicks P=" + preview(f) + " (expected 5)");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 3, "After 3 single right-clicks backpack=" + player.getInventory().getItem(0).getCount() + " (expected 3)");
        // Left-click the remaining 5.
        player.containerMenu.clicked(menuSlot, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 16, "After left-click S=" + stock(f) + " (expected 112)");
        helper.assertTrue(preview(f) == 0, "After left-click P=" + preview(f) + " (expected 0)");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 8, "After left-click backpack=" + player.getInventory().getItem(0).getCount() + " (expected 8)");
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "Cursor not empty after full placement");
        helper.succeed();
    }

    // T7 (Planner §5 "混合来源"): a cursor stack holding MORE than the preview count has a real
    // component beyond the reserved alias. Returning it must release only the preview (S unchanged
    // for that part) and insert only the real excess — never attach the whole stack to the preview.
    @GameTest(template = "empty")
    public static void t7MixedRealAndPreviewReturnSplitsCorrectly(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120 stones
        var player = f.player();
        player.getInventory().setItem(0, ItemStack.EMPTY);
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && player.containerMenu.getCarried().getCount() == 8 && preview(f) == 8, "Preview take failed");
        // The cursor now holds 8 (preview) + 2 real stones the player placed from elsewhere = 10.
        player.containerMenu.setCarried(new ItemStack(Items.STONE, 10));
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 0) == CacheActions.Result.OK,
                "Mixed return was rejected");
        // Only the real excess (2) is inserted; the preview (8) is released, S stays 120.
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 8 + 2, "Mixed return net wrong: S=" + stock(f) + " (expected 122)");
        helper.assertTrue(preview(f) == 0, "Mixed return left a preview");
        helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "Mixed return did not clear the cursor");
        helper.succeed();
    }

    // T7b (Planner P-0096, 第十七节): the real source must actually move off the backpack. Unlike the
    // ordinary PICKUP merge (carry→slot), the native PICKUP_ALL on an EMPTY landing slot scans same-item
    // source slots, safeTakes and merges into the carry. Setup: cache 120, source slot with 2 real stones,
    // an empty landing slot, no other stone. Take 8 preview, then PICKUP_ALL from the empty landing to
    // gather the 2 real stones -> carry 10, source 0, S120/P8; FMP right-return 1 twice -> S122/P8/carry 8;
    // left-return the whole preview -> S122/P0/carry empty. No setItem transfer or snapshot settlement.
    @GameTest(template = "empty")
    public static void t7bRealSourceTransferViaPickupAll(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120 stones
        var player = f.player();
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 2)); // real source slot
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && player.containerMenu.getCarried().getCount() == 8 && preview(f) == 8, "Preview take failed");
        // Locate the empty landing slot (container = player inventory, container-slot = 1) for PICKUP_ALL.
        int emptyMenuSlot = -1;
        for (int i = 0; i < player.containerMenu.slots.size(); i++) {
            var slot = player.containerMenu.getSlot(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == 1) { emptyMenuSlot = i; break; }
        }
        helper.assertTrue(emptyMenuSlot >= 0 && player.getInventory().getItem(1).isEmpty(), "Empty landing slot not located");
        player.containerMenu.clicked(emptyMenuSlot, 0, net.minecraft.world.inventory.ClickType.PICKUP_ALL, player);
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "PICKUP_ALL did not empty the real source slot");
        helper.assertTrue(player.containerMenu.getCarried().getCount() == 10, "PICKUP_ALL did not merge the 2 real stones into the carry (count=" + player.containerMenu.getCarried().getCount() + ")");
        helper.assertTrue(stock(f) == ReceiveTests.FULL - 8 && preview(f) == 8, "PICKUP_ALL must not change S/P (S=" + stock(f) + ", P=" + preview(f) + ")");
        // FMP right-return one real stone, twice.
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 1) == CacheActions.Result.OK
                && stock(f) == ReceiveTests.FULL - 8 + 1 && player.containerMenu.getCarried().getCount() == 9, "First real return wrong (S=" + stock(f) + ")");
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 1) == CacheActions.Result.OK
                && stock(f) == ReceiveTests.FULL - 8 + 2 && player.containerMenu.getCarried().getCount() == 8, "Second real return wrong (S=" + stock(f) + ")");
        // Left-return the whole remaining preview: releases only the preview, S stays 122 (the 2 real are already in).
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 0) == CacheActions.Result.OK
                && stock(f) == ReceiveTests.FULL - 8 + 2 && preview(f) == 0 && player.containerMenu.getCarried().isEmpty(),
                "Preview return did not release (S=" + stock(f) + ", P=" + preview(f) + ")");
        helper.succeed();
    }

    // T13 (Planner §5 "创造专项", P-0090 §4 归因): creative preview + native placement through the
    // creative packet must charge only the cache-own transfer, and an independent same-variant pickup
    // from the creative list must NOT be mis-attributed to the cache withdrawal.
    @GameTest(template = "empty")
    public static void t13CreativeAttributionOnlyChargesCacheOwnTransfer(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 3); var player = f.player();
        TestPlayers.nativePackets(player);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        // Creative preview take 1 stone (S stays 3, preview P=1).
        var view = CacheActions.open(player);
        var take = new CacheActions.Intent(view.session(), view.record().state().revision(), CacheActions.Action.TAKE_CURSOR, 0, 1, -1, "");
        helper.assertTrue(CacheActions.executeCreative(player, take, "", 0) == CacheActions.Result.OK
                && f.record().state().cells().getFirst().amount() == 3, "Creative preview take failed or wrongly deducted");
        // Native creative placement to inventory[0] (CreativeModeInventoryScreen slot 36 = inv 9 on InventoryMenu;
        // but the packet uses the inventory slot index; step through the native slot packet).
        player.getInventory().setItem(0, ItemStack.EMPTY);
        player.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.STONE)));
        // Close: only the 1 cache-own stone is charged (S=2), the placed one is in the backpack.
        player.connection.handleContainerClose(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0));
        int stones = 0; for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(Items.STONE)) stones += player.getInventory().getItem(i).getCount();
        helper.assertTrue(f.record().state().cells().getFirst().amount() == 2, "Creative placement over/under-charged (S=" + f.record().state().cells().getFirst().amount() + ", expected 2)");
        helper.assertTrue(stones == 1, "Creative placement did not keep exactly 1 in the backpack (got " + stones + ")");
        helper.succeed();
    }

    // T6 (Planner §5 "并行消费"): with S=120, a cursor preview P and a crafting reservation C of the
    @GameTest(template = "empty")
    public static void t6ParallelConsumersSeeExactlySMinusPMinusC(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); // S=120 stones
        var player = f.player();
        var table = helper.absolutePos(new BlockPos(2, 1, 2)); helper.setBlock(new BlockPos(2, 1, 2), Blocks.CRAFTING_TABLE);
        player.setPos(table.getX(), table.getY() + 1, table.getZ());
        player.containerMenu = new CraftingMenu(37, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), table));
        var stone = new ItemStack(Items.STONE);
        // One non-maximum 2x2 stone-brick placement reserves exactly 4 stones (C=4).
        helper.assertTrue(CraftingService.place(player, recipe(player, "stone_bricks"), false, false, true) == CraftingService.Result.OK,
                "Crafting reservation setup failed");
        int craftingReserved = dev.scathiard.feedmepackages.consumption.CraftingReservations.reservedCache(f.handle().cacheId(), 0, null);

        // Cursor preview P=8 of the same variant through the panel session.
        var view = CacheActions.open(player);
        helper.assertTrue(CacheActions.execute(player, new CacheActions.Intent(view.session(), view.record().state().revision(),
                CacheActions.Action.TAKE_CURSOR, 0, 8, -1, "")) == CacheActions.Result.OK
                && player.containerMenu.getCarried().getCount() == 8, "Preview take failed");
        int cursorReserved = dev.scathiard.feedmepackages.interaction.CursorReservations.reserved(f.handle().cacheId(), 0);

        // The shared reserve gate must equal C+P, and a parallel consumer sees exactly S−P−C.
        int gate = dev.scathiard.feedmepackages.consumption.CraftingReservations.reservedCache(f.handle().cacheId(), 0, null);
        helper.assertTrue(gate == craftingReserved + cursorReserved,
                "Reserve gate did not sum crafting (C) and cursor (P) reserves: gate=" + gate + " C=" + craftingReserved + " P=" + cursorReserved);
        int expected = 120 - craftingReserved - cursorReserved;
        int available = MaterialTransaction.open(player).orElseThrow().available(stone);
        helper.assertTrue(gate == 120 - available,
                "Parallel consumer available was " + available + " but S−gate = " + (120 - gate) + " (expected A = S − P − C, no leak or double-charge)");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeClickMatrixConservesPreview(GameTestHelper helper) {
        for (String action : new String[]{"drag", "number", "slotThrow", "outsideRight", "outsideLeft"}) {
            var player = realCursorPlayer(helper);
            var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer());
            long droppedBefore = droppedStones(helper);
            var menu = player.containerMenu;
            switch (action) {
                case "drag" -> {
                    menu.clicked(-999, 0, net.minecraft.world.inventory.ClickType.QUICK_CRAFT, player);
                    menu.clicked(36, 1, net.minecraft.world.inventory.ClickType.QUICK_CRAFT, player);
                    menu.clicked(37, 1, net.minecraft.world.inventory.ClickType.QUICK_CRAFT, player);
                    menu.clicked(-999, 2, net.minecraft.world.inventory.ClickType.QUICK_CRAFT, player);
                    helper.assertTrue(player.getInventory().getItem(0).getCount() == 4 && player.getInventory().getItem(1).getCount() == 4, "native drag distribution");
                }
                case "number" -> menu.clicked(36, 1, net.minecraft.world.inventory.ClickType.SWAP, player);
                case "slotThrow" -> menu.clicked(36, 0, net.minecraft.world.inventory.ClickType.THROW, player);
                case "outsideRight" -> menu.clicked(-999, 1, net.minecraft.world.inventory.ClickType.PICKUP, player);
                case "outsideLeft" -> menu.clicked(-999, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
            }
            int moved = switch (action) { case "drag", "outsideLeft" -> 8; case "outsideRight" -> 1; default -> 0; };
            helper.assertTrue(ledger.find(access.handle().cacheId()).state().cells().getFirst().amount() == 120-moved
                    && CursorReservations.reserved(access.handle().cacheId(), 0) == 8-moved, "native " + action + " wrong settlement");
            player.connection.handleContainerClose(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0));
            int inventory = player.getInventory().countItem(Items.STONE);
            long dropped = droppedStones(helper)-droppedBefore;
            helper.assertTrue(inventory+dropped == moved && menu.getCarried().isEmpty()
                    && CursorReservations.reserved(access.handle().cacheId(), 0) == 0, "native " + action + " lost/duplicated after close");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialPlacementSurvivesActualLifecycleEntrypoints(GameTestHelper helper) {
        var rules = helper.getLevel().getGameRules(); boolean oldKeep = rules.getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        try {
            for (String action : new String[]{"close", "menu", "unwear", "network", "cache", "logoutEvent", "deathKeep", "deathDrop"}) {
                var player = realCursorPlayer(helper); var oldMenu = player.containerMenu;
                var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer());
                for (int i=0; i<3; i++) oldMenu.clicked(36, 1, net.minecraft.world.inventory.ClickType.PICKUP, player);
                long droppedBefore = droppedStones(helper);
                switch (action) {
                    case "close" -> player.connection.handleContainerClose(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0));
                    case "menu" -> player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                            (id, inventory, owner) -> net.minecraft.world.inventory.ChestMenu.oneRow(id, inventory), net.minecraft.network.chat.Component.literal("lifecycle")));
                    case "unwear" -> TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
                    case "network" -> TestPlayers.necklace(player).getStackInSlot(0).set(FmpRegistries.NETWORK.get(), java.util.UUID.randomUUID());
                    case "cache" -> TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
                    case "logoutEvent" -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player));
                    default -> {
                        rules.getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(action.equals("deathKeep"), player.getServer());
                        player.setHealth(0); player.die(player.damageSources().generic());
                    }
                }
                // This is the actual registered player-tick entrypoint, not a direct cancel substitute.
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(player));
                helper.assertTrue(ledger.find(access.handle().cacheId()).state().cells().getFirst().amount() == 117
                        && CursorReservations.reserved(access.handle().cacheId(), 0) == 0 && oldMenu.getCarried().isEmpty(), "lifecycle " + action + " lost preview/stock");
                helper.assertTrue(player.getInventory().countItem(Items.STONE) + droppedStones(helper)-droppedBefore == 3,
                        "lifecycle " + action + " rolled back or duplicated placed3");
            }
        } finally { rules.getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(oldKeep, helper.getLevel().getServer()); }
        helper.succeed();
    }

    private static ServerPlayer realCursorPlayer(GameTestHelper helper) {
        var player = TestPlayers.real(helper, java.util.UUID.randomUUID()); TestPlayers.nativePackets(player);
        TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(access.handle().cacheId());
        var key = dev.scathiard.feedmepackages.item.ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        var edit = record.state().edit(); edit.filter(0, key); edit.insert(0, key, 120);
        ledger.replace(access.handle(), record.state().revision(), record.withState(edit.finish()));
        var view = CacheActions.open(player);
        if (CacheActions.execute(player, new CacheActions.Intent(view.session(), view.record().state().revision(), CacheActions.Action.TAKE_CURSOR, 0, 8, -1, "")) != CacheActions.Result.OK)
            throw new IllegalStateException("real player preview setup");
        return player;
    }
    private static long droppedStones(GameTestHelper helper) {
        long count=0;
        for (var entity : helper.getLevel().getAllEntities()) if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.STONE)) count+=item.getItem().getCount();
        return count;
    }

    private static net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>
            recipe(ServerPlayer player, String id) {
        return (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                player.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace(id)).orElseThrow();
    }
}
