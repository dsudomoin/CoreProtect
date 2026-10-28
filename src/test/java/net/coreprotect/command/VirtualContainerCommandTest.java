package net.coreprotect.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.coreprotect.command.parser.VirtualContainerParser;
import net.coreprotect.language.Language;
import net.coreprotect.model.lookup.VirtualContainerTarget;

class VirtualContainerCommandTest {

    @BeforeAll
    static void phrases() {
        Language.loadPhrases();
    }

    @Test
    void parsesTypeAndKey() {
        VirtualContainerTarget type = VirtualContainerParser.parse(new String[] { "lookup", "container:MyPlugin:Backpack", "t:1h" }).getTarget();
        assertEquals("myplugin:backpack", type.getType());
        assertNull(type.getId());

        VirtualContainerTarget key = VirtualContainerParser.parse(new String[] { "lookup", "u:Notch", "container:funclan:clan_storage/42" }).getTarget();
        assertEquals("funclan:clan_storage", key.getType());
        assertEquals("42", key.getId());

        VirtualContainerTarget spaced = VirtualContainerParser.parse(new String[] { "lookup", "container:", "funclan:clan_storage/42" }).getTarget();
        assertEquals("42", spaced.getId());

        assertFalse(VirtualContainerParser.parse(new String[] { "lookup", "u:Notch", "t:1h" }).isSpecified());
    }

    @Test
    void rejectsInvalidTargets() {
        String[][] invalid = { { "lookup", "container:" }, { "lookup", "container:backpack" }, { "lookup", "container:myplugin:backpack/" }, { "lookup", "container:myplugin:a/b/c" },
                { "lookup", "container:myplugin:backpack/a b" }, { "lookup", "container:a:b/1", "container:a:b/2" }, { "lookup", "container:myplugin:backpack/" + repeat(65) } };
        for (String[] arguments : invalid) {
            VirtualContainerParser.ParseResult result = VirtualContainerParser.parse(arguments);
            assertTrue(result.isSpecified(), String.join(" ", arguments));
            assertNull(result.getTarget(), String.join(" ", arguments));
        }
        assertTrue(VirtualContainerParser.isParameter(" Container:x"));
        assertFalse(VirtualContainerParser.isParameter("#container"));
        assertFalse(VirtualContainerParser.isParameter("c:1,2,3"));
    }

    @Test
    void lookupRejectsConflicts() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("player");
        when(player.getLocation()).thenReturn(new Location(mock(World.class), 10, 64, 10));
        LookupCommand.runCommand(player, mock(Command.class), true, new String[] { "l", "container:myplugin:backpack/1", "t:1h", "r:10" });
        assertTrue(message(player).contains("\"r:\" is not a supported parameter"), message(player));
        assertTrue(lookup(false, "l", "container:myplugin:backpack/1", "t:1h", "u:#container").contains("\"#container\" is not a supported parameter"));
        assertTrue(lookup(false, "l", "container:myplugin:backpack/1", "t:1h", "a:block").contains("\"container:\" can't be used with that action"));
        assertTrue(lookup(false, "l", "container:myplugin:backpack/1", "t:1h", "a:inventory").contains("\"container:\" can't be used with that action"));
        assertTrue(lookup(false, "l", "container:myplugin:backpack/1", "t:1h", "a:container,click").contains("\"container:\" can't be used with that action"));
        assertTrue(lookup(false, "l", "container:BadType", "t:1h").contains("/co l container:<namespace:type>[/<id>]"));
    }

    @Test
    void lookupAcceptsContainerActions() {
        // with every permission the lookup passes validation and stops at the missing time
        assertTrue(lookup(true, "l", "container:myplugin:backpack/1").contains("Please specify the amount of time to lookup"));
        assertTrue(lookup(true, "l", "container:", "myplugin:backpack", "a:-container").contains("Please specify the amount of time to lookup"));
        assertTrue(lookup(true, "l", "container:myplugin:backpack", "a:+container", "r:#global").contains("Please specify the amount of time to lookup"));
    }

    @Test
    void rollbackAndRestoreRejectContainer() {
        for (String command : new String[] { "rollback", "restore" }) {
            CommandSender sender = sender(true);
            RollbackRestoreCommand.runCommand(sender, mock(Command.class), true, new String[] { command, "u:Notch", "t:1h", "container:myplugin:backpack/1" }, null, 0, 0);
            assertTrue(message(sender).contains("\"container:myplugin:backpack/1\" is not a supported parameter"), command);

            sender = sender(true);
            RollbackRestoreCommand.runCommand(sender, mock(Command.class), true, new String[] { command, "u:Notch", "t:1h", "container:", "myplugin:backpack" }, null, 0, 0);
            assertTrue(message(sender).contains("\"container:\" is not a supported parameter"), command);
        }
    }

    private static String lookup(boolean permissions, String... arguments) {
        CommandSender sender = sender(permissions);
        LookupCommand.runCommand(sender, mock(Command.class), true, arguments);
        return message(sender);
    }

    private static CommandSender sender(boolean permissions) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("tester");
        when(sender.hasPermission(anyString())).thenReturn(permissions);
        return sender;
    }

    private static String message(CommandSender sender) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessage(captor.capture());
        return captor.getValue();
    }

    private static String repeat(int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) {
            builder.append('a');
        }
        return builder.toString();
    }
}
