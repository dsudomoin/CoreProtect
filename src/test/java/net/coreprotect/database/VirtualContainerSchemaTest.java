package net.coreprotect.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.coreprotect.config.ConfigHandler;

/**
 * Runs the real startup DDL and insert statements for the virtual container tables on embedded SQLite and DuckDB.
 */
class VirtualContainerSchemaTest {

    private DatabaseType previousType;

    @AfterEach
    void restore() {
        if (previousType != null) {
            ConfigHandler.databaseType = previousType;
        }
    }

    @Test
    void sqliteCreatesTablesIndexesAndAcceptsInserts(@TempDir Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("database.db");
        use(DatabaseType.SQLITE);
        // the SQLite schema path closes the connection it is given
        Database.createDatabaseTables("co_", true, DriverManager.getConnection(url), DatabaseType.SQLITE, true);
        try (Connection connection = DriverManager.getConnection(url)) {
            Set<String> indexes = new HashSet<>();
            try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")) {
                while (resultSet.next()) {
                    indexes.add(resultSet.getString(1));
                }
            }
            assertTrue(indexes.contains("virtual_container_index"), indexes.toString());
            assertTrue(indexes.contains("virtual_container_user_index"));
            assertTrue(indexes.contains("virtual_container_type_index"));
            assertTrue(indexes.contains("virtual_container_map_id_index"));
            assertTrue(indexes.contains("virtual_container_map_container_index"));
            insertAndRead(connection);
        }
    }

    @Test
    void duckdbCreatesTablesAndAcceptsInserts() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            use(DatabaseType.DUCKDB);
            Database.createDatabaseTables("co_", true, connection, DatabaseType.DUCKDB, true);
            insertAndRead(connection);
        }
    }

    private void use(DatabaseType type) {
        previousType = ConfigHandler.databaseType;
        ConfigHandler.databaseType = type;
        ConfigHandler.prefix = "co_";
    }

    private static void insertAndRead(Connection connection) throws Exception {
        try (PreparedStatement map = Database.prepareStatement(connection, Database.VIRTUAL_CONTAINER_MAP, false)) {
            map.setInt(1, 7);
            map.setString(2, "myplugin:backpack/1f0c");
            map.executeUpdate();
        }
        try (PreparedStatement row = Database.prepareStatement(connection, Database.VIRTUAL_CONTAINER, false)) {
            row.setInt(1, 1000);
            row.setInt(2, 3);
            row.setInt(3, 7);
            row.setInt(4, 42);
            row.setInt(5, 0);
            row.setInt(6, 5);
            row.setObject(7, null);
            row.setInt(8, 1);
            row.setInt(9, 0);
            row.executeUpdate();
        }
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT rowid,container_id,amount FROM co_virtual_container")) {
            assertTrue(resultSet.next());
            assertTrue(resultSet.getLong(1) > 0);
            assertEquals(7, resultSet.getInt(2));
            assertEquals(5, resultSet.getInt(3));
        }
    }
}
