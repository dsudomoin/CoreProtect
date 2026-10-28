package net.coreprotect.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class VirtualContainerPurgeTest {

    @Test
    void timePurgeDeletesAndScopedPurgesKeep() {
        assertTrue(PurgePolicy.isPurgeable("virtual_container"));
        assertFalse(PurgePolicy.isWorldScoped("virtual_container"));
        assertFalse(PurgePolicy.isPurgeable("virtual_container_map"));

        assertEquals("time < 200 AND time >= 100", filter(0, null).deleteCondition("virtual_container", "co_"));
        assertNull(filter(3, null).deleteCondition("virtual_container", "co_"));
        assertNull(filter(0, new Integer[] { 5, -5, 5, null, null, -5, 5 }).deleteCondition("virtual_container", "co_"));
        assertNull(new PurgeFilter(100, 200, 0, null, Collections.singletonList(1), Collections.emptyList(), Collections.emptyList(), false).deleteCondition("virtual_container", "co_"));
        assertNull(filter(0, null).deleteCondition("virtual_container_map", "co_"));
    }

    private static PurgeFilter filter(int worldId, Integer[] radius) {
        return new PurgeFilter(100, 200, worldId, radius, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false);
    }
}
