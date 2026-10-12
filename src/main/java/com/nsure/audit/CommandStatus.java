package com.nsure.audit;

import java.util.Set;

public final class CommandStatus {

    public static final String UNDER_PROCESSING = "UNDER_PROCESSING";
    public static final String PROCESSED = "PROCESSED";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String REJECTED = "REJECTED";
    public static final String ERROR = "ERROR";

    private static final Set<String> ALL = Set.of(
            UNDER_PROCESSING, PROCESSED, AWAITING_APPROVAL, REJECTED, ERROR);

    private CommandStatus() {
    }

    public static boolean isKnown(String status) {
        return status != null && ALL.contains(status);
    }
}
