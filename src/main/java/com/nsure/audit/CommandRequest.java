package com.nsure.audit;

import com.nsure.security.domain.PermissionCodes;
import java.util.Locale;
import java.util.regex.Pattern;

public record CommandRequest(
        String actionName,
        String entityName,
        Long resourceId,
        String json,
        String idempotencyKey
) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    public CommandRequest {
        if (actionName == null || entityName == null) {
            throw new IllegalArgumentException("action and entity are required");
        }
        actionName = actionName.trim().toUpperCase(Locale.ROOT);
        entityName = entityName.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(actionName).matches() || !CODE.matcher(entityName).matches()) {
            throw new IllegalArgumentException("action and entity must be codes such as CREATE and CLIENT");
        }
        if (json == null || json.isBlank()) {
            json = "{}";
        }
        if (idempotencyKey != null) {
            idempotencyKey = idempotencyKey.trim();
            if (idempotencyKey.isEmpty()) {
                idempotencyKey = null;
            } else if (idempotencyKey.length() > 64) {
                throw new IllegalArgumentException("Idempotency-Key must be at most 64 characters");
            }
        }
    }

    public String permissionCode() {
        return PermissionCodes.code(actionName, entityName);
    }
}
