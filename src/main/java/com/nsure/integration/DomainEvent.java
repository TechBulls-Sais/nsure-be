package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An outbox event as a handler sees it (spec §10 NS-API-003): references and the minimum data, never documents or
 * secrets. {@code eventId} is the idempotency key a handler stores with whatever it creates.
 */
public record DomainEvent(
        String eventId,
        String eventType,
        String aggregateRef,
        String correlationId,
        LocalDateTime occurredAt,
        JsonNode data) {

    public String text(String field) {
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public BigDecimal amount(String field) {
        String value = text(field);
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    public boolean flag(String field) {
        JsonNode value = data.get(field);
        return value != null && value.asBoolean();
    }
}
