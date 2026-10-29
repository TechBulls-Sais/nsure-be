package com.nsure.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One handler's delivery of one outbox event: Pending → Delivered, or Failed after bounded retries. */
@Entity
@Table(name = "int_event_delivery")
public class EventDelivery {

    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outbox_event_id", nullable = false)
    private OutboxEvent event;

    @Column(name = "handler_name", nullable = false, length = 60)
    private String handler;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected EventDelivery() {
    }

    EventDelivery(OutboxEvent event, String handler) {
        this.event = event;
        this.handler = handler;
        this.status = PENDING;
        this.nextAttemptAt = LocalDateTime.now();
        this.updatedAt = nextAttemptAt;
    }

    void delivered() {
        attempts++;
        status = DELIVERED;
        lastError = null;
        deliveredAt = LocalDateTime.now();
        updatedAt = deliveredAt;
    }

    /** A failed attempt: retried with backoff until maxAttempts, then left Failed for an operator to replay. */
    void failed(String error, int maxAttempts) {
        attempts++;
        lastError = error == null ? "Handler failed" : error.length() <= 1000 ? error : error.substring(0, 1000);
        updatedAt = LocalDateTime.now();
        if (attempts >= maxAttempts) {
            status = FAILED;
        } else {
            nextAttemptAt = updatedAt.plusSeconds(5L << Math.min(attempts, 6));
        }
    }

    void replay() {
        status = PENDING;
        attempts = 0;
        nextAttemptAt = LocalDateTime.now();
        updatedAt = nextAttemptAt;
    }

    public Long getId() {
        return id;
    }

    public OutboxEvent getEvent() {
        return event;
    }

    public String getHandler() {
        return handler;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
