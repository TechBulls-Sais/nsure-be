package com.nsure.integration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class IntegrationViews {

    private IntegrationViews() {
    }

    public record DeliveryView(Long id, String handler, String status, int attempts, String lastError,
            LocalDateTime nextAttemptAt, LocalDateTime deliveredAt) {
    }

    public record EventView(String eventId, String eventType, String aggregateRef, String correlationId,
            String publishedBy, LocalDateTime occurredAt, String payload, String status,
            List<DeliveryView> deliveries) {
    }

    public record InboundView(Long id, String provider, String providerRef, String messageType, String businessRef,
            BigDecimal amount, String status, String resultRef, String detail, String receivedBy,
            LocalDateTime receivedAt) {
    }

    public record DashboardView(long events, Map<String, Long> deliveriesByStatus, long failed,
            LocalDateTime oldestPending, Map<String, Long> inboundByStatus, LocalDateTime asOf) {
    }

    public record DispatchView(int delivered, int failed, DashboardView dashboard) {
    }
}
