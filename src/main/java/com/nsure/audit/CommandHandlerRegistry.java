package com.nsure.audit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CommandHandlerRegistry {

    private final Map<String, CommandHandler> handlers;

    public CommandHandlerRegistry(List<CommandHandler> discovered) {
        Map<String, CommandHandler> mapped = new HashMap<>();
        for (CommandHandler handler : discovered) {
            for (String action : handler.actionNames()) {
                String key = handler.entityName() + ":" + action;
                if (mapped.put(key, handler) != null) {
                    throw new IllegalStateException("Duplicate command handler for " + key);
                }
            }
        }
        this.handlers = Map.copyOf(mapped);
    }

    public CommandHandler get(String entityName, String actionName) {
        CommandHandler handler = handlers.get(entityName + ":" + actionName);
        if (handler == null) {
            throw new IllegalArgumentException("No handler for " + actionName + " " + entityName);
        }
        return handler;
    }
}
