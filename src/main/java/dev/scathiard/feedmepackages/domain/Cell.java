package dev.scathiard.feedmepackages.domain;

import java.util.Objects;

/** A filter is a template, never stock. -1 disables a threshold.
 *  Thresholds and capacity are in groups; amount is in items. */
public record Cell<K extends MaterialVariant>(K filter, int amount, int minimum, int maximum, long filterRevision) {
    public Cell {
        if (amount < 0 || minimum < -1 || maximum < -1 || filterRevision < 0)
            throw new IllegalArgumentException("Negative cell state");
        if (filter == null && (amount != 0 || minimum != -1 || maximum != -1))
            throw new IllegalArgumentException("Unfiltered cell holds stock or thresholds");
        if (minimum >= 0 && maximum >= 0 && minimum > maximum)
            throw new IllegalArgumentException("Minimum exceeds maximum");
    }

    public static <K extends MaterialVariant> Cell<K> empty() { return new Cell<>(null, 0, -1, -1, 0); }

    public Cell<K> withAmount(int next) {
        return new Cell<>(filter, next, minimum, maximum, filterRevision);
    }

    public Cell<K> configure(K next) {
        if (Objects.equals(filter, next)) return this;
        if (amount != 0) throw new IllegalStateException("Empty the cell before changing its filter");
        return new Cell<>(next, 0, -1, -1, Math.incrementExact(filterRevision));
    }

    public Cell<K> thresholds(int min, int max) {
        return new Cell<>(filter, amount, min, max, filterRevision);
    }

    /** Shortage in items: minimum groups × stack size − current amount. */
    public int shortage() { return minimum < 0 ? 0 : Math.max(0, minimum * filter.stackSize() - amount); }
}
