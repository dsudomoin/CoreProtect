package net.coreprotect.database.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import net.coreprotect.api.VirtualContainerChange;
import net.coreprotect.api.VirtualContainerHandler;
import net.coreprotect.bukkit.BukkitAdapter;
import net.coreprotect.bukkit.BukkitInterface;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.consumer.process.Process;
import net.coreprotect.database.Database;
import net.coreprotect.database.DatabaseType;
import net.coreprotect.database.Lookup;
import net.coreprotect.database.RelationalConsumerWriteBatch;
import net.coreprotect.model.action.EntityActionFilter;
import net.coreprotect.model.action.LookupActions;
import net.coreprotect.model.item.InventorySources;
import net.coreprotect.model.item.ItemTransactionActions;
import net.coreprotect.model.lookup.EntityLookupContext;
import net.coreprotect.model.lookup.VirtualContainerTarget;
import net.coreprotect.model.rollback.RollbackUpdateTargets;
import net.coreprotect.paper.PaperAdapter;
import net.coreprotect.paper.PaperInterface;
import net.coreprotect.utility.ItemUtils;

class VirtualContainerRollbackHandlerTest {

    private static final int DIAMOND = 1;
    private static final int STONE = 2;

    private final DatabaseType previousType = ConfigHandler.databaseType;
    private final BukkitInterface previousAdapter = BukkitAdapter.ADAPTER;
    private final List<Object> works = new ArrayList<>();
    private MockedConstruction<ItemStack> itemStacks;

