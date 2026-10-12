package com.nsure.security.web;

public record PermissionResponse(
        Long id,
        String grouping,
        String code,
        String entityName,
        String actionName,
        boolean canMakerChecker
) {
}
