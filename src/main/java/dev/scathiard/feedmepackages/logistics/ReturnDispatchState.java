package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.storage.CacheHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ReturnDispatchState {
    static final int UNKNOWN = 0;
    static final int FAILED = 1;
    static final int SENT = 2;
    private record DispatchEvidence(UUID playerId, UUID networkId, String address, String[] filters, int[] maximums, byte[] cells) {}
    private record ObservedContext(UUID cacheId, UUID networkId, String address) {}
    private static final Map<UUID, DispatchEvidence> DISPATCH = new HashMap<>();
    private static final Map<UUID, ObservedContext> OBSERVED = new HashMap<>();

    private ReturnDispatchState() {}

    static void clear() {
        DISPATCH.clear();
        OBSERVED.clear();
    }

    static void forget(UUID playerId) {
        DISPATCH.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
        OBSERVED.remove(playerId);
    }

    static void reset(CacheHandle handle) {
        DISPATCH.remove(handle.cacheId());
    }

    static void ensureContext(CacheHandle handle, String address, int slots) {
        String normalizedAddress = address == null ? "" : address;
        observe(handle, normalizedAddress);
        DispatchEvidence evidence = DISPATCH.get(handle.cacheId());
        if (evidence == null || !evidence.playerId().equals(handle.playerId())
                || !Objects.equals(evidence.networkId(), handle.networkId())
                || !evidence.address().equals(normalizedAddress)
                || evidence.cells().length != slots) {
            DISPATCH.put(handle.cacheId(), new DispatchEvidence(handle.playerId(), handle.networkId(), normalizedAddress,
                    new String[slots], new int[slots], new byte[slots]));
        }
    }

    static void rememberCell(CacheHandle handle, int slot, String filter, int maximum, int state) {
        DispatchEvidence evidence = DISPATCH.get(handle.cacheId());
        if (evidence == null || slot < 0 || slot >= evidence.cells().length) return;
        evidence.filters()[slot] = filter == null ? "" : filter;
        evidence.maximums()[slot] = maximum;
        evidence.cells()[slot] = (byte)state;
    }

    private static void observe(CacheHandle handle, String address) {
        ObservedContext current = new ObservedContext(handle.cacheId(), handle.networkId(), address == null ? "" : address);
        ObservedContext previous = OBSERVED.put(handle.playerId(), current);
        if (previous != null && !previous.equals(current)) {
            DISPATCH.remove(previous.cacheId());
        }
    }
    static int state(CacheHandle handle, String address, int slot, String filter, int maximum) {
        if (handle == null) return UNKNOWN;
        String observedAddress = address == null ? "" : address;
        observe(handle, observedAddress);
        DispatchEvidence evidence = DISPATCH.get(handle.cacheId());
        if (observedAddress.isEmpty() || filter == null || filter.isEmpty() || maximum < 0) {
            if (evidence != null && slot >= 0 && slot < evidence.cells().length) {
                rememberCell(handle, slot, filter, maximum, UNKNOWN);
            }
            if (observedAddress.isEmpty()) reset(handle);
            return UNKNOWN;
        }
        if (evidence == null || !evidence.playerId().equals(handle.playerId())
                || !Objects.equals(evidence.networkId(), handle.networkId())
                || !evidence.address().equals(observedAddress)
                || slot < 0 || slot >= evidence.cells().length) {
            return UNKNOWN;
        }
        if (!filter.equals(evidence.filters()[slot]) || maximum != evidence.maximums()[slot]) {
            rememberCell(handle, slot, filter, maximum, UNKNOWN);
            return UNKNOWN;
        }
        return evidence.cells()[slot];
    }

    static void rememberForTest(CacheHandle handle, String address, int slot, String filter, int maximum, int state) {
        ensureContext(handle, address, Math.max(slot + 1, 1));
        rememberCell(handle, slot, filter, maximum, state);
    }
}