    @BeforeAll
    static void server() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class);
            when(server.getLogger()).thenReturn(Logger.getLogger("VirtualContainerRollbackHandlerTest"));
            Bukkit.setServer(server);
        }
    }

    @BeforeEach
    void setUp() {
        ConfigHandler.databaseType = DatabaseType.SQLITE;
        ConfigHandler.prefix = "co_";
        ConfigHandler.materialsReversed.put(DIAMOND, "minecraft:diamond");
        ConfigHandler.materialsReversed.put(STONE, "minecraft:stone");
        BukkitInterface adapter = mock(BukkitInterface.class);
        when(adapter.parseLegacyName(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        BukkitAdapter.ADAPTER = adapter;
        // paper-api item stacks need a running server, so each new ItemStack(type, amount) is a mock of that stack
        itemStacks = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getType()).thenReturn((Material) context.arguments().get(0));
            when(stack.getAmount()).thenReturn((Integer) context.arguments().get(1));
            when(stack.clone()).thenReturn(stack);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        itemStacks.close();
        for (Object work : works) {
            VirtualContainerRollbackHandler.release((VirtualContainerRollbackHandler.Work) work);
        }
        BukkitAdapter.ADAPTER = previousAdapter;
        ConfigHandler.databaseType = previousType;
    }

    @Test
    void groupsEligibleRowsInApplyOrder(@TempDir Path directory) throws Exception {
        try (Connection connection = database(directory)) {
            // lookup order is newest first
            List<Object[]> rows = Arrays.asList(row(6, 1, ItemTransactionActions.ADD, 0), row(5, 2, ItemTransactionActions.REMOVE, 0), row(4, 1, ItemTransactionActions.REMOVE, 1),
                    row(3, 1, ItemTransactionActions.REMOVE, 0), row(2, 1, ItemTransactionActions.ADD, 1), row(1, 1, ItemTransactionActions.ADD, 0));

            VirtualContainerRollbackHandler.Work rollback = prepare(connection, rows, 0);
            assertEquals(Arrays.asList("myplugin:backpack/a", "myplugin:backpack/b"), keys(rollback));
            assertEquals(Arrays.asList(6L, 3L, 1L), rowIds(containers(rollback).get(0)));
            assertEquals(Collections.singletonList(5L), rowIds(containers(rollback).get(1)));
            VirtualContainerRollbackHandler.release(rollback);

            // a restore replays the rolled back rows oldest first
            VirtualContainerRollbackHandler.Work restore = prepare(connection, rows, 1);
            assertEquals(Collections.singletonList("myplugin:backpack/a"), keys(restore));
            assertEquals(Arrays.asList(2L, 4L), rowIds(containers(restore).get(0)));
        }
    }

    @Test
    void secondRollbackOfAContainerIsCancelled(@TempDir Path directory) throws Exception {
        try (Connection connection = database(directory)) {
            List<Object[]> rows = Collections.singletonList(row(1, 1, ItemTransactionActions.ADD, 0));
            EntitySpawnRollbackHandler.Context first = context(0);
            works.add(VirtualContainerRollbackHandler.prepare(connection, rows, 0, first));
            assertFalse(first.isCancelled());

            EntitySpawnRollbackHandler.Context second = context(0);
            works.add(VirtualContainerRollbackHandler.prepare(connection, rows, 0, second));
            assertTrue(second.isCancelled());

            // once the first one releases its claim the container can be rolled back again
            VirtualContainerRollbackHandler.release((VirtualContainerRollbackHandler.Work) works.get(0));
            EntitySpawnRollbackHandler.Context third = context(0);
            works.add(VirtualContainerRollbackHandler.prepare(connection, rows, 0, third));
            assertFalse(third.isCancelled());
        }
    }

    @Test
    void handlerReceivesResolvedChangesAndOnlyItsPrefixIsMarked(@TempDir Path directory) throws Exception {
        List<String> received = new ArrayList<>();
        register("myplugin:backpack", true, (id, changes) -> {
            for (VirtualContainerChange change : changes) {
                received.add(id + " " + (change.isAddition() ? "+" : "-") + change.getItem().getAmount() + " " + change.getItem().getType() + " " + change.getSlot());
            }
            return 1;
        });
        try (Connection connection = database(directory)) {
            List<Object[]> rows = Arrays.asList(row(3, 1, ItemTransactionActions.ADD, 0, DIAMOND, 4), row(2, 1, ItemTransactionActions.REMOVE, 0, STONE, 7), row(1, 1, ItemTransactionActions.ADD, 0, DIAMOND, 1));
            EntitySpawnRollbackHandler.Context context = context(0);
            VirtualContainerRollbackHandler.Work rollback = VirtualContainerRollbackHandler.prepare(connection, rows, 0, context);
            works.add(rollback);

            List<Object[]> applied = VirtualContainerRollbackHandler.apply(context, rollback, containers(rollback).get(0));
            // a rollback takes added items out and puts removed items back, newest first
            assertEquals(Arrays.asList("a -4 DIAMOND -1", "a +7 STONE -1", "a -1 DIAMOND -1"), received);
            assertEquals(1, applied.size());
            assertSame(rows.get(0), applied.get(0));
            assertEquals(4, context.getItemCount());
            assertEquals(2, VirtualContainerRollbackHandler.reportSkipped(rollback, "tester"));

            received.clear();
            VirtualContainerRollbackHandler.release(rollback);
            List<Object[]> restoreRows = Arrays.asList(row(5, 1, ItemTransactionActions.REMOVE, 1, STONE, 2), row(4, 1, ItemTransactionActions.ADD, 1, DIAMOND, 3));
            EntitySpawnRollbackHandler.Context restoreContext = context(1);
            VirtualContainerRollbackHandler.Work restore = VirtualContainerRollbackHandler.prepare(connection, restoreRows, 1, restoreContext);
            works.add(restore);
            VirtualContainerRollbackHandler.apply(restoreContext, restore, containers(restore).get(0));
            // a restore replays them oldest first in their logged direction
            assertEquals(Arrays.asList("a +3 DIAMOND -1", "a -2 STONE -1"), received);
        }
    }

    @Test
    void aBatchQueuesTheAppliedRowsForTheConsumer(@TempDir Path directory) throws Exception {
        register("myplugin:backpack", true, (id, changes) -> 1);
        Consumer.initialize();
        when(Bukkit.getServer().getWorlds()).thenReturn(Collections.singletonList(mock(World.class)));
        PaperInterface previousPaperAdapter = PaperAdapter.ADAPTER;
        PaperAdapter.ADAPTER = mock(PaperInterface.class);
        try (Connection connection = database(directory)) {
            List<Object[]> rows = Arrays.asList(row(4, 1, ItemTransactionActions.ADD, 0), row(3, 2, ItemTransactionActions.ADD, 0), row(2, 2, ItemTransactionActions.REMOVE, 0), row(1, 3, ItemTransactionActions.ADD, 0));
            EntitySpawnRollbackHandler.Context context = context(0);
            VirtualContainerRollbackHandler.Work work = VirtualContainerRollbackHandler.prepare(connection, rows, 0, context);
            works.add(work);
            Method applyBatch = VirtualContainerRollbackHandler.class.getDeclaredMethod("applyBatch", EntitySpawnRollbackHandler.Context.class, VirtualContainerRollbackHandler.Work.class, String.class);
            applyBatch.setAccessible(true);
            // a batch ends when its time budget runs out, the next one continues with the next container
            int batches = 0;
            while ((Integer) field(work, "index") < containers(work).size()) {
                applyBatch.invoke(null, context, work, "tester");
                batches++;
            }

            // a consumer record per batch that applied something, with the first row of each container
            List<Object[]> records = Consumer.consumer.get(Consumer.currentConsumer);
            assertTrue(records.size() >= 1 && records.size() <= batches, records.size() + " records for " + batches + " batches");
            List<Long> queued = new ArrayList<>();
            for (Object[] record : records) {
                assertEquals(Process.VIRTUAL_CONTAINER_ROLLBACK_UPDATE, record[1]);
                assertEquals(0, record[6]);
                for (Object[] row : Consumer.consumerObjectArrayList.get(Consumer.currentConsumer).get((Integer) record[0])) {
                    queued.add((Long) row[0]);
                }
            }
            assertEquals(Arrays.asList(4L, 3L), queued);
            assertEquals(2, VirtualContainerRollbackHandler.reportSkipped(work, "tester"));
        }
        finally {
            PaperAdapter.ADAPTER = previousPaperAdapter;
            Consumer.initialize();
        }
    }

    @Test
    void failingHandlersApplyNothing(@TempDir Path directory) throws Exception {
        RecordingHandler log = new RecordingHandler();
        Plugin throwing = register("myplugin:backpack", true, (id, changes) -> {
            throw new IllegalStateException("broken");
        });
        throwing.getLogger().addHandler(log);
        try (Connection connection = database(directory)) {
            List<Object[]> rows = Arrays.asList(row(2, 1, ItemTransactionActions.ADD, 0), row(1, 1, ItemTransactionActions.ADD, 0));
            assertEquals(0, applyFirst(connection, rows).size());
            assertTrue(log.messages.get(0).contains("myplugin:backpack/a"), log.messages.toString());

            for (int result : new int[] { -1, 3 }) {
                Plugin plugin = register("myplugin:backpack", true, (id, changes) -> result);
                plugin.getLogger().addHandler(log);
                assertEquals(0, applyFirst(connection, rows).size(), "returned " + result);
                assertTrue(log.messages.get(log.messages.size() - 1).contains("returned " + result), log.messages.toString());
            }

            // re-registering replaces the handler
            register("myplugin:backpack", true, (id, changes) -> changes.size());
            assertEquals(2, applyFirst(connection, rows).size());
        }
    }

    @Test
    void missingOrDisabledHandlersSkipTheContainer(@TempDir Path directory) throws Exception {
        try (Connection connection = database(directory)) {
            List<Object[]> rows = Arrays.asList(row(3, 3, ItemTransactionActions.ADD, 0), row(2, 3, ItemTransactionActions.REMOVE, 0));
            EntitySpawnRollbackHandler.Context context = context(0);
            VirtualContainerRollbackHandler.Work work = VirtualContainerRollbackHandler.prepare(connection, rows, 0, context);
            works.add(work);
            assertEquals(0, VirtualContainerRollbackHandler.apply(context, work, containers(work).get(0)).size());
            assertEquals(2, VirtualContainerRollbackHandler.reportSkipped(work, "tester"));
            assertTrue(((java.util.Set<?>) field(work, "missingTypes")).contains("other:unregistered"));

            register("myplugin:backpack", false, (id, changes) -> changes.size());
            List<Object[]> backpackRows = Collections.singletonList(row(1, 1, ItemTransactionActions.ADD, 0));
            assertEquals(0, applyFirst(connection, backpackRows).size());
        }
    }

    @Test
    void appliedPrefixIsMarkedAndARerunAppliesTheRest(@TempDir Path directory) throws Exception {
        int[] limit = { 2 };
        register("myplugin:backpack", true, (id, changes) -> Math.min(limit[0], changes.size()));
        try (Connection connection = database(directory); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO co_virtual_container (time,user,container_id,type,data,amount,metadata,action,rolled_back) VALUES "
                    + "(100,1,1,1,0,2,NULL,1,0),(200,1,1,1,0,3,NULL,0,0),(300,1,1,2,0,4,NULL,1,0),(400,1,2,1,0,5,NULL,1,0)");

            assertEquals(2, rollback(connection, 0).size());
            // the handler applied the two newest rows of backpack a; backpack b is not part of the rollback
            assertEquals(Arrays.asList(0, 1, 1, 0), states(statement));

            limit[0] = 10;
            assertEquals(1, rollback(connection, 0).size());
            assertEquals(Arrays.asList(1, 1, 1, 0), states(statement));
            assertEquals(0, rollback(connection, 0).size());

            assertEquals(3, rollback(connection, 1).size());
            assertEquals(Arrays.asList(0, 0, 0, 0), states(statement));
        }
    }

    @Test
    void handlersGetTheSlotStoredInTheRow(@TempDir Path directory) throws Exception {
        List<Integer> slots = new ArrayList<>();
        register("myplugin:backpack", true, (id, changes) -> {
            for (VirtualContainerChange change : changes) {
                slots.add(change.getSlot());
            }
            return changes.size();
        });
        try (Connection connection = database(directory); PreparedStatement insert = connection.prepareStatement("INSERT INTO co_virtual_container (time,user,container_id,type,data,amount,metadata,action,rolled_back) VALUES (?,1,1,1,0,1,?,1,0)")) {
            // the metadata ContainerLogger stores for slot 7, and a row logged before slots were recorded
            List<List<Map<String, Object>>> metadata = new ArrayList<>();
            Map<String, Object> slot = new HashMap<>();
            slot.put("slot", 7);
            metadata.add(new ArrayList<>(Collections.singletonList(slot)));
            insert.setInt(1, 100);
            insert.setBytes(2, ItemUtils.convertByteData(metadata));
            insert.executeUpdate();
            insert.setInt(1, 200);
            insert.setBytes(2, null);
            insert.executeUpdate();

            assertEquals(2, rollback(connection, 0).size());
            assertEquals(Arrays.asList(-1, 7), slots);
        }
    }

    /**
     * Runs the rollback lookup, the handler, and the rolled-back update the consumer would run for the applied rows.
     */
    private static List<Object[]> rollback(Connection connection, int rollbackType) throws Exception {
        List<Object> include = new ArrayList<>();
        include.add(new VirtualContainerTarget("myplugin:backpack", "a"));
        List<Integer> actions = new ArrayList<>(Collections.singletonList(LookupActions.CONTAINER));
        List<Object[]> rows;
        try (Statement statement = connection.createStatement()) {
            rows = RollbackLookup.rows(statement, include, actions);
        }
        EntitySpawnRollbackHandler.Context context = context(rollbackType);
        VirtualContainerRollbackHandler.Work work = VirtualContainerRollbackHandler.prepare(connection, rows, rollbackType, context);
        try {
            List<Object[]> applied = new ArrayList<>();
            for (VirtualContainerRollbackHandler.Container container : containers(work)) {
                applied.addAll(VirtualContainerRollbackHandler.apply(context, work, container));
            }
            if (!applied.isEmpty()) {
                try (RelationalConsumerWriteBatch batch = new RelationalConsumerWriteBatch(connection, DatabaseType.SQLITE)) {
                    batch.begin();
                    batch.updateRolledBackRows(RollbackUpdateTargets.VIRTUAL_CONTAINER, rollbackType == 0 ? 1 : 0, applied);
                    assertTrue(batch.commit());
                }
            }
            return applied;
        }
        finally {
            VirtualContainerRollbackHandler.release(work);
        }
    }

    private static List<Integer> states(Statement statement) throws Exception {
        List<Integer> states = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery("SELECT rolled_back FROM co_virtual_container ORDER BY rowid")) {
            while (resultSet.next()) {
                states.add(resultSet.getInt(1));
            }
        }
        return states;
    }

    private static final class RollbackLookup extends Lookup {
        private static List<Object[]> rows(Statement statement, List<Object> include, List<Integer> actions) {
            return performLookupRaw(statement, null, new ArrayList<>(), Collections.singletonList("#global"), include, new HashMap<>(), new ArrayList<>(), actions, EntityActionFilter.DEFAULT, EntityLookupContext.legacy(Collections.emptySet(), Collections.emptySet()), null, null, null, 0, 0, -1, -1, false, false, null);
        }
    }

    private List<Object[]> applyFirst(Connection connection, List<Object[]> rows) throws Exception {
        EntitySpawnRollbackHandler.Context context = context(0);
        VirtualContainerRollbackHandler.Work work = VirtualContainerRollbackHandler.prepare(connection, rows, 0, context);
        try {
            return VirtualContainerRollbackHandler.apply(context, work, containers(work).get(0));
        }
        finally {
            VirtualContainerRollbackHandler.release(work);
        }
    }

    private VirtualContainerRollbackHandler.Work prepare(Connection connection, List<Object[]> rows, int rollbackType) throws Exception {
        VirtualContainerRollbackHandler.Work work = VirtualContainerRollbackHandler.prepare(connection, rows, rollbackType, context(rollbackType));
        works.add(work);
        return work;
    }

    private static Plugin register(String type, boolean enabled, VirtualContainerHandler handler) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(enabled);
        when(plugin.getName()).thenReturn("TestPlugin");
        Logger logger = Logger.getLogger("TestPlugin." + System.nanoTime());
        logger.setUseParentHandlers(false);
        when(plugin.getLogger()).thenReturn(logger);
        VirtualContainerRollbackHandler.register(plugin, type, handler);
        return plugin;
    }

    private static Connection database(Path directory) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("database.db");
        Database.createDatabaseTables("co_", true, DriverManager.getConnection(url), DatabaseType.SQLITE, true);
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO co_virtual_container_map (id, container) VALUES (1,'myplugin:backpack/a'),(2,'myplugin:backpack/b'),(3,'other:unregistered/x')");
        }
        return connection;
    }

    private static EntitySpawnRollbackHandler.Context context(int rollbackType) {
        return EntitySpawnRollbackHandler.prepare(new ArrayList<>(), Collections.emptyMap(), new ArrayList<>(), Collections.emptyMap(), Collections.emptyMap(), new ArrayList<>(), rollbackType, false, 0, "tester", null, null);
    }

    private static Object[] row(long rowId, int containerId, int action, int rolledBack) {
        return row(rowId, containerId, action, rolledBack, DIAMOND, 1);
    }

    // { id, time, user, x, y, z, type, data, action, rolled_back, wid, amount, meta, blockdata, tbl, entity_spawn_rowid }
    private static Object[] row(long rowId, int containerId, int action, int rolledBack, int type, int amount) {
        return new Object[] { rowId, (int) rowId, 1, 0, 0, 0, type, 0, action, rolledBack, 0, amount, null, null, InventorySources.VIRTUAL_CONTAINER, containerId };
    }

    @SuppressWarnings("unchecked")
    private static List<VirtualContainerRollbackHandler.Container> containers(VirtualContainerRollbackHandler.Work work) throws Exception {
        return (List<VirtualContainerRollbackHandler.Container>) field(work, "containers");
    }

    private static List<String> keys(VirtualContainerRollbackHandler.Work work) throws Exception {
        List<String> keys = new ArrayList<>();
        for (Object container : containers(work)) {
            keys.add((String) field(container, "key"));
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> rowIds(Object container) throws Exception {
        List<Long> rowIds = new ArrayList<>();
        for (Object[] row : (List<Object[]>) field(container, "rows")) {
            rowIds.add((Long) row[0]);
        }
        return rowIds;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class RecordingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
