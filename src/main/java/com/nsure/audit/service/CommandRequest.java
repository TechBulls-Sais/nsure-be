package com.nsure.audit.service;

import com.nsure.security.domain.PermissionCode;

public record CommandRequest(
        String actionName,
        String entityName,
        Long resourceId,
        String json,
        String idempotencyKey,
        boolean idempotencyRetry) {

    public String permissionCode() {
        return PermissionCode.code(actionName, entityName);
    }
}
