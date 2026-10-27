package com.nsure.audit.api;

import java.util.List;

public record AuditPage(List<AuditResponse> items, long total, int page, int size) {
}
