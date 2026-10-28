package net.coreprotect.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.statement.ContainerStatement;
import net.coreprotect.model.action.EntityActionFilter;
import net.coreprotect.model.action.LookupActions;
import net.coreprotect.model.item.InventorySources;
import net.coreprotect.model.item.ItemTransactionActions;
import net.coreprotect.model.lookup.EntityLookupContext;
import net.coreprotect.model.lookup.LookupRollbackState;
import net.coreprotect.model.lookup.VirtualContainerTarget;

/**
 * Runs the container: lookup SQL built by LookupRaw against embedded SQLite and DuckDB.
 */
class VirtualContainerLookupTest {

    private DatabaseType previousType;

    @AfterEach
    void restore() {
        if (previousType != null) {
            ConfigHandler.databaseType = previousType;
        }
    }

    @Test
    void sqlite(@TempDir Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("database.db");
        use(DatabaseType.SQLITE);
        Database.createDatabaseTables("co_", true, DriverManager.getConnection(url), DatabaseType.SQLITE, true);
        try (Connection connection = DriverManager.getConnection(url)) {
            verify(connection);
        }
    }

    @Test
    void duckdb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            use(DatabaseType.DUCKDB);
            Database.createDatabaseTables("co_", true, connection, DatabaseType.DUCKDB, true);
            verify(connection);
        }
    }

    private void use(DatabaseType type) {
        previousType = ConfigHandler.databaseType;
        ConfigHandler.databaseType = type;
        ConfigHandler.prefix = "co_";
    }

    private static void verify(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO co_virtual_container_map (id, container) VALUES (1,'myplugin:backpack/a'),(2,'myplugin:backpack/b'),(3,'myplugin:backpacks/x'),(4,'other:thing/a')");
            String user = ConfigHandler.databaseType.getUserColumn();
            // container 1: removed at 100, added at 200 (rolled back); container 2, 3, 4: one addition each
            statement.executeUpdate("INSERT INTO co_virtual_container (time," + user + ",container_id,type,data,amount,metadata,action,rolled_back) VALUES "
                    + "(100,1,1,10,0,3,NULL,0,0),(200,1,1,11,0,4,NULL,1,1),(300,1,2,12,0,5,NULL,1,0),(400,1,3,13,0,6,NULL,1,0),(500,1,4,14,0,7,NULL,1,0)");
            // a block container row must never show up in a container: lookup
            statement.executeUpdate("INSERT INTO co_container (time," + user + ",wid,x,y,z,type,data,amount,metadata,action,rolled_back) VALUES (600,1,1,0,0,0,15,0,1,NULL,1,0)");

            assertEquals(Arrays.asList(200, 100), times(statement, target("myplugin:backpack", "a"), container(), LookupRollbackState.ANY, 0));
            assertEquals(Arrays.asList(300, 200, 100), times(statement, target("myplugin:backpack", null), container(), LookupRollbackState.ANY, 0));
            assertEquals(Collections.singletonList(500), times(statement, target("other:thing", null), container(), LookupRollbackState.ANY, 0));
            assertEquals(Collections.emptyList(), times(statement, target("myplugin:backpack", "zzz"), container(), LookupRollbackState.ANY, 0));
            assertEquals(Collections.singletonList(100), times(statement, target("myplugin:backpack", null), container(ItemTransactionActions.REMOVE), LookupRollbackState.ANY, 0));
            assertEquals(Collections.singletonList(200), times(statement, target("myplugin:backpack", null), container(), LookupRollbackState.ROLLED_BACK, 0));
            assertEquals(Collections.singletonList(300), times(statement, target("myplugin:backpack", null), container(), LookupRollbackState.ANY, 250));

            List<Object[]> rows = LookupRaw.performLookupRaw(statement, null, new ArrayList<>(), Collections.singletonList("#global"), list(target("myplugin:backpack", "b")), new HashMap<>(), new ArrayList<>(), container(), EntityActionFilter.DEFAULT, Collections.emptyList(), EntityLookupContext.legacy(Collections.emptySet(), Collections.emptySet()), null, null, null, 0, 0, 0, 4, false, true, null, LookupRollbackState.ANY);
            assertNotNull(rows);
            assertEquals(1, rows.size());
            Object[] row = rows.get(0);
            // { id, time, user, x, y, z, type, data, action, rolled_back, wid, amount, meta, blockdata, tbl, entity_spawn_rowid }
            assertEquals(300, row[1]);
            assertEquals(12, row[6]);
            assertEquals(1, row[8]);
            assertEquals(5, row[11]);
            assertEquals(InventorySources.VIRTUAL_CONTAINER, row[14]);
            assertEquals(2, row[15]);

            // a rollback lookup (lookup=false) must never return virtual rows
            try (ResultSet rollbackRows = LookupRaw.rawLookupResultSet(statement, null, new ArrayList<>(), Collections.singletonList("#global"), list(target("myplugin:backpack", null)), new HashMap<>(), new ArrayList<>(), container(), EntityActionFilter.DEFAULT, Collections.emptyList(), EntityLookupContext.legacy(Collections.emptySet(), Collections.emptySet()), null, null, null, 0, 0, -1, -1, false, false, false, null, LookupRollbackState.ANY)) {
                assertNull(rollbackRows);
            }

            Map<Integer, String> keys = ContainerStatement.loadVirtualKeys(connection, new TreeSet<>(Arrays.asList(1, 4, 9)));
            assertEquals(2, keys.size());
            assertEquals("myplugin:backpack/a", keys.get(1));
            assertEquals("other:thing/a", keys.get(4));

            long count = Lookup.countLookupRows(statement, null, new ArrayList<>(), Collections.singletonList("#global"), list(target("myplugin:backpack", null)), new HashMap<>(), new ArrayList<>(), container(), EntityActionFilter.DEFAULT, Collections.emptyList(), EntityLookupContext.legacy(Collections.emptySet(), Collections.emptySet()), null, null, null, 0, 0, false, true, null, LookupRollbackState.ANY);
            assertEquals(3, count);

            // #summary: container 1 lost 3 of type 10 and gained 4 of type 11, container 2 gained 5 of type 12
            List<net.coreprotect.model.lookup.LookupSummaryRow> summary = Lookup.performSummaryLookup(statement, null, new ArrayList<>(), Collections.singletonList("#global"), list(target("myplugin:backpack", null)), new HashMap<>(), new ArrayList<>(), container(), EntityActionFilter.DEFAULT, EntityLookupContext.legacy(Collections.emptySet(), Collections.emptySet()), null, null, 0, 0, 0, 10, false, null, LookupRollbackState.ANY);
            assertEquals(3, summary.size());
            Map<Integer, Long> net = new HashMap<>();
            for (net.coreprotect.model.lookup.LookupSummaryRow summaryRow : summary) {
                net.put(summaryRow.getMaterialId(), summaryRow.getAmount());
            }
            assertEquals(-3L, net.get(10));
            assertEquals(4L, net.get(11));
            assertEquals(5L, net.get(12));

            if (ConfigHandler.databaseType.isDuckDB()) {
                net.coreprotect.model.lookup.LookupPage page = Lookup.performDuckDBLookupPage(statement, null, new ArrayList<>(), Collections.singletonList("#global"), list(target("myplugin:backpack", null)), new HashMap<>(), new ArrayList<>(), container(), EntityActionFilter.DEFAULT, Collections.emptyList(), EntityLookupContext.legacy(Collections.emptySet(), Collections.emptySet()), null, null, new Long[] { 0L, 0L, 0L, 0L, 0L }, 0, 0, 2, false, true, null, LookupRollbackState.ANY);
                assertEquals(3, page.getTotalRows());
                assertEquals(2, page.getRows().size());
                assertEquals("300", page.getRows().get(0)[0]);
            }
        }
    }

    private static List<Integer> times(Statement statement, VirtualContainerTarget target, List<Integer> actions, LookupRollbackState state, long startTime) throws Exception {
        List<Integer> times = new ArrayList<>();
        try (ResultSet resultSet = LookupRaw.rawLookupResultSet(statement, null, new ArrayList<>(), Collections.singletonList("#global"), list(target), new HashMap<>(), new ArrayList<>(), actions, EntityActionFilter.DEFAULT, Collections.emptyList(), EntityLookupContext.legacy(Collections.emptySet(), Collections.emptySet()), null, null, null, startTime, 0, -1, -1, false, true, false, null, state)) {
            assertNotNull(resultSet);
            while (resultSet.next()) {
                assertEquals(InventorySources.VIRTUAL_CONTAINER, resultSet.getInt("tbl"));
                times.add(resultSet.getInt("time"));
            }
        }
        return times;
    }

    private static VirtualContainerTarget target(String type, String id) {
        return new VirtualContainerTarget(type, id);
    }

    private static List<Object> list(Object value) {
        List<Object> list = new ArrayList<>();
        list.add(value);
        return list;
    }

    private static List<Integer> container(Integer... extra) {
        List<Integer> actions = new ArrayList<>();
        actions.add(LookupActions.CONTAINER);
        actions.addAll(Arrays.asList(extra));
        return actions;
    }
}
