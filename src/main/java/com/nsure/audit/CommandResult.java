package com.nsure.audit;

public record CommandResult(Long resourceId, String json) {

    public CommandResult {
        if (json == null || json.isBlank()) {
            json = "{}";
        }
    }
}
