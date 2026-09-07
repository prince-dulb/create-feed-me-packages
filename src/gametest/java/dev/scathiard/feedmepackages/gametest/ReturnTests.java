package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.logistics.ReturnService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReturnTests {
    @GameTest(template = "empty")
    public static void returnNeedsAddressAndCarrierBeforeDeducting(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        // Stone: 64 stack. max=1 group = 64 items; put 100 -> overage 36 (within level-1 capacity 128).
        edit.filter(0, key); edit.thresholds(0, 1, 1); edit.insert(0, key, 100);
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        // No return address -> no dispatch, no deduction.
        ReturnService.check(player);
        helper.assertTrue(ledger.find(handle.cacheId()).state().cells().get(0).amount() == 100, "No-address return deducted stock");
        // Address set but no carrier (core run has no transport add-on) -> still no deduction.
        ledger.setReturnAddress(handle.cacheId(), "ReturnStation");
        ReturnService.check(player);
        helper.assertTrue(ledger.find(handle.cacheId()).state().cells().get(0).amount() == 100, "Carrier-less return deducted stock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void returnAddressPersistsAcrossReload(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        ledger.setReturnAddress(handle.cacheId(), "FMP@origin");
        var saved = CacheLedger.load(ledger.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
        helper.assertTrue(saved.problem().isEmpty() && "FMP@origin".equals(saved.returnAddress(handle.cacheId())), "Return address did not persist");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void returnAddressSavesThroughIntentPath(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        // The real client sends SET_RETURN_ADDRESS with slot=-1 through CacheActions (cache-level, no cell).
        var view = CacheActions.open(player);
        var before = ledger.find(handle.cacheId());
        var result = CacheActions.execute(player, new CacheActions.Intent(view.session(), before.state().revision(),
                CacheActions.Action.SET_RETURN_ADDRESS, -1, -1, -1, "FMP@origin"));
        helper.assertTrue(result == CacheActions.Result.OK, "Intent-path return address was rejected: " + result);
        helper.assertTrue("FMP@origin".equals(ledger.returnAddress(handle.cacheId())), "Intent path did not save the return address");
        var saved = CacheLedger.load(ledger.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
        helper.assertTrue(saved.problem().isEmpty() && "FMP@origin".equals(saved.returnAddress(handle.cacheId())), "Saved return address did not survive reload");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void noReturnMarkerSurvivesUpgrade(GameTestHelper helper) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack());
        var access = AccessGate.resolve(player); var ledger = CacheLedger.get(player.getServer()); var handle = access.handle();
        var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
        // max=-1 = "no return": a semantic marker that survives upgrades (the client displays it as
        // the capacity full position, so the thumb follows new capacities automatically).
        edit.filter(0, key); edit.thresholds(0, 0, -1); edit.upgrade();
        ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        var cell = ledger.find(handle.cacheId()).state().cells().get(0);
        helper.assertTrue(cell.maximum() == -1, "No-return marker should survive the upgrade: " + cell.maximum());
        helper.succeed();
    }
}
