package net.coreprotect.consumer.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.Database;
import net.coreprotect.database.DatabaseType;
import net.coreprotect.database.RelationalConsumerWriteBatch;
import net.coreprotect.language.Language;

class VirtualContainerInsertProcessTest {

    private final DatabaseType previousType = ConfigHandler.databaseType;

    @BeforeAll
    static void server() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class);
            when(server.getLogger()).thenReturn(Logger.getLogger("VirtualContainerInsertProcessTest"));
            Bukkit.setServer(server);
        }
        Language.loadPhrases();
    }

    @AfterEach
    void restore() {
        ConfigHandler.databaseType = previousType;
    }

    @Test
    void conflictForgetsOnlyThatKey(@TempDir Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("database.db");
        ConfigHandler.databaseType = DatabaseType.SQLITE;
        ConfigHandler.prefix = "co_";
        Consumer.initialize();
        Database.createDatabaseTables("co_", true, DriverManager.getConnection(url), DatabaseType.SQLITE, true);
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO co_virtual_container_map (id, container) VALUES (7,'other:server/y'),(12,'other:server/z')");
            ConfigHandler.virtualContainerId = 7;
            ConfigHandler.virtualContainers.clear();
            ConfigHandler.virtualContainersReversed.clear();
            // this server handed out 7 for its own key and 8 for another, before seeing the other server's rows
            ConfigHandler.cacheIdentifier(ConfigHandler.CacheType.VIRTUAL_CONTAINERS, 7, "myplugin:backpack/new");
            ConfigHandler.cacheIdentifier(ConfigHandler.CacheType.VIRTUAL_CONTAINERS, 8, "myplugin:backpack/fresh");
            ConfigHandler.cacheIdentifier(ConfigHandler.CacheType.VIRTUAL_CONTAINERS, 12, "other:server/z");

            try (RelationalConsumerWriteBatch batch = new RelationalConsumerWriteBatch(connection, DatabaseType.SQLITE)) {
                batch.begin();
                VirtualContainerInsertProcess.process(batch, statement, 0, "myplugin:backpack/new", 7);
                VirtualContainerInsertProcess.process(batch, statement, 1, "myplugin:backpack/fresh", 8);
                // a retried insert of a row that is already stored changes nothing
                VirtualContainerInsertProcess.process(batch, statement, 2, "other:server/z", 12);
                assertTrue(batch.commit());
            }

            assertFalse(ConfigHandler.virtualContainers.containsKey("myplugin:backpack/new"));
            assertEquals(8, ConfigHandler.virtualContainers.get("myplugin:backpack/fresh"));
            assertEquals(12, ConfigHandler.virtualContainers.get("other:server/z"));
            assertEquals(12, ConfigHandler.virtualContainerId);
            try (ResultSet resultSet = statement.executeQuery("SELECT id,container FROM co_virtual_container_map ORDER BY id")) {
                assertTrue(resultSet.next());
                assertEquals("7/other:server/y", resultSet.getInt(1) + "/" + resultSet.getString(2));
                assertTrue(resultSet.next());
                assertEquals("8/myplugin:backpack/fresh", resultSet.getInt(1) + "/" + resultSet.getString(2));
                assertTrue(resultSet.next());
                assertEquals("12/other:server/z", resultSet.getInt(1) + "/" + resultSet.getString(2));
                assertFalse(resultSet.next());
            }
        }
    }
}
