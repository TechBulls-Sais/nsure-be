package com.nsure.security.api;

public record PermissionResponse(
        Long id,
        String grouping,
        String code,
        String entityName,
        String actionName,
        String functionName,
        boolean canMakerChecker) {
}
