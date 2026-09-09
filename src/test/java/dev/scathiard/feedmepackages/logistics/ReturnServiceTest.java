package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.storage.CacheHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReturnServiceTest {
    @AfterEach void clear() {
        ReturnDispatchState.clear();
    }

    @Test void dispatchEvidenceDoesNotReviveAfterViewingAnotherContext() {
        UUID player = UUID.randomUUID();
        UUID network = UUID.randomUUID();
        CacheHandle first = new CacheHandle(UUID.randomUUID(), player, network);
        CacheHandle second = new CacheHandle(UUID.randomUUID(), player, network);
        ReturnDispatchState.rememberForTest(first, "FMP@A", 0, "minecraft:stone", 1, ReturnDispatchState.SENT);

        assertEquals(ReturnDispatchState.SENT, ReturnDispatchState.state(first, "FMP@A", 0, "minecraft:stone", 1));
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(second, "FMP@B", 0, "minecraft:stone", 1));
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(first, "FMP@A", 0, "minecraft:stone", 1));
    }

    @Test void dispatchEvidenceExpiresWhenTheCellConfigurationChanges() {
        UUID player = UUID.randomUUID();
        UUID network = UUID.randomUUID();
        CacheHandle handle = new CacheHandle(UUID.randomUUID(), player, network);
        ReturnDispatchState.rememberForTest(handle, "FMP@A", 0, "minecraft:stone", 1, ReturnDispatchState.SENT);

        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "minecraft:dirt", 1));
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "minecraft:stone", 1));

        ReturnDispatchState.rememberForTest(handle, "FMP@A", 0, "minecraft:stone", 1, ReturnDispatchState.SENT);
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "minecraft:stone", 2));
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "minecraft:stone", 1));
    }

    @Test void dispatchEvidenceSurvivesWhenDispatchRecordsTheCurrentContext() {
        UUID player = UUID.randomUUID();
        UUID network = UUID.randomUUID();
        CacheHandle first = new CacheHandle(UUID.randomUUID(), player, network);
        CacheHandle second = new CacheHandle(UUID.randomUUID(), player, network);

        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(first, "FMP@A", 0, "minecraft:stone", 1));
        ReturnDispatchState.ensureContext(second, "FMP@B", 1);
        ReturnDispatchState.rememberCell(second, 0, "minecraft:stone", 1, ReturnDispatchState.SENT);

        assertEquals(ReturnDispatchState.SENT, ReturnDispatchState.state(second, "FMP@B", 0, "minecraft:stone", 1));
    }

    @Test void invalidCellConfigurationClearsPreviousDispatchEvidence() {
        UUID player = UUID.randomUUID();
        UUID network = UUID.randomUUID();
        CacheHandle handle = new CacheHandle(UUID.randomUUID(), player, network);
        ReturnDispatchState.rememberForTest(handle, "FMP@A", 0, "minecraft:stone", 1, ReturnDispatchState.SENT);

        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "minecraft:stone", -1));
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "minecraft:stone", 1));

        ReturnDispatchState.rememberForTest(handle, "FMP@A", 0, "minecraft:stone", 1, ReturnDispatchState.SENT);
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "", 1));
        assertEquals(ReturnDispatchState.UNKNOWN, ReturnDispatchState.state(handle, "FMP@A", 0, "minecraft:stone", 1));
    }
}

