package dev.scathiard.feedmepackages.domain;

import java.util.Objects;
import java.util.UUID;

/** Remains after manual restocking; only received real parcels or explicit reset settle it. */
public record SupplyOrder<K extends MaterialVariant>(UUID id, UUID player, K variant, long filterRevision,
                             int remaining, boolean uncertain) {
    public SupplyOrder {
        Objects.requireNonNull(id);
        Objects.requireNonNull(player);
        Objects.requireNonNull(variant);
        if (filterRevision < 0 || remaining <= 0)
            throw new IllegalArgumentException("Invalid supply order");
    }
}
