package com.nsure.audit.service;

import java.util.Set;

public interface CommandHandler {

    String entityName();

    Set<String> actions();

    CommandResult handle(CommandRequest request);

    /**
     * Whether this particular request, made under a maker-checker code, waits for a checker. Most requests do; a
     * handler may let a harmless one through (for example a login created with no access at all).
     */
    default boolean needsChecker(CommandRequest request) {
        return true;
    }

    /**
     * The body to run and store for a client request. A request that waits for a checker is stored until it is
     * approved, so it must not carry a plain secret: the handler replaces it here (a password becomes its hash) or
     * the request is refused. Approvals run the stored body and do not come through here.
     */
    default String prepare(CommandRequest request, boolean awaitingChecker) {
        return request.json();
    }
}
