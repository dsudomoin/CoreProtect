package net.coreprotect.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class VirtualContainerUtilsTest {

    @Test
    void buildsKeys() {
        assertEquals("funclan:clan_storage/42", VirtualContainerUtils.getKey("funclan:clan_storage", "42"));
        assertEquals("myplugin:ender_chest/0b1c2d3e-0000-4000-8000-000000000001", VirtualContainerUtils.getKey("myplugin:ender_chest", "0b1c2d3e-0000-4000-8000-000000000001"));
        assertEquals("a.b-c_d:e.f-g_h/i.j-k_l", VirtualContainerUtils.getKey("a.b-c_d:e.f-g_h", "i.j-k_l"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "ABC", "a/b", "a b", "a:b", "a%", "a'", "é" })
    void rejectsIds(String id) {
        assertNull(VirtualContainerUtils.getKey("myplugin:backpack", id));
    }

    @Test
    void idIsAtMostSixtyFourCharacters() {
        assertEquals("p:t/" + repeat('a', 64), VirtualContainerUtils.getKey("p:t", repeat('a', 64)));
        assertNull(VirtualContainerUtils.getKey("p:t", repeat('a', 65)));
        assertNull(VirtualContainerUtils.getKey("p:t", null));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "backpack", ":backpack", "myplugin:", "MyPlugin:backpack", "myplugin:back/pack", "my:plugin:backpack", "myplugin:back pack", "myplugin:backpack'" })
    void rejectsTypes(String type) {
        assertFalse(VirtualContainerUtils.isValidType(type));
        assertNull(VirtualContainerUtils.getKey(type, "1"));
    }

    @Test
    void keyIsAtMostOneHundredNinetyOneCharacters() {
        String type = "p:" + repeat('t', 124);
        String id = repeat('i', 64);
        assertEquals(191, VirtualContainerUtils.getKey(type, id).length());
        assertNull(VirtualContainerUtils.getKey(type + "t", id));

        String longestType = "p:" + repeat('t', 187);
        assertTrue(VirtualContainerUtils.isValidType(longestType));
        assertEquals(191, VirtualContainerUtils.getKey(longestType, "i").length());
        assertFalse(VirtualContainerUtils.isValidType(longestType + "t"));
        assertFalse(VirtualContainerUtils.isValidType(null));
    }

    private static String repeat(char character, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(character);
        }
        return builder.toString();
    }
}
