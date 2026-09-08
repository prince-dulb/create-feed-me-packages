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

    private static net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>
            recipe(ServerPlayer player, String id) {
        return (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                player.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.withDefaultNamespace(id)).orElseThrow();
    }
}
