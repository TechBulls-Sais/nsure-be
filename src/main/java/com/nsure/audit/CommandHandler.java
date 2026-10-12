package com.nsure.audit;

import java.util.Set;

public interface CommandHandler {

    String entityName();

    Set<String> actionNames();

    CommandResult handle(CommandRequest request);
}
