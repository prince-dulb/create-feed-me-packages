package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.*;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.service.PrivateCacheMerge;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.logistics.*;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.List;
import java.util.Map;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FoundationTests {
    @GameTest(template = "empty")
    public static void exactVariantsPreserveComponents(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var plain = new ItemStack(Items.ARROW, 32);
        var named = new ItemStack(Items.ARROW, 3); named.set(DataComponents.CUSTOM_NAME, Component.literal("Supply specimen"));
        var key = ItemVariantKey.of(named, registries);
        helper.assertTrue(!key.equals(ItemVariantKey.of(plain, registries)), "Component variants collapsed");
        helper.assertTrue(key.equals(ItemVariantKey.decode(key.encoded(), registries)), "Canonical template does not round-trip");
        var recovered = key.stack(registries, 3);
        helper.assertTrue(ItemStack.matches(recovered, named), "Components changed in storage");
        recovered.set(DataComponents.CUSTOM_NAME, Component.literal("Changed copy"));
        helper.assertTrue(ItemStack.matches(key.stack(registries, 3), named), "Caller changed immutable variant");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unsafeTemplatesAreRejected(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var large = new ItemStack(Items.STONE); large.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(1100)));
        rejects(helper, () -> ItemVariantKey.of(large, registries));
        rejects(helper, () -> ItemVariantKey.of(FmpRegistries.PENDANT.toStack(), registries));
        rejects(helper, () -> ItemVariantKey.decode("{id:\"minecraft:stone\",count:64}", registries));
        rejects(helper, () -> ItemVariantKey.decode("{id:\"missing:no_item\",count:1}", registries));
        helper.assertTrue(large.getCount() == 1 && large.getHoverName().getString().length() == 1100, "Rejected item mutated");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ledgerReloadsWholeCacheAndPlayerIsolation(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var ledger = new CacheLedger();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(); UUID id = ledger.createOrdinary();
        var record = ledger.find(id); var key = ItemVariantKey.of(new ItemStack(Items.STONE), registries);
        var edit = record.state().edit(); edit.filter(0, key); edit.insert(0, key, 90); edit.thresholds(0, 2, -1);
        edit.request(0, UUID.randomUUID(), alice, 30, false);
        var state = edit.finish(); ledger.replace(new CacheHandle(id, alice, null), record.state().revision(), record.withState(state));
        ledger.personalOrCreate(alice); ledger.personalOrCreate(bob); ledger.setCacheFirst(alice, true);
        var saved = ledger.save(new CompoundTag(), registries); var loaded = CacheLedger.load(saved, registries);
        helper.assertTrue(loaded.problem().isEmpty(), "Valid ledger locked");
        helper.assertTrue(state.equals(loaded.find(id).state()), "Cache changed across reload");
        helper.assertTrue(!loaded.personal(alice).equals(loaded.personal(bob)), "Players share private inventory");
        helper.assertTrue(!loaded.cacheFirst(alice) && !loaded.cacheFirst(bob), "Persisted inactive preference overrode fixed priority");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void corruptLedgerLocksAndPreservesOriginal(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var invalid = new CompoundTag(); invalid.putInt("schema", 999);
        invalid.putString("irreplaceable_evidence", "keep original");
        var loaded = CacheLedger.load(invalid, registries);
        helper.assertTrue(!loaded.problem().isEmpty(), "Corrupt schema was accepted");
        rejects(helper, loaded::createOrdinary);
        helper.assertTrue(invalid.equals(loaded.save(new CompoundTag(), registries)), "Rejected data overwritten");
        helper.succeed();
    }

    private static void rejects(GameTestHelper helper, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected, "Unsafe operation was accepted");
    }

    @GameTest(template = "empty")
    public static void wrongListElementTypesNeverBecomeEmptyState(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var ledger = new CacheLedger(); ledger.createOrdinary();
        var valid = ledger.save(new CompoundTag(), registries);
        var wrongOrders = valid.copy(); var strings = new ListTag(); strings.add(StringTag.valueOf("unreadable order"));
        wrongOrders.getList("caches", Tag.TAG_COMPOUND).getCompound(0).put("orders", strings);
        var wrongPreferences = valid.copy(); wrongPreferences.put("preferences", strings.copy());
        for (var invalid : java.util.List.of(wrongOrders, wrongPreferences)) {
            var loaded = CacheLedger.load(invalid, registries);
            helper.assertTrue(!loaded.problem().isEmpty() && loaded.save(new CompoundTag(), registries).equals(invalid), "Malformed list silently erased its data");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyCommitInvalidatesPriorRevision(GameTestHelper helper) {
        var ledger = new CacheLedger(); var id = ledger.createOrdinary(); var original = ledger.find(id);
        var handle = new CacheHandle(id, UUID.randomUUID(), null);
        ledger.replace(handle, original.state().revision(), original);
        helper.assertTrue(ledger.find(id).state().revision() == original.state().revision() + 1, "Record-only commit retained stale revision");
        rejects(helper, () -> ledger.replace(handle, original.state().revision(), original));
        helper.succeed();
    }

    private static final UUID PROCESS = UUID.randomUUID();

    @GameTest(template = "empty")
    public static void manufacturingProposalsRegisterNothingUntilTheirSingleCommit(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer()); var registries = helper.getLevel().registryAccess();
        var before = ledger.save(new CompoundTag(), registries); var source = ledger.ordinaryForManufacturing(null);
        var proposal = ledger.prepareOrdinaryUpgrade(source, 2);
        helper.assertTrue(before.equals(ledger.save(new CompoundTag(), registries)) && ledger.find(source.state().id()) == null,
                "Preview registered an ordinary cache");
        proposal.commit();
        helper.assertTrue(ledger.find(source.state().id()).state().level() == 2 && !proposal.current(), "Actual upgrade was not committed once");
        rejects(helper, proposal::commit);
        var stale = ledger.prepareOrdinaryUpgrade(ledger.find(source.state().id()), 3); ledger.createOrdinary();
        var after = ledger.save(new CompoundTag(), registries); rejects(helper, stale::commit);
        helper.assertTrue(after.equals(ledger.save(new CompoundTag(), registries)), "Stale world proposal overwrote later state");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void privatizationCommitsWholeOwnershipAndRevokesOrdinaryCopies(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, ReceiveTests.FULL - 8); helper.assertTrue(ReceiveService.receive(f.player(), f.box(32)), "Private ownership fixture failed");
        var before = f.ledger().save(new CompoundTag(), f.player().registryAccess());
        var plan = PrivateCacheMerge.simulate(f.ledger(), f.player().registryAccess(), f.player().getUUID(), f.handle().cacheId());
        var transaction = f.ledger().prepareOwnership(plan.source(), plan.target(), plan.replacement(), plan.sourceRoutes());
        helper.assertTrue(f.ledger().personal(f.player().getUUID()) == null && before.equals(f.ledger().save(new CompoundTag(), f.player().registryAccess())),
                "Ownership preparation changed the ledger");
        transaction.commit(); rejects(helper, transaction::commit);
        helper.assertTrue(f.ledger().personal(f.player().getUUID()).equals(f.handle().cacheId()) && f.record().owner().equals(f.player().getUUID()), "First privatization changed its cache id");
        helper.assertTrue(!AccessGate.resolve(f.player()).active(), "Old ordinary input still grants personal cache access");
        var personalPendant = FmpRegistries.PERSONAL_PENDANT.toStack(); personalPendant.set(FmpRegistries.OWNER.get(), f.player().getUUID());
        TestPlayers.necklace(f.player()).setStackInSlot(0, personalPendant);
        helper.assertTrue(AccessGate.resolve(f.player()).handle().cacheId().equals(f.handle().cacheId()) && !f.residual().isEmpty()
                && f.record().state().cells().getFirst().amount() == ReceiveTests.FULL && f.record().state().pending(0) == 32, "Ownership commit lost a part of the cache");
        var copy = CacheLedger.load(f.ledger().save(new CompoundTag(), f.player().registryAccess()), f.player().registryAccess());
        helper.assertTrue(copy.problem().isEmpty() && copy.personal(f.player().getUUID()).equals(f.handle().cacheId()), "Personal ownership did not save/load");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mergedCacheAcceptsAuthenticatedLateParcelsButNotOldAccessOrOldFilters(GameTestHelper helper) {
        var f = ReceiveTests.setup(helper, 0); ItemStack late = f.box(32), later = f.box(16);
        UUID target = f.ledger().personalOrCreate(f.player().getUUID());
        var plan = PrivateCacheMerge.simulate(f.ledger(), f.player().registryAccess(), f.player().getUUID(), f.handle().cacheId());
        f.ledger().prepareOwnership(plan.source(), plan.target(), plan.replacement(), plan.sourceRoutes()).commit();
        helper.assertTrue(f.ledger().find(f.handle().cacheId()) == null && !AccessGate.resolve(f.player()).active(), "Retired ordinary cache remained accessible");
        var copy = CacheLedger.load(f.ledger().save(new CompoundTag(), f.player().registryAccess()), f.player().registryAccess());
        long epoch = late.get(FmpRegistries.PARCEL_SEAL.get()).filterRevision();
        helper.assertTrue(copy.problem().isEmpty() && copy.parcelTarget(f.handle().cacheId(), f.key(), epoch).cacheId().equals(target), "Manufacturing route did not save/load");
        var mergedPersonal = FmpRegistries.PERSONAL_PENDANT.toStack(); mergedPersonal.set(FmpRegistries.OWNER.get(), f.player().getUUID());
        TestPlayers.necklace(f.player()).setStackInSlot(0, mergedPersonal);
        helper.assertTrue(ReceiveService.receive(f.player(), late) && late.isEmpty(), "Valid late parcel could not find the merged cache");
        var current = f.ledger().find(target); var handle = AccessGate.resolve(f.player()).handle();
        helper.assertTrue(current.state().cells().getFirst().amount() == 32 && current.state().pending(0) == 32, "Redirected delivery lost stock or order state");
        var edit = current.state().edit(); edit.extract(0, 32); edit.filter(0, null); edit.filter(1, f.key());
        f.ledger().replace(handle, current.state().revision(), current.withState(edit.finish()));
        ItemStack unchanged = later.copy();
        helper.assertTrue(!ReceiveService.receive(f.player(), later) && ItemStack.matches(later, unchanged), "Redirect bypassed an expired filter lifetime");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void personalUpgradeBelongsToOwnerAndAdvancesItsCache(GameTestHelper helper) {
        var ledger = CacheLedger.get(helper.getLevel().getServer()); var registries = helper.getLevel().registryAccess();
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        UUID id = ledger.personalOrCreate(owner); var handle = new CacheHandle(id, owner, null);
        var correct = ledger.preparePersonalUpgrade(handle, 2);
        helper.assertTrue(ledger.find(id).state().level() == 1, "Personal upgrade preview advanced the cache"); correct.commit();
        helper.assertTrue(ledger.find(id).state().level() == 2, "Owner personal upgrade did not advance exactly once");
        rejects(helper, correct::commit);
        // Only the cache owner may upgrade; a different player cannot touch it, and no level may be skipped.
        rejects(helper, () -> ledger.preparePersonalUpgrade(new CacheHandle(id, other, null), 3));
        rejects(helper, () -> ledger.preparePersonalUpgrade(handle, 4));
        var saved = CacheLedger.load(ledger.save(new CompoundTag(), registries), registries);
        helper.assertTrue(saved.problem().isEmpty() && saved.find(id).state().level() == 2, "Personal upgrade did not survive save/load");
        helper.succeed();
    }
    private static final UUID ORDINARY_PLAYER = UUID.fromString("36fa75ac-ce67-44c5-8c66-a54f10150001");
    private static final UUID PERSONAL_PLAYER = UUID.fromString("36fa75ac-ce67-44c5-8c66-a54f10150002");

    @GameTest(template = "empty")
    public static void ordinaryCacheSurvivesNativeDeathAndClone(GameTestHelper helper) { deathAndClone(helper, false); }

    @GameTest(template = "empty")
    public static void personalCacheSurvivesNativeDeathAndClone(GameTestHelper helper) { deathAndClone(helper, true); }

    private static void deathAndClone(GameTestHelper helper, boolean personal) {
        var rules = helper.getLevel().getGameRules(); var server = helper.getLevel().getServer();
        boolean originalKeep = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
        try {
            for (boolean keep : new boolean[]{false, true}) for (boolean worn : new boolean[]{false, true}) {
                rules.getRule(GameRules.RULE_KEEPINVENTORY).set(keep, server);
                UUID playerId = UUID.randomUUID(), marker = UUID.randomUUID();
                var player = TestPlayers.real(helper, playerId);
                var pendant = (personal ? FmpRegistries.PERSONAL_PENDANT : FmpRegistries.PENDANT).toStack();
                pendant.set(FmpRegistries.NETWORK.get(), marker); TestPlayers.necklace(player).setStackInSlot(0, pendant);
                var handle = AccessGate.resolve(player).handle(); var ledger = CacheLedger.get(server); var initial = ledger.find(handle.cacheId());
                var edit = initial.state().edit(); var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
                edit.filter(0, key); edit.thresholds(0, 2, -1);
                UUID request = UUID.randomUUID(); edit.request(0, request, playerId, 64, false); edit.insert(0, key, 120);
                ledger.replace(handle, initial.state().revision(), initial.withState(edit.finish()));
                var box = PackageItem.containing(List.of(new ItemStack(Items.STONE, 32))); String address = SupplyService.address(player);
                PackageItem.addAddress(box, address);
                ParcelAuthentication.apply(ledger, player.registryAccess(), box, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), playerId,
                        request, key.encoded(), ledger.find(handle.cacheId()).state().cells().getFirst().filterRevision(), address, false, ""));
                helper.assertTrue(ReceiveService.receive(player, box), "Death fixture could not receive its parcel"); ledger.setCacheFirst(playerId, true);
                var before = row(ledger, handle.cacheId(), player.registryAccess());
                for (int cycle = 0; cycle < 2; cycle++) {
                    if (!worn) {
                        player.getInventory().setItem(5, TestPlayers.necklace(player).getStackInSlot(0)); TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
                    }
                    player.setHealth(0); player.die(player.damageSources().generic());
                    helper.assertTrue(!AccessGate.resolve(player).active(), "Dead player retained cache authority");
                    helper.assertTrue(row(ledger, handle.cacheId(), player.registryAccess()).equals(before), "Death changed stock, request, residual or grade");
                    var dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3),
                            item -> marker.equals(item.getItem().get(FmpRegistries.NETWORK.get())));
                    helper.assertTrue(dropped.size() == (keep ? 0 : 1), "Native death duplicated or lost the pendant entity");
                    var revived = TestPlayers.real(helper, playerId); revived.restoreFrom(player, false);
                    if (!keep) {
                        helper.assertTrue(!AccessGate.resolve(revived).active(), "Death without keepInventory recreated a worn pendant");
                        var entity = dropped.getFirst(); entity.setNoPickUpDelay(); entity.playerTouch(revived);
                        helper.assertTrue(entity.isRemoved(), "Real pendant pickup was not consumed");
                    }
                    if (!keep || !worn) {
                        int recoveredSlot = -1;
                        for (int index = 0; index < 36; index++) if (marker.equals(revived.getInventory().getItem(index).get(FmpRegistries.NETWORK.get()))) recoveredSlot = index;
                        helper.assertTrue(recoveredSlot >= 0, "Pendant did not remain in native inventory or reach it by pickup");
                        var recovered = revived.getInventory().getItem(recoveredSlot); revived.getInventory().setItem(recoveredSlot, ItemStack.EMPTY);
                        TestPlayers.necklace(revived).setStackInSlot(0, recovered);
                    }
                    var access = AccessGate.resolve(revived);
                    helper.assertTrue(access.active() && access.handle().cacheId().equals(handle.cacheId()), "Reequipped pendant selected another cache after death");
                    helper.assertTrue(row(ledger, handle.cacheId(), revived.registryAccess()).equals(before) && !ledger.cacheFirst(playerId), "Native clone changed persistent cache or fixed priority");
                    player = revived;
                }
            }
        } finally { rules.getRule(GameRules.RULE_KEEPINVENTORY).set(originalKeep, server); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void currentModelPersistsInRealPlayerFilesAcrossJvm(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var registries = helper.getLevel().registryAccess();
        var ledger = CacheLedger.get(server);
        var probe = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PersistenceProbe::new, PersistenceProbe::load), "fmp_reference_current_model_probe");
        boolean prior = !probe.data.isEmpty();
        helper.assertTrue(prior || !Boolean.getBoolean("fmp.requirePriorPersistence"), "Prior JVM persistence evidence is required but missing");
        if (prior) { int probeSchema = probe.data.getInt("schema");
            helper.assertTrue(!PROCESS.equals(probe.data.getUUID("writer")) && probeSchema >= 2 && probeSchema <= CacheLedger.SCHEMA,
                "Persistence evidence came from this JVM or another data model"); }
        var ordinary = TestPlayers.create(helper, ORDINARY_PLAYER, FmpRegistries.PENDANT.toStack());
        var personalStack = FmpRegistries.PERSONAL_PENDANT.toStack(); personalStack.set(FmpRegistries.OWNER.get(), PERSONAL_PLAYER);
        var personal = TestPlayers.create(helper, PERSONAL_PLAYER, personalStack);
        var players = List.of(ordinary, personal);
        if (prior) for (int index = 0; index < players.size(); index++) {
            var player = players.get(index);
            helper.assertTrue(server.getPlayerList().load(player).isPresent(), "Native player-file load failed");
            var access = AccessGate.resolve(player); UUID expectedId = probe.data.getUUID("cache_" + index);
            helper.assertTrue(access.active() && access.handle().cacheId().equals(expectedId), "Curios cache identity did not survive native player-file load");
            helper.assertTrue(row(ledger, expectedId, registries).equals(probe.data.getCompound("record_" + index)), "World cache state changed across JVM");
            helper.assertTrue(!ledger.cacheFirst(player.getUUID()), "Prior saved preference reactivated after JVM restart");
            helper.assertTrue(player.getInventory().getItem(12).is(Items.DIAMOND) && player.getInventory().getItem(12).getCount() == 13,
                    "Real vanilla player inventory was not restored");
            var residuals = ledger.find(expectedId).residuals();
            var residual = residuals.values().stream().findFirst().orElse(ItemStack.EMPTY);
            helper.assertTrue(!residual.isEmpty() && ParcelAuthentication.valid(ledger, registries, residual, residual.get(FmpRegistries.PARCEL_SEAL.get())),
                    "Real saved residual or world authentication state did not survive JVM");
        }
        var next = new CompoundTag(); next.putUUID("writer", PROCESS); next.putInt("schema", CacheLedger.SCHEMA);
        for (int index = 0; index < players.size(); index++) {
            var player = players.get(index); var handle = AccessGate.resolve(player).handle();
            var before = ledger.find(handle.cacheId()); var edit = before.state().edit();
            for (int level = before.state().level(); level < (index == 0 ? 2 : 5); level++) edit.upgrade();
            edit.reset(0); edit.extract(0, Integer.MAX_VALUE);
            var sample = new ItemStack(Items.IRON_INGOT); sample.set(DataComponents.CUSTOM_NAME, Component.literal("Persistent exact component " + index));
            var key = ItemVariantKey.of(sample, registries); edit.filter(0, key);
            int capacity = CacheLevel.of(index == 0 ? 2 : 5).capacity(); int groupCapacity = CacheLevel.of(index == 0 ? 2 : 5).groupCapacity(); edit.thresholds(0, groupCapacity, -1);
            UUID order = UUID.randomUUID(); edit.request(0, order, player.getUUID(), 64, false); edit.insert(0, key, groupCapacity * key.stackSize() - 16);
            ledger.replace(handle, before.state().revision(), new CacheRecord(edit.finish(), before.owner(), Map.of()));
            var box = PackageItem.containing(List.of(key.stack(registries, 32))); String address = SupplyService.address(player);
            PackageItem.addAddress(box, address);
            ParcelAuthentication.apply(ledger, registries, box, new ParcelSeal(UUID.randomUUID(), handle.cacheId(), player.getUUID(), order,
                    key.encoded(), ledger.find(handle.cacheId()).state().cells().getFirst().filterRevision(), address, false, ""));
            helper.assertTrue(ReceiveService.receive(player, box), "Persistence fixture failed to form its real residual");
            var full = ledger.find(handle.cacheId()); var take = full.state().edit(); take.extract(0, 5);
            ledger.replace(handle, full.state().revision(), full.withState(take.finish()));
            ledger.setCacheFirst(player.getUUID(), index == 1); player.getInventory().setItem(12, new ItemStack(Items.DIAMOND, 13));
            saveNativePlayer(player);
            next.putUUID("cache_" + index, handle.cacheId()); next.put("record_" + index, row(ledger, handle.cacheId(), registries));
        }
        probe.data = next; probe.setDirty();
        FeedMePackages.LOGGER.info("FMP_CURRENT_PERSISTENCE {} schema={} ordinary+personal exact-state/player-files", prior ? "PRIOR_JVM_PASSED" : "FIRST_WRITE", CacheLedger.SCHEMA);
        helper.succeed();
    }

    private static CompoundTag row(CacheLedger ledger, UUID id, HolderLookup.Provider registries) {
        for (var entry : ledger.save(new CompoundTag(), registries).getList("caches", Tag.TAG_COMPOUND))
            if (((CompoundTag)entry).getUUID("id").equals(id)) return ((CompoundTag)entry).copy();
        throw new IllegalStateException("Persistence record is missing");
    }
    static void saveNativePlayer(ServerPlayer player) {
        try {
            // Only the test fixture needs access to this protected vanilla save entry.
            var method = PlayerList.class.getDeclaredMethod("save", ServerPlayer.class); method.setAccessible(true);
            method.invoke(player.getServer().getPlayerList(), player);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native fixture player save failed", failure); }
    }
    private static final class PersistenceProbe extends SavedData {
        private CompoundTag data = new CompoundTag();
        private static PersistenceProbe load(CompoundTag tag, HolderLookup.Provider registries) {
            var probe = new PersistenceProbe(); probe.data = tag.copy(); return probe;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) { return data.copy(); }
    }
}
