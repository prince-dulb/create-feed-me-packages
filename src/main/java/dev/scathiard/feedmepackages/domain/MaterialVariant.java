package dev.scathiard.feedmepackages.domain;

/** Bridge between Minecraft item identity and the pure domain model.
 *  Provides the native stack size so that group-based thresholds
 *  can be converted to item counts. */
public interface MaterialVariant {
    int stackSize();
}
