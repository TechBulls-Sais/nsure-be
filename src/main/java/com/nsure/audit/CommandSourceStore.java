package com.nsure.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CommandSourceStore {

    private final CommandSourceRepository commands;

    public CommandSourceStore(CommandSourceRepository commands) {
        this.commands = commands;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandSource insert(CommandSource audit) {
        return commands.saveAndFlush(audit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAwaiting(Long id) {
        CommandSource audit = load(id);
        audit.markAwaitingApproval();
        commands.saveAndFlush(audit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markError(Long id, int httpStatus, String message) {
        CommandSource audit = load(id);
        audit.markError(httpStatus, message);
        commands.saveAndFlush(audit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepareRetry(Long id, String sanitizedJson) {
        CommandSource audit = load(id);
        audit.resetForRetry();
        audit.setCommandAsJson(sanitizedJson);
        commands.saveAndFlush(audit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markProcessed(Long id, Long resourceId, String resultJson) {
        CommandSource audit = load(id);
        audit.markProcessed(resourceId, resultJson);
        commands.saveAndFlush(audit);
    }

    private CommandSource load(Long id) {
        return commands.findById(id).orElseThrow(() -> new IllegalStateException("Command " + id + " was not found"));
    }
}
