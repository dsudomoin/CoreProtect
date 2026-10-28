package net.coreprotect.database.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ClickHouseVirtualContainerWriterTest {

    @Test
    void virtualRowsLandInPartitionZero() throws Exception {
        ClickHouseBatchIdentity identity = ClickHouseBatchIdentity.create(UUID.randomUUID(), 1);
        AtomicLong rowIds = new AtomicLong();
        try (ClickHouseEventBatch batch = new ClickHouseEventBatch(identity, family -> rowIds.incrementAndGet())) {
            batch.addVirtualContainerMap(7, "myplugin:backpack/1f0c");
            batch.addVirtualContainer(1_792_368_000, 3, 7, 42, 0, 5, new byte[] { 1, 2 }, 1, 0);
            assertEquals(2, batch.size());
            Map<Integer, Integer> partitions = batch.seal(new ClickHouseStateBatch(identity));
            // no lookup-index rows (family code 0), both rows in partition 0
            assertEquals(Map.of(0, 2), partitions);
        }
    }

    @Test
    void virtualRollbackStateLandsInPartitionZero() throws Exception {
        ClickHouseBatchIdentity identity = ClickHouseBatchIdentity.create(UUID.randomUUID(), 1);
        ClickHouseStateBatch state = new ClickHouseStateBatch(identity);
        // rejected with "rollback state is unsupported" before virtual containers were a rollback family
        state.addRollback(ClickHouseFamily.VIRTUAL_CONTAINER, 5, 1_792_368_000, 0, 0, 0, 1);
        AtomicLong rowIds = new AtomicLong();
        try (ClickHouseEventBatch batch = new ClickHouseEventBatch(identity, family -> rowIds.incrementAndGet())) {
            assertEquals(Map.of(0, 1), batch.seal(state));
        }
    }
}
