package dev.scathiard.feedmepackages.storage;

import java.util.UUID;

/** Resolved server authority for one operation; never accept a client-provided handle. */
public record CacheHandle(UUID cacheId, UUID playerId, UUID networkId) {}
