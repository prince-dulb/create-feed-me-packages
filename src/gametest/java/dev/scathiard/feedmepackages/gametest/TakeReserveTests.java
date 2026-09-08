package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
}
