package com.nsure.audit.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record AuditEntryResponse(
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
        JsonNode command,
        JsonNode result,
        Integer resultStatus
) {
}
