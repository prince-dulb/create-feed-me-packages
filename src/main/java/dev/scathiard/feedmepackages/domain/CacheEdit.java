package dev.scathiard.feedmepackages.domain;

import java.util.*;

/** Private simulation workspace. Discard on failure; publish its result only once. */
public final class CacheEdit<K extends MaterialVariant> {
    private final CacheState<K> before;
    private final List<Cell<K>> cells;
    private final Map<UUID, SupplyOrder<K>> orders;
    private int level;
    private boolean finished;

    CacheEdit(CacheState<K> before) {
        this.before = before;
        cells = new ArrayList<>(before.cells());
        orders = new LinkedHashMap<>(before.orders());
        level = before.level();
    }

    /** Immutable view of this private simulation, without finishing or publishing it. */
    public List<Cell<K>> cells() { open(); return List.copyOf(cells); }

    public void filter(int slot, K variant) {
        open();
        if (variant != null) for (int i = 0; i < cells.size(); i++)
            if (i != slot && variant.equals(cells.get(i).filter()))
                throw new IllegalArgumentException("Duplicate exact filter");
        Cell<K> old = cells.get(slot);
        Cell<K> next = old.configure(variant);
        if (!next.equals(old)) {
            // Routes do not contain the visual slot number. A moved/recreated variant must
            // never inherit another slot's local counter and accept an old sealed parcel.
            long revision = Math.incrementExact(cells.stream().mapToLong(Cell::filterRevision).max().orElse(0));
            next = new Cell<>(next.filter(), next.amount(), next.minimum(), next.maximum(), revision);
            reset(slot);
        }
        cells.set(slot, next);
    }

    public void thresholds(int slot, int min, int max) {
        open();
        Cell<K> cell = cells.get(slot);
        int groupCapacity = CacheLevel.of(level).groupCapacity();
        if (min > groupCapacity || max > groupCapacity) throw new IllegalArgumentException("Threshold exceeds capacity");
        cells.set(slot, cell.thresholds(min, max));
    }

    public int insert(int slot, K variant, int offered) {
        open();
        if (offered < 0) throw new IllegalArgumentException("Negative offer");
        Cell<K> cell = cells.get(slot);
        if (variant == null || !variant.equals(cell.filter())) return 0;
        int itemCapacity = CacheLevel.of(level).groupCapacity() * variant.stackSize();
        int accepted = Math.min(offered, itemCapacity - cell.amount());
        cells.set(slot, cell.withAmount(cell.amount() + accepted));
        return accepted;
    }

    public int extract(int slot, int wanted) {
        open();
        if (wanted < 0) throw new IllegalArgumentException("Negative extraction");
        Cell<K> cell = cells.get(slot);
        int taken = Math.min(wanted, cell.amount());
        cells.set(slot, cell.withAmount(cell.amount() - taken));
        return taken;
    }

    public void request(int slot, UUID orderId, UUID player, int amount, boolean uncertain) {
        open();
        Cell<K> cell = cells.get(slot);
        long pending = orders.values().stream().filter(o -> Objects.equals(o.variant(), cell.filter())
                && o.filterRevision() == cell.filterRevision()).mapToLong(SupplyOrder::remaining).sum();
        if (amount <= 0 || amount > (long) cell.shortage() - pending || orders.size() >= CacheState.MAX_ORDERS)
            throw new IllegalArgumentException("Request has no unreserved shortage or order table is full");
        if (orders.containsKey(orderId)) throw new IllegalArgumentException("Duplicate order identity");
        orders.put(orderId, new SupplyOrder<>(orderId, player, cell.filter(), cell.filterRevision(), amount, uncertain));
    }

    /** Caller must prove first local arrival of this physical parcel, including its exact route. */
    public void arrived(UUID orderId, int arrived) {
        open();
        if (arrived < 0) throw new IllegalArgumentException("Negative arrival");
        SupplyOrder<K> old = orders.get(orderId);
        if (old == null) return; // Explicitly reset or already fully accounted; late stock still exists.
        int remaining = Math.max(0, old.remaining() - arrived);
        if (remaining == 0) orders.remove(orderId);
        else orders.put(orderId, new SupplyOrder<>(old.id(), old.player(), old.variant(), old.filterRevision(), remaining, old.uncertain()));
    }

    /** Release only quantities proven not dispatched; never resurrect an already received order. */
    public void confirmDispatch(UUID orderId, int notDispatched) {
        open();
        if (notDispatched < 0) throw new IllegalArgumentException("Negative unfulfilled quantity");
        SupplyOrder<K> old = orders.get(orderId);
        if (old == null) return;
        int remaining = Math.max(0, old.remaining() - notDispatched);
        if (remaining == 0) orders.remove(orderId);
        else orders.put(orderId, new SupplyOrder<>(old.id(), old.player(), old.variant(), old.filterRevision(), remaining, false));
    }

    public void reset(int slot) {
        open();
        Cell<K> cell = cells.get(slot);
        orders.values().removeIf(o -> Objects.equals(o.variant(), cell.filter())
                && o.filterRevision() == cell.filterRevision());
    }

    public void upgrade() {
        open();
        CacheLevel next = CacheLevel.of(level + 1);
        while (cells.size() < next.slots()) cells.add(Cell.empty());
        level = next.level();
    }

    public CacheState<K> finish() {
        open();
        finished = true;
        if (level == before.level() && cells.equals(before.cells()) && orders.equals(before.orders())) return before;
        return new CacheState<>(before.id(), level, Math.incrementExact(before.revision()), cells, orders);
    }

    private void open() {
        if (finished) throw new IllegalStateException("Transaction already finished");
    }
}
