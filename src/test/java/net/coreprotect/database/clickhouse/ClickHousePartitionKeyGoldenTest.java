package net.coreprotect.database.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Golden values captured from origin/master 2d484df (before any virtual container change). Any difference makes
 * validateTable refuse every existing ClickHouse database at startup.
 */
class ClickHousePartitionKeyGoldenTest {

    private static final String MASTER_PARTITION_KEY = "if(family='_batch_receipt',wid,if(family IN ('block','chat','command','container','entity_container','entity_interaction','item','entity','session','sign','skull'),toYYYYMM(toDateTime(time,'UTC')),0))";
    private static final Set<String> MASTER_TIME_PARTITIONED = new HashSet<>(Arrays.asList("block", "chat", "command", "container", "entity_container", "entity_interaction", "item", "entity", "session", "sign", "skull"));
    // 2026-10-19T00:00:00Z
    private static final int TIME = 1_792_368_000;

    @Test
    void partitionKeyIsByteIdenticalToMaster() throws Exception {
        Field field = ClickHouseSchema.class.getDeclaredField("EVENT_PARTITION_KEY");
        field.setAccessible(true);
        assertEquals(MASTER_PARTITION_KEY, field.get(null));
    }

    @Test
    void createStatementUsesMasterPartitionKey() {
        List<String> statements = ClickHouseSchema.createStatements("db", "co_");
        boolean found = false;
        for (String statement : statements) {
            if (statement.contains("event_data") && statement.contains("PARTITION BY")) {
                assertTrue(statement.contains(" PARTITION BY " + MASTER_PARTITION_KEY + " ORDER BY "), statement);
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void virtualContainersArePurgedByMutationOutsideMonthlyPartitions() throws Exception {
        Field field = ClickHouseRetention.class.getDeclaredField("PURGEABLE_FAMILIES");
        field.setAccessible(true);
        String purgeable = (String) field.get(null);
        assertTrue(purgeable.contains("'virtual_container'"), purgeable);
        assertTrue(!purgeable.contains("'virtual_container_map'"), purgeable);
        assertEquals(0, ClickHouseSchema.eventPartitionId(ClickHouseFamily.VIRTUAL_CONTAINER, TIME));
        assertEquals(0, ClickHouseSchema.eventPartitionId(ClickHouseFamily.VIRTUAL_CONTAINER_MAP, TIME));
    }

    @Test
    void virtualContainerViewsAreCreated() {
        String statements = String.join("\n", ClickHouseSchema.createStatements("db", "co_"));
        assertTrue(statements.contains("CREATE OR REPLACE VIEW `db`.`co_virtual_container` AS SELECT e.rowid AS rowid,e.time AS time,e.user_id AS `user`,e.entity_spawn_rowid AS container_id"), statements);
        assertTrue(statements.contains("WHERE family='virtual_container'"));
        assertTrue(statements.contains("CREATE OR REPLACE VIEW `db`.`co_virtual_container_map` AS SELECT e.rowid AS rowid,e.id AS id,e.name AS container"));
    }

    @Test
    void eventPartitionIdMatchesMasterForEveryFamily() {
        for (ClickHouseFamily family : ClickHouseFamily.values()) {
            int expected = MASTER_TIME_PARTITIONED.contains(family.getTableName()) ? 202610 : 0;
            assertEquals(expected, ClickHouseSchema.eventPartitionId(family, TIME), family.getTableName());
        }
    }
}
