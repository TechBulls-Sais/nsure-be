package com.nsure.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

public record AuditResponse(
        Long id,
        String actionName,
        String entityName,
        Long resourceId,
        Long officeId,
        Long makerId,
        OffsetDateTime madeOn,
        Long checkerId,
        OffsetDateTime checkedOn,
        String status,
        Integer resultStatus,
        String idempotencyKey,
        JsonNode command,
        JsonNode result) {
}
