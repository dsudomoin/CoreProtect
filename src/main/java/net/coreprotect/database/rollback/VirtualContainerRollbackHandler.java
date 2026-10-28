package net.coreprotect.database.rollback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.plugin.Plugin;

import net.coreprotect.api.VirtualContainerHandler;

public final class VirtualContainerRollbackHandler {

    private static final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    private VirtualContainerRollbackHandler() {
        throw new IllegalStateException("Database class");
    }

    public static void register(Plugin plugin, String type, VirtualContainerHandler handler) {
        registrations.put(type, new Registration(plugin, handler));
    }

    private static final class Registration {
        private final Plugin plugin;
        private final VirtualContainerHandler handler;

        private Registration(Plugin plugin, VirtualContainerHandler handler) {
            this.plugin = plugin;
            this.handler = handler;
        }
    }
}
