package net.coreprotect.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.coreprotect.command.parser.VirtualContainerParser;
import net.coreprotect.language.Language;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.coreprotect.model.lookup.VirtualContainerTarget;

class VirtualContainerCommandTest {

    @BeforeAll
    static void phrases() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class);
            when(server.getLogger()).thenReturn(Logger.getLogger("VirtualContainerCommandTest"));
            Bukkit.setServer(server);
        }
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
    void rollbackAndRestoreAcceptContainer() {
        for (String command : new String[] { "rollback", "restore" }) {
            String action = command.equals("rollback") ? "rollback" : "restore";
            // with a user the command passes validation and stops at the missing time
            assertTrue(rollback(command, "u:Notch", "container:myplugin:backpack/1").contains("Please specify the amount of time to " + action), command);
            assertTrue(rollback(command, "u:Notch", "container:", "myplugin:backpack", "a:-container", "r:#global").contains("Please specify the amount of time to " + action), command);
            // a single container needs no user
            assertTrue(rollback(command, "container:myplugin:backpack/1").contains("Please specify the amount of time to " + action), command);
            assertTrue(rollback(command, "container:myplugin:backpack/1", "a:+container").contains("Please specify the amount of time to " + action), command);
            // every container of a type is a global rollback, so it needs a user like a:container r:#global
            assertTrue(rollback(command, "t:1h", "container:myplugin:backpack").contains("You did not specify a " + action + " user"), command);
            assertTrue(rollback(command, "container:myplugin:backpack").contains("You did not specify a " + action + " user"), command);
        }
    }

    @Test
    void rollbackAndRestoreRejectContainerConflicts() {
        for (String command : new String[] { "rollback", "restore" }) {
            assertTrue(rollback(command, "u:Notch", "t:1h", "container:myplugin:backpack/1", "r:10").contains("\"r:\" is not a supported parameter"), command);
            assertTrue(rollback(command, "u:Notch", "t:1h", "container:myplugin:backpack/1", "r:#worldedit").contains("\"r:\" is not a supported parameter"), command);
            assertTrue(rollback(command, "u:#container", "t:1h", "container:myplugin:backpack/1").contains("\"#container\" is not a supported parameter"), command);
            assertTrue(rollback(command, "u:Notch", "t:1h", "container:myplugin:backpack/1", "a:block").contains("\"container:\" can't be used with that action"), command);
            assertTrue(rollback(command, "u:Notch", "t:1h", "container:myplugin:backpack/1", "a:inventory").contains("\"container:\" can't be used with that action"), command);
            assertTrue(rollback(command, "u:Notch", "t:1h", "container:myplugin:backpack/1", "#preview").contains("You can't preview container transactions"), command);
            assertTrue(rollback(command, "u:Notch", "t:1h", "container:myplugin:backpack/1", "#count").contains("\"#count\" is not a supported parameter"), command);
            assertTrue(rollback(command, "u:Notch", "t:1h", "container:BadType").contains("/co " + command + " container:<namespace:type>[/<id>]"), command);
        }
    }

    @Test
    void skippedChangesPhrase() throws Exception {
        assertEquals("Skipped 1 virtual container change. See the console for details.", Phrase.build(Phrase.ROLLBACK_SKIPPED, "1", Selector.FIRST));
        assertEquals("Skipped 3 virtual container changes. See the console for details.", Phrase.build(Phrase.ROLLBACK_SKIPPED, "3", Selector.SECOND));
        String english = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("lang/en.yml")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(english.contains("ROLLBACK_SKIPPED: \"Skipped {0} virtual container {change|changes}. See the console for details.\""));
    }

    private static String rollback(String... arguments) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("tester");
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(mock(World.class), 10, 64, 10));
        // r:#worldedit looks for the WorldEdit plugin
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(player.getServer()).thenReturn(server);
        RollbackRestoreCommand.runCommand(player, mock(Command.class), true, arguments, null, 0, 0);
        return message(player);
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
