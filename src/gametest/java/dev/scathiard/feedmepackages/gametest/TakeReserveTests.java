package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
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

    @GameTest(template = "empty")
    public static void takeReservesWithoutDeductingOrSupply(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL);
        int ordersBefore = f.record().state().orders().size();
        helper.assertTrue(exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8) == CacheActions.Result.OK
                && f.player().containerMenu.getCarried().getCount() == 8 && stock(f) == ReceiveTests.FULL,
                "Take did not reserve (stock should stay full)");
        var ledger = CacheLedger.get(helper.getLevel().getServer());
        helper.assertTrue(ledger.pendingTake(f.handle().cacheId(), f.key()) == 8, "Reservation not recorded");
        for (int i = 0; i < 4; i++) SupplyService.tick(f.player());
        helper.assertTrue(f.record().state().orders().size() == ordersBefore, "Reserved take triggered a supply order");
        exec(f, CacheActions.Action.RELEASE_TAKE, 0, 8);
        helper.assertTrue(stock(f) == ReceiveTests.FULL && ledger.pendingTake(f.handle().cacheId(), f.key()) == 0,
                "Released take changed stock or left a reservation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void confirmedTakeDeductsExactlyOnce(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8);
        exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8);
        helper.assertTrue(exec(f, CacheActions.Action.CONFIRM_TAKE, 0, 8) == CacheActions.Result.OK && stock(f) == ReceiveTests.FULL - 16,
                "Confirmed take did not deduct");
        helper.assertTrue(exec(f, CacheActions.Action.CONFIRM_TAKE, 0, 8) == CacheActions.Result.NO_SPACE && stock(f) == ReceiveTests.FULL - 16,
                "Double confirm deducted twice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void depositOfReservedItemReleasesIt(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8);
        exec(f, CacheActions.Action.TAKE_CURSOR, 0, 8);
        f.player().containerMenu.setCarried(new ItemStack(Items.STONE, 8));
        var ledger = CacheLedger.get(helper.getLevel().getServer());
        helper.assertTrue(exec(f, CacheActions.Action.DEPOSIT, 0, 0) == CacheActions.Result.OK
                && stock(f) == ReceiveTests.FULL && ledger.pendingTake(f.handle().cacheId(), f.key()) == 0,
                "Deposit of a reserved item did not return it without duplication");
        helper.succeed();
    }
}
