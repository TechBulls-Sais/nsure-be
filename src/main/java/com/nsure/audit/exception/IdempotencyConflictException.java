package com.nsure.audit.exception;

public class IdempotencyConflictException extends RuntimeException {

    private final Long commandId;
    private final String commandStatus;
    private final String resultJson;

    public IdempotencyConflictException(Long commandId, String commandStatus, String message, String resultJson) {
        super(message);
        this.commandId = commandId;
        this.commandStatus = commandStatus;
        this.resultJson = resultJson;
    }

    public Long getCommandId() {
        return commandId;
    }

    public String getCommandStatus() {
        return commandStatus;
    }

    public String getResultJson() {
        return resultJson;
    }
}
