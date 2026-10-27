package com.nsure.audit.service;

public record CommandOutcome(
        Long commandId,
        Long resourceId,
        String status,
        String resultJson,
        boolean replayed) {
}
