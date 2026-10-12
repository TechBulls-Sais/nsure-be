package com.nsure.audit;

public record ExecutedCommand(int httpStatus, String json, Long commandId, Long resourceId) {

    public ExecutedCommand {
        if (json == null || json.isBlank()) {
            json = "{}";
        }
    }
}
