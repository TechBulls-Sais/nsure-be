package com.nsure.audit;

public class AwaitingApprovalException extends RuntimeException {

    private final Long commandId;

    public AwaitingApprovalException(Long commandId) {
        super("Command " + commandId + " is awaiting approval");
        this.commandId = commandId;
    }

    public Long getCommandId() {
        return commandId;
    }
}
