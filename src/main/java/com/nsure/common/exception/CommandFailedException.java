package com.nsure.common.exception;

public class CommandFailedException extends RuntimeException {

    private final int status;
    private final Long commandId;

    public CommandFailedException(int status, Long commandId, String message) {
        super(message);
        this.status = status;
        this.commandId = commandId;
    }

    public int getStatus() {
        return status;
    }

    public Long getCommandId() {
        return commandId;
    }
}
