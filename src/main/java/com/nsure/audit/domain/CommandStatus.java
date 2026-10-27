package com.nsure.audit.domain;

public final class CommandStatus {

    public static final String UNDER_PROCESSING = "UNDER_PROCESSING";
    public static final String PROCESSED = "PROCESSED";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String REJECTED = "REJECTED";
    public static final String ERROR = "ERROR";

    private CommandStatus() {
    }
}
