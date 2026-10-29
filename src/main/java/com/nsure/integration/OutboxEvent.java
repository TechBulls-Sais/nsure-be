package com.nsure.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** A business event committed with the change that caused it (transactional outbox, NS-ARC-003). Never updated. */
@Entity
@Table(name = "int_outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "aggregate_ref", nullable = false, length = 60)
    private String aggregateRef;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Column(name = "correlation_id", nullable = false, length = 36)
    private String correlationId;

    @Column(name = "published_by", nullable = false, length = 100)
    private String publishedBy;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected OutboxEvent() {
    }

    OutboxEvent(String eventType, String aggregateRef, String payload, String correlationId, String publishedBy) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.aggregateRef = aggregateRef;
        this.payload = payload;
        this.correlationId = correlationId == null ? eventId : correlationId;
        this.publishedBy = publishedBy;
        this.occurredAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateRef() {
        return aggregateRef;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
