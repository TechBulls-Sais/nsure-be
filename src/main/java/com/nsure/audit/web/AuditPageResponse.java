package com.nsure.audit.web;

import java.util.List;

public record AuditPageResponse(
        List<AuditEntryResponse> content,
        long totalElements,
        int page,
        int size
) {
}
