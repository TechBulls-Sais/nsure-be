package com.nsure.integration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class IntegrationViews {

    private IntegrationViews() {
    }

    /** interfaceCode/owner come from the interface catalogue: who works this consumer's business exceptions. */
    public record DeliveryView(Long id, String handler, String status, int attempts, String lastError,
            LocalDateTime nextAttemptAt, LocalDateTime deliveredAt, String failureClass, LocalDateTime deadLetteredAt,
            String interfaceCode, String owner, Long latencySeconds) {
    }

    /** One dead-lettered delivery with its event, owner and reason (NS-SH12-003). */
    public record DeadLetterView(Long deliveryId, String eventId, String eventType, String aggregateRef,
            String correlationId, LocalDateTime occurredAt, String handler, String interfaceCode, String interfaceName,
            String owner, String failureClass, int attempts, String reason, LocalDateTime deadLetteredAt) {
    }

    public record EventView(String eventId, String eventType, String aggregateRef, String correlationId,
            String publishedBy, LocalDateTime occurredAt, String payload, String status,
            List<DeliveryView> deliveries, int schemaVersion, Long aggregateVersion) {
    }

    public record InboundView(Long id, String provider, String providerRef, String messageType, String businessRef,
            BigDecimal amount, String status, String resultRef, String detail, String receivedBy,
            LocalDateTime receivedAt, String channel, int statusQueries, LocalDateTime statusQueriedAt) {
    }

    public record ProviderReceiptView(Long id, String provider, String providerRef, BigDecimal amount,
            String currency, String recordedBy, LocalDateTime recordedAt) {
    }

    public record DashboardView(long events, Map<String, Long> deliveriesByStatus, long failed,
            LocalDateTime oldestPending, Map<String, Long> inboundByStatus, LocalDateTime asOf,
            Long averageLatencySeconds, long retrying, List<String> interfacesDown) {
    }

    public record DispatchView(int delivered, int failed, DashboardView dashboard) {
    }
}
