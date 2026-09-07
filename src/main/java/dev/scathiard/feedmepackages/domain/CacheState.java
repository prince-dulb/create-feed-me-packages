package dev.scathiard.feedmepackages.domain;

import java.util.*;

/** Immutable transaction unit. K must be an immutable exact item identity. */
public record CacheState<K extends MaterialVariant>(UUID id, int level, long revision, List<Cell<K>> cells,
                             Map<UUID, SupplyOrder<K>> orders) {
    public static final int MAX_ORDERS = 1024;

    public CacheState {
        Objects.requireNonNull(id);
        CacheLevel limits = CacheLevel.of(level);
        cells = List.copyOf(cells);
        orders = Map.copyOf(orders);
        if (revision < 0 || cells.size() != limits.slots() || orders.size() > MAX_ORDERS)
            throw new IllegalArgumentException("Invalid cache structure");
        Set<K> variants = new HashSet<>();
        for (Cell<K> cell : cells) {
            int groupCapacity = limits.groupCapacity();
            int itemCapacity = cell.filter() != null ? groupCapacity * cell.filter().stackSize() : limits.capacity();
            if (cell.amount() > itemCapacity || cell.minimum() > groupCapacity
                    || cell.maximum() > groupCapacity)
                throw new IllegalArgumentException("Cell exceeds capacity");
            if (cell.filter() != null && !variants.add(cell.filter()))
                throw new IllegalArgumentException("Duplicate exact filter");
        }
        for (var entry : orders.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id()))
                throw new IllegalArgumentException("Order index mismatch");
        }
    }

    public static <K extends MaterialVariant> CacheState<K> empty(UUID id) {
        return new CacheState<>(id, 1, 0, Collections.nCopies(CacheLevel.of(1).slots(), Cell.empty()), Map.of());
    }

    public int find(K variant) {
        for (int i = 0; i < cells.size(); i++)
            if (Objects.equals(variant, cells.get(i).filter())) return i;
        return -1;
    }

    public int pending(int slot) {
        Cell<K> cell = cells.get(slot);
        long result = orders.values().stream()
                .filter(order -> Objects.equals(order.variant(), cell.filter())
                        && order.filterRevision() == cell.filterRevision())
                .mapToLong(SupplyOrder::remaining).sum();
        return (int) Math.min(result, Integer.MAX_VALUE);
    }

    public int requestable(int slot) { return Math.max(0, cells.get(slot).shortage() - pending(slot)); }

    public Arrow arrow(int slot) {
        return cells.get(slot).shortage() == 0 ? Arrow.NONE : pending(slot) > 0 ? Arrow.REQUESTED : Arrow.UNSUPPLIED;
    }

    public CacheEdit<K> edit() { return new CacheEdit<>(this); }

    public enum Arrow { NONE, UNSUPPLIED, REQUESTED }
}
