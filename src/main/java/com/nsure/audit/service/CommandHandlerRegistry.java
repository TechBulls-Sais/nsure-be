package com.nsure.audit.service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CommandHandlerRegistry {

    private final Map<String, CommandHandler> handlers;

    public CommandHandlerRegistry(List<CommandHandler> handlers) {
        Map<String, CommandHandler> mapped = new HashMap<>();
        for (CommandHandler handler : handlers) {
            for (String action : handler.actions()) {
                mapped.put(key(handler.entityName(), action), handler);
            }
        }
        this.handlers = Map.copyOf(mapped);
    }

    public CommandHandler get(String entityName, String actionName) {
        CommandHandler handler = handlers.get(key(entityName, actionName));
        if (handler == null) {
            throw new IllegalArgumentException("No handler for " + actionName + " " + entityName);
        }
        return handler;
    }

    private static String key(String entityName, String actionName) {
        return entityName.toUpperCase(Locale.ROOT) + ":" + actionName.toUpperCase(Locale.ROOT);
    }
}
