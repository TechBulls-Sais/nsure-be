package com.nsure.audit.api;

import com.fasterxml.jackson.databind.JsonNode;

public record CommandResponse(Long commandId, Long resourceId, String status, JsonNode result) {
}
