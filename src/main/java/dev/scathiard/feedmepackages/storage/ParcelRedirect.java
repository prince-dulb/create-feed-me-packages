package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.domain.CacheMerge;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Post-manufacture routing only. Never resolve inventory access through this table. */
public record ParcelRedirect(UUID target, Map<CacheMerge.Route<ItemVariantKey>, Long> variants) {
    public ParcelRedirect {
        Objects.requireNonNull(target); variants = Map.copyOf(variants);
        if (variants.isEmpty() || variants.size() > 36 || variants.values().stream().anyMatch(revision -> revision < 0))
            throw new IllegalArgumentException("Invalid parcel redirect");
    }
}
