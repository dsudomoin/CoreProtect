package net.coreprotect.database.logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.coreprotect.bukkit.BukkitAdapter;
import net.coreprotect.bukkit.BukkitInterface;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.ConsumerWriteBatch;
import net.coreprotect.database.DatabaseType;
import net.coreprotect.database.statement.BlockStatement;
import net.coreprotect.model.item.ItemTransactionActions;
import net.coreprotect.model.item.VirtualContainerTransaction;

/**
 * Rows ContainerLogger.logVirtual writes: the net change per item, split over the slots it happened in.
 */
class VirtualContainerLoggerTest {

    private final DatabaseType previousType = ConfigHandler.databaseType;
    private final BukkitInterface previousAdapter = BukkitAdapter.ADAPTER;
    private final List<String> rows = new ArrayList<>();
    private ConsumerWriteBatch batch;

    @BeforeAll
    static void server() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class);
            when(server.getLogger()).thenReturn(Logger.getLogger("VirtualContainerLoggerTest"));
            Bukkit.setServer(server);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ConfigHandler.databaseType = DatabaseType.SQLITE;
        ConfigHandler.materials.put("minecraft:diamond", 1);
        ConfigHandler.materials.put("minecraft:stone", 2);
        BukkitAdapter.ADAPTER = mock(BukkitInterface.class);
        batch = mock(ConsumerWriteBatch.class);
        // "+3 diamond @4" = 3 diamonds added in slot 4, "@-1" = no slot stored
        doAnswer(invocation -> {
            int type = invocation.getArgument(4);
            int amount = invocation.getArgument(6);
            int action = invocation.getArgument(8);
            rows.add((action == ItemTransactionActions.ADD ? "+" : "-") + amount + " " + (type == 1 ? "diamond" : "stone") + " @" + slot(invocation.getArgument(7)));
            return null;
        }).when(batch).addVirtualContainer(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt());
    }

    @AfterEach
    void tearDown() {
        BukkitAdapter.ADAPTER = previousAdapter;
        ConfigHandler.databaseType = previousType;
    }

    @Test
    void fullSnapshotsRecordTheChangedSlots() throws Exception {
        log(stacks(diamond(10), null, stone(5)), stacks(diamond(10), diamond(3), null));
        assertEquals(Arrays.asList("-5 stone @2", "+3 diamond @1"), rows);
    }

    @Test
    void movingAStackWithinTheContainerLogsNothing() throws Exception {
        log(stacks(diamond(10), null), stacks(null, diamond(10)));
        assertEquals(Collections.emptyList(), rows);
    }

    @Test
    void partialTakeFromAStack() throws Exception {
        log(stacks(stone(1), diamond(10)), stacks(stone(1), diamond(4)));
        assertEquals(Collections.singletonList("-6 diamond @1"), rows);
    }

    @Test
    void anItemSpreadOverTwoSlotsIsSplitIntoTwoRows() throws Exception {
        log(stacks(diamond(64), stone(1), diamond(20)), stacks(null, stone(1), diamond(4)));
        assertEquals(Arrays.asList("-64 diamond @0", "-16 diamond @2"), rows);

        rows.clear();
        // a move and a take: the 6 taken diamonds come out of the slot that lost them
        log(stacks(diamond(10), null), stacks(diamond(1), diamond(3)));
        assertEquals(Collections.singletonList("-6 diamond @0"), rows);
    }

    @Test
    void emptySnapshotsAreIndexAligned() throws Exception {
        log(null, stacks(null, stone(2), diamond(1)));
        assertEquals(Arrays.asList("+2 stone @1", "+1 diamond @2"), rows);

        rows.clear();
        log(stacks(diamond(7)), null);
        assertEquals(Collections.singletonList("-7 diamond @0"), rows);
    }

    @Test
    void changedStacksOfDifferentLengthsHaveNoSlot() throws Exception {
        // the net change is the same as before slots were recorded
        log(stacks(diamond(5), stone(2)), stacks(diamond(8)));
        assertEquals(Arrays.asList("-2 stone @-1", "+3 diamond @-1"), rows);
    }

    private void log(ItemStack[] before, ItemStack[] after) throws Exception {
        ContainerLogger.logVirtual(batch, 0, "Notch", new VirtualContainerTransaction("myplugin:backpack/a", before, after));
    }

    private static int slot(byte[] metadata) {
        List<Object> entries = BlockStatement.deserializeMetadata(metadata);
        if (entries != null) {
            for (Object entry : entries) {
                Object slot = ((Map<?, ?>) ((List<?>) entry).get(0)).get("slot");
                if (slot != null) {
                    return (Integer) slot;
                }
            }
        }
        return -1;
    }

    private static ItemStack[] stacks(ItemStack... stacks) {
        return stacks;
    }

    private static ItemStack diamond(int amount) {
        return new FakeStack(Material.DIAMOND, amount);
    }

    private static ItemStack stone(int amount) {
        return new FakeStack(Material.STONE, amount);
    }

    /**
     * paper-api item stacks need a running server, this one only keeps a type and an amount.
     */
    private static final class FakeStack extends ItemStack {
        private final Material type;
        private int amount;

        private FakeStack(Material type, int amount) {
            this.type = type;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public boolean isSimilar(ItemStack stack) {
            return stack != null && stack.getType() == type;
        }

        @Override
        public boolean hasItemMeta() {
            return false;
        }

        @Override
        public ItemStack clone() {
            return new FakeStack(type, amount);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof FakeStack && ((FakeStack) object).type == type && ((FakeStack) object).amount == amount;
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + amount;
        }
    }
}
