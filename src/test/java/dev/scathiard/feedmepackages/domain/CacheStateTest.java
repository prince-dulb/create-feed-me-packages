package dev.scathiard.feedmepackages.domain;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheStateTest {
    /** Test key with configurable stack size; group semantics are exercised by the dedicated test below. */
    private record TestVariant(String id, int stack) implements MaterialVariant {
        @Override public int stackSize() { return stack; }
        @Override public boolean equals(Object o) { return o instanceof TestVariant v && id.equals(v.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }
    private static final int STACK = 64; // standard Minecraft stack size
    private static TestVariant tv(String id) { return new TestVariant(id, STACK); }

    /** Level 1: groupCapacity=2, itemCapacity=128 for 64-stack items. Min=1 group (64 items), max=2 groups (128 items). */
    private CacheState<TestVariant> filtered() {
        var state = CacheState.<TestVariant>empty(UUID.randomUUID());
        var edit = state.edit(); edit.filter(0, tv("arrow{potion:healing}")); edit.thresholds(0, 1, 2);
        return edit.finish();
    }

    @Test void templatesAndSimulationDoNotCreateStock() {
        var state = filtered(); var edit = state.edit();
        assertEquals(128, edit.insert(0, state.cells().getFirst().filter(), Integer.MAX_VALUE));
        assertEquals(0, state.cells().getFirst().amount());
        assertEquals(128, edit.finish().cells().getFirst().amount());
        assertThrows(IllegalStateException.class, edit::finish);
    }

    @Test void identityRevisionAndUniqueFilterCannotBeBypassed() {
        var state = filtered(); var edit = state.edit();
        assertThrows(IllegalArgumentException.class, () -> edit.filter(1, state.cells().getFirst().filter()));
        edit.filter(1, tv("arrow{potion:poison}"));
        assertEquals(0, edit.insert(0, tv("arrow{potion:poison}"), 20));
        edit.insert(0, state.cells().getFirst().filter(), 3);
        assertThrows(IllegalStateException.class, () -> edit.filter(0, null));
        var next = edit.finish(); assertEquals(3, next.cells().getFirst().amount());
        assertEquals(0, state.cells().getFirst().amount());
    }

    @Test void arrowReflectsActualShortageWithoutErasingTheOrder() {
        var state = filtered(); var id = UUID.randomUUID(); var edit = state.edit();
        // shortage = 1 group * 64 - 0 = 64 items; request 50
        edit.request(0, id, UUID.randomUUID(), 50, false); state = edit.finish();
        assertEquals(14, state.requestable(0)); assertEquals(CacheState.Arrow.REQUESTED, state.arrow(0));
        // Insert 64 items: shortage becomes 0
        edit = state.edit(); edit.insert(0, state.cells().getFirst().filter(), 64); state = edit.finish();
        assertEquals(CacheState.Arrow.NONE, state.arrow(0)); assertEquals(50, state.pending(0));
        // Extract 20: amount=44, shortage=20
        edit = state.edit(); edit.extract(0, 20); state = edit.finish();
        assertEquals(CacheState.Arrow.REQUESTED, state.arrow(0)); assertEquals(0, state.requestable(0));
        // Reset clears pending; amount=44, shortage=20, requestable=20
        edit = state.edit(); edit.reset(0); state = edit.finish();
        assertEquals(44, state.cells().getFirst().amount()); assertEquals(20, state.requestable(0));
        // Late arrival for already-reset order: no-op
        edit = state.edit(); edit.arrived(id, 50); state = edit.finish();
        assertEquals(44, state.cells().getFirst().amount()); assertTrue(state.orders().isEmpty());
    }

    @Test void movingFilterToAnotherCellCannotReuseAnOldParcelRevision() {
        var state = filtered(); var old = state.cells().getFirst(); var edit = state.edit();
        edit.filter(0, null); edit.filter(1, old.filter()); state = edit.finish();
        assertTrue(state.cells().get(1).filterRevision() > old.filterRevision());
        long moved = state.cells().get(1).filterRevision(); edit = state.edit();
        edit.filter(1, old.filter()); assertSame(state, edit.finish());
        edit = state.edit(); edit.filter(1, null); edit.filter(0, old.filter());
        assertTrue(edit.finish().cells().getFirst().filterRevision() > moved);
    }

    @Test void partialOrdersAndArrivalCannotOverreserveOrGoNegative() {
        var state = filtered(); var id = UUID.randomUUID(); var edit = state.edit();
        edit.request(0, id, UUID.randomUUID(), 30, true);
        // shortage = 64, pending = 30, requestable = 34
        assertThrows(IllegalArgumentException.class, () -> edit.request(0, UUID.randomUUID(), UUID.randomUUID(), 35, false));
        var next = edit.finish(); assertEquals(34, next.requestable(0));
        var arrival = next.edit(); arrival.arrived(id, 1000); next = arrival.finish();
        assertEquals(0, next.pending(0)); assertEquals(64, next.requestable(0));
    }

    @Test void randomTransfersConserveAndStayWithinCapacity() {
        var state = filtered(); var random = new Random(0xF33D);
        long outside = 100000; long total = outside;
        for (int i = 0; i < 20000; i++) {
            var edit = state.edit();
            int quantity = random.nextInt(600);
            if (random.nextBoolean()) outside -= edit.insert(0, state.cells().getFirst().filter(), (int)Math.min(outside, quantity));
            else outside += edit.extract(0, quantity);
            state = edit.finish();
            assertEquals(total, outside + state.cells().getFirst().amount(), "step " + i);
            assertTrue(state.cells().getFirst().amount() >= 0 && state.cells().getFirst().amount() <= 128);
        }
    }

    @Test void dispatchConfirmationDoesNotResurrectEarlyArrivalOrReset() {
        var state = filtered(); var request = UUID.randomUUID(); var edit = state.edit();
        edit.request(0, request, UUID.randomUUID(), 50, true);
        state = edit.finish();
        edit = state.edit(); edit.arrived(request, 20); edit.confirmDispatch(request, 10);
        state = edit.finish();
        assertEquals(20, state.pending(0)); assertFalse(state.orders().get(request).uncertain());
        edit = state.edit(); edit.reset(0); edit.confirmDispatch(request, 0);
        assertTrue(edit.finish().orders().isEmpty());
    }

    @Test void upgradesRetCyanStockFiltersAndOrdersAndOnlyAddEmptyCells() {
        var state = filtered(); var edit = state.edit(); var id = UUID.randomUUID();
        edit.insert(0, state.cells().getFirst().filter(), 40); edit.request(0, id, UUID.randomUUID(), 24, false);
        state = edit.finish(); var original = state.cells().getFirst();
        for (int level = 2; level <= 5; level++) {
            edit = state.edit(); edit.upgrade(); state = edit.finish();
            assertEquals(original, state.cells().getFirst()); assertEquals(24, state.pending(0));
            assertEquals(CacheLevel.of(level).slots(), state.cells().size());
            assertTrue(state.cells().subList(1, state.cells().size()).stream().allMatch(c -> c.filter() == null));
        }
        var maxEdit = state.edit(); assertThrows(IllegalArgumentException.class, maxEdit::upgrade);
    }

    @Test void mergePreservesPaidLevelExactStockOrdersAndRouteLifetimesWithoutCommitting() {
        var target = filtered(); var edit = target.edit(); edit.insert(0, tv("arrow{potion:healing}"), 10);
        edit.filter(1, tv("discarded")); edit.filter(1, null); target = edit.finish();
        long oldEpoch = target.cells().get(1).filterRevision();
        var source = filtered(); edit = source.edit(); edit.upgrade(); edit.upgrade();
        UUID orderId = UUID.randomUUID(), player = UUID.randomUUID(); edit.request(0, orderId, player, 30, true);
        edit.insert(0, tv("arrow{potion:healing}"), 40); edit.filter(1, tv("arrow{potion:poison}")); edit.insert(1, tv("arrow{potion:poison}"), 70);
        source = edit.finish(); var plan = CacheMerge.simulate(source, target); var merged = plan.merged();
        assertEquals(target.id(), merged.id()); assertEquals(3, merged.level());
        assertEquals(50, merged.cells().getFirst().amount()); assertEquals(70, merged.cells().get(1).amount());
        assertEquals(target.cells().getFirst().filterRevision(), merged.cells().getFirst().filterRevision());
        assertTrue(merged.cells().get(1).filterRevision() > oldEpoch);
        assertEquals(new SupplyOrder<>(orderId, player, tv("arrow{potion:healing}"), merged.cells().getFirst().filterRevision(), 30, true), merged.orders().get(orderId));
        assertEquals(merged.cells().get(1).filterRevision(), plan.sourceRoutes().get(new CacheMerge.Route<>(source.cells().get(1).filter(), source.cells().get(1).filterRevision())));
        assertEquals(10, target.cells().getFirst().amount()); assertEquals(40, source.cells().getFirst().amount());
        assertEquals(plan, CacheMerge.simulate(source, target));
        assertThrows(UnsupportedOperationException.class, () -> plan.sourceRoutes().clear());
    }

    @Test void mergeRefusesOverflowConflictingConfigurationAndSelfMergeWithoutChangingEitherInput() {
        var target = filtered(); var edit = target.edit(); edit.insert(0, tv("arrow{potion:healing}"), 128); target = edit.finish();
        var source = filtered(); edit = source.edit(); edit.insert(0, tv("arrow{potion:healing}"), 1); source = edit.finish();
        var full = target; var extra = source;
        assertEquals(CacheMerge.Reason.NO_SPACE, assertThrows(CacheMerge.Rejected.class, () -> CacheMerge.simulate(extra, full)).reason());
        edit = source.edit(); edit.thresholds(0, 2, 2); var conflict = edit.finish();
        assertEquals(CacheMerge.Reason.CONFIGURATION_CONFLICT, assertThrows(CacheMerge.Rejected.class, () -> CacheMerge.simulate(conflict, full)).reason());
        assertEquals(CacheMerge.Reason.SAME_CACHE, assertThrows(CacheMerge.Rejected.class, () -> CacheMerge.simulate(full, full)).reason());
        assertEquals(128, target.cells().getFirst().amount()); assertEquals(1, source.cells().getFirst().amount());
    }

    @Test void mergeDoesNotDiscardZeroStockFiltersOrAmbiguousOrders() {
        var target = filtered(); var edit = target.edit();
        for (int i = 1; i < 9; i++) edit.filter(i, tv("ghost" + i)); target = edit.finish();
        var source = CacheState.<TestVariant>empty(UUID.randomUUID()); edit = source.edit(); edit.filter(0, tv("new ghost")); source = edit.finish();
        var ghosts = source; var full = target;
        assertEquals(CacheMerge.Reason.NO_SPACE, assertThrows(CacheMerge.Rejected.class, () -> CacheMerge.simulate(ghosts, full)).reason());
        UUID id = UUID.randomUUID(), player = UUID.randomUUID();
        edit = filtered().edit(); edit.request(0, id, player, 10, false); var ordered = edit.finish();
        edit = filtered().edit(); edit.request(0, id, player, 10, false); var duplicate = edit.finish();
        assertEquals(CacheMerge.Reason.INVALID_ORDER, assertThrows(CacheMerge.Rejected.class, () -> CacheMerge.simulate(ordered, duplicate)).reason());
        var bad = new CacheState<>(UUID.randomUUID(), 1, 0, source.cells(), ordered.orders());
        assertEquals(CacheMerge.Reason.INVALID_ORDER, assertThrows(CacheMerge.Rejected.class, () -> CacheMerge.simulate(bad, ordered)).reason());
    }

    @Test void randomMergeSimulationsConserveEveryExactVariantOrRejectWholeState() {
        var random = new Random(0xC0FFEE);
        for (int run = 0; run < 2000; run++) {
            var source = randomCache(random); var target = randomCache(random); var totals = new HashMap<TestVariant, Integer>();
            for (var state : List.of(source, target)) for (var cell : state.cells()) if (cell.filter() != null) totals.merge(cell.filter(), cell.amount(), Integer::sum);
            var limits = CacheLevel.of(Math.max(source.level(), target.level()));
            boolean fits = totals.size() <= limits.slots() && totals.entrySet().stream().allMatch(e -> e.getValue() <= limits.groupCapacity() * e.getKey().stackSize());
            if (fits) {
                var plan = CacheMerge.simulate(source, target); var merged = new HashMap<TestVariant, Integer>();
                for (var cell : plan.merged().cells()) if (cell.filter() != null) merged.put(cell.filter(), cell.amount());
                assertEquals(totals, merged); assertEquals(source, plan.source()); assertEquals(target, plan.target());
            } else assertEquals(CacheMerge.Reason.NO_SPACE, assertThrows(CacheMerge.Rejected.class, () -> CacheMerge.simulate(source, target)).reason());
        }
    }
    private CacheState<TestVariant> randomCache(Random random) {
        var edit = CacheState.<TestVariant>empty(UUID.randomUUID()).edit(); int level = random.nextInt(5) + 1;
        for (int i = 1; i < level; i++) edit.upgrade();
        int count = random.nextInt(CacheLevel.of(level).slots() + 1), offset = random.nextInt(8);
        for (int slot = 0; slot < count; slot++) { var key = tv("variant" + (slot + offset)); edit.filter(slot, key); edit.insert(slot, key, random.nextInt(CacheLevel.of(level).groupCapacity() * STACK + 1)); }
        return edit.finish();
    }

    /** Group counting: uniform groupCapacity (capacity/64) shared by all items; per-cell item capacity = groupCapacity × stackSize.
     *  With stackSize=64, groupCapacity=2 → itemCapacity=128. With stackSize=16, groupCapacity=2 → itemCapacity=32. */
    @Test void groupCountingUsesUniformGroupCapacity() {
        var stone = new TestVariant("stone", 64);
        var pearl = new TestVariant("pearl", 16);
        var state = CacheState.<TestVariant>empty(UUID.randomUUID());
        var edit = state.edit();
        edit.filter(0, stone);
        edit.thresholds(0, 1, 2); // 1 group minimum, 2 groups maximum (groupCapacity = 128/64 = 2)
        state = edit.finish();

        // 64-stack item: 2 groups × 64 = 128 items
        edit = state.edit();
        assertEquals(128, edit.insert(0, stone, Integer.MAX_VALUE));
        state = edit.finish();
        assertEquals(128, state.cells().getFirst().amount());

        // Shortage: 1 group × 64 − 128 = 0 (satisfied)
        assertEquals(0, state.cells().getFirst().shortage());
        assertEquals(CacheState.Arrow.NONE, state.arrow(0));

        // 16-stack item: same groupCapacity=2, but itemCapacity = 2 × 16 = 32
        edit = state.edit(); edit.filter(1, pearl); edit.thresholds(1, 1, 2);
        assertEquals(32, edit.insert(1, pearl, Integer.MAX_VALUE));
        state = edit.finish();
        assertEquals(32, state.cells().get(1).amount());

        // Withdraw stone to below minimum: shortage = 64 − 30 = 34
        edit = state.edit();
        edit.extract(0, 98);
        state = edit.finish();
        assertEquals(30, state.cells().getFirst().amount());
        assertEquals(34, state.cells().getFirst().shortage());
        assertEquals(34, state.requestable(0));

        // Request fills shortage in items
        edit = state.edit();
        edit.request(0, UUID.randomUUID(), UUID.randomUUID(), 34, false);
        state = edit.finish();
        assertEquals(0, state.requestable(0));
    }
}
