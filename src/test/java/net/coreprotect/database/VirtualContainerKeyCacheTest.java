package net.coreprotect.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.consumer.process.Process;

/**
 * Virtual container keys are resolved on demand: startup loads only the highest id, a cache miss reads the key from
 * the consumer's own connection, and a new key continues from the highest stored id.
 */
class VirtualContainerKeyCacheTest {

    private DatabaseType previousType;
    private boolean previousLock;

    @BeforeEach
    void setUp() {
        previousType = ConfigHandler.databaseType;
        previousLock = Config.getGlobal().DATABASE_LOCK;
        // database-lock defaults to true: a single server owns the database
        Config.getGlobal().DATABASE_LOCK = true;
        ConfigHandler.prefix = "co_";
        Consumer.initialize();
    }

    @AfterEach
    void restore() {
        ConfigHandler.databaseType = previousType;
        Config.getGlobal().DATABASE_LOCK = previousLock;
    }

    @Test
    void sqlite(@TempDir Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("database.db");
        ConfigHandler.databaseType = DatabaseType.SQLITE;
        Database.createDatabaseTables("co_", true, DriverManager.getConnection(url), DatabaseType.SQLITE, true);
        try (Connection connection = DriverManager.getConnection(url)) {
            verify(connection, DatabaseType.SQLITE);
        }
    }

    @Test
    void duckdb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            ConfigHandler.databaseType = DatabaseType.DUCKDB;
            Database.createDatabaseTables("co_", true, connection, DatabaseType.DUCKDB, true);
            verify(connection, DatabaseType.DUCKDB);
        }
    }

    private static void verify(Connection connection, DatabaseType type) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO co_virtual_container_map (id, container) VALUES (3,'myplugin:backpack/x'),(7,'myplugin:backpack/y')");

            // a fresh server: startup loads no keys, only the highest id
            ConfigHandler.virtualContainerId = 0;
            ConfigHandler.virtualContainers.put("stale:key/1", 1);
            assertTrue(ConfigHandler.loadTypes(statement));
            assertTrue(ConfigHandler.virtualContainers.isEmpty());
            assertTrue(ConfigHandler.virtualContainersReversed.isEmpty());
            assertEquals(7, ConfigHandler.virtualContainerId);

            try (RelationalConsumerWriteBatch batch = new RelationalConsumerWriteBatch(connection, type)) {
                // an existing key resolves from the database and is cached on its own
                assertEquals(3, batch.resolveVirtualContainerId("myplugin:backpack/x"));
                assertEquals(1, ConfigHandler.virtualContainers.size());
                assertEquals("myplugin:backpack/x", ConfigHandler.virtualContainersReversed.get(3));
                assertEquals(3, batch.resolveVirtualContainerId("myplugin:backpack/x"));

                // a new key continues from the highest stored id and queues its map row
                assertEquals(8, batch.resolveVirtualContainerId("myplugin:backpack/new"));
                assertEquals(8, batch.resolveVirtualContainerId("myplugin:backpack/new"));
                assertEquals(9, batch.resolveVirtualContainerId("other:thing/a"));
                List<Object[]> inserts = new ArrayList<>();
                for (Object[] data : Consumer.consumer.get(Consumer.currentConsumer)) {
                    if ((int) data[1] == Process.VIRTUAL_CONTAINER_INSERT) {
                        inserts.add(data);
                    }
                }
                assertEquals(2, inserts.size());
                assertEquals(8, inserts.get(0)[6]);
                assertEquals("myplugin:backpack/new", Consumer.consumerObjects.get(Consumer.currentConsumer).get((int) inserts.get(0)[0]));
                assertEquals(9, inserts.get(1)[6]);
            }

            // a stored id the cache never saw (another writer) is never handed out again
            statement.executeUpdate("INSERT INTO co_virtual_container_map (id, container) VALUES (20,'myplugin:backpack/z')");
            assertTrue(ConfigHandler.loadTypes(statement));
            assertEquals(20, ConfigHandler.virtualContainerId);
            try (RelationalConsumerWriteBatch batch = new RelationalConsumerWriteBatch(connection, type)) {
                assertEquals(21, batch.resolveVirtualContainerId("myplugin:backpack/after-restart"));
            }
        }
    }
}
