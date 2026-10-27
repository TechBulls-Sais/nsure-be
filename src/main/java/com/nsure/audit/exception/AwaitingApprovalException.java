package com.nsure.audit.exception;

public class AwaitingApprovalException extends RuntimeException {

    private final Long commandId;

    public AwaitingApprovalException(Long commandId) {
        super("Awaiting approval");
        this.commandId = commandId;
    }

    public Long getCommandId() {
        return commandId;
    }
}
