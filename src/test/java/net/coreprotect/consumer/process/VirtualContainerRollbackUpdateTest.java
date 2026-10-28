package net.coreprotect.consumer.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.Database;
import net.coreprotect.database.DatabaseType;
import net.coreprotect.database.RelationalConsumerWriteBatch;
import net.coreprotect.model.rollback.RollbackUpdateTargets;

class VirtualContainerRollbackUpdateTest {

    private final DatabaseType previousType = ConfigHandler.databaseType;

    @AfterEach
    void restore() {
        ConfigHandler.databaseType = previousType;
    }

    @Test
    void updatesOnlyTheVirtualContainerTable(@TempDir Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("database.db");
        ConfigHandler.databaseType = DatabaseType.SQLITE;
        ConfigHandler.prefix = "co_";
        Database.createDatabaseTables("co_", true, DriverManager.getConnection(url), DatabaseType.SQLITE, true);
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO co_virtual_container (time,user,container_id,type,data,amount,metadata,action,rolled_back) VALUES (1,1,1,1,0,1,NULL,0,0),(2,1,1,1,0,1,NULL,1,0),(3,1,1,1,0,1,NULL,1,0)");
            // block container rows share rowids with virtual rows and must keep their state
            statement.executeUpdate("INSERT INTO co_container (time,user,wid,x,y,z,type,data,amount,metadata,action,rolled_back) VALUES (1,1,1,0,0,0,1,0,1,NULL,0,0),(2,1,1,0,0,0,1,0,1,NULL,1,0)");

            apply(connection, rows(0, 1L, 2L), 0);
            assertEquals(Arrays.asList(1, 1, 0), states(statement, "virtual_container"));
            assertEquals(Arrays.asList(0, 0), states(statement, "container"));

            apply(connection, rows(1, 2L), 1);
            assertEquals(Arrays.asList(1, 0, 0), states(statement, "virtual_container"));
        }
        assertTrue(Process.isRollbackPublication(Process.VIRTUAL_CONTAINER_ROLLBACK_UPDATE, null));
    }

    private static void apply(Connection connection, List<Object[]> rows, int rollbackType) throws Exception {
        try (RelationalConsumerWriteBatch batch = new RelationalConsumerWriteBatch(connection, DatabaseType.SQLITE)) {
            batch.begin();
            RollbackUpdateProcess.process(batch, rows, rollbackType, RollbackUpdateTargets.VIRTUAL_CONTAINER, false);
            assertTrue(batch.commit());
        }
    }

    private static List<Object[]> rows(int rolledBack, Long... rowIds) {
        List<Object[]> rows = new ArrayList<>();
        for (Long rowId : rowIds) {
            Object[] row = new Object[16];
            row[0] = rowId;
            row[9] = rolledBack;
            rows.add(row);
        }
        return rows;
    }

    private static List<Integer> states(Statement statement, String table) throws Exception {
        List<Integer> states = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery("SELECT rolled_back FROM co_" + table + " ORDER BY rowid")) {
            while (resultSet.next()) {
                states.add(resultSet.getInt(1));
            }
        }
        return states;
    }
}
