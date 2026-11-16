package com.nsure.security.api;

public record PermissionResponse(
        Long id,
        String grouping,
        String code,
        String entityName,
        String actionName,
        String functionName,
        boolean canMakerChecker,
        // The spec 04 capability the code realises (claim.approve, rate.publish …), or null.
        String capability) {
}
