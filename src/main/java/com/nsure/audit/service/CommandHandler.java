package com.nsure.audit.service;

import java.util.Set;

public interface CommandHandler {

    String entityName();

    Set<String> actions();

    CommandResult handle(CommandRequest request);
}
