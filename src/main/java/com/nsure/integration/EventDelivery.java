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
import java.time.temporal.ChronoUnit;

/** One handler's delivery of one outbox event: Pending → Delivered, or Failed after bounded retries. */
@Entity
@Table(name = "int_event_delivery")
public class EventDelivery {

    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";
    /** Not handled because the handler judged it irrelevant (superseded); the reason stays as evidence. */
    public static final String IGNORED = "IGNORED";
    public static final String TRANSIENT = "TRANSIENT";
    public static final String PERMANENT = "PERMANENT";

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

    /** TRANSIENT (worth retrying: an outage, a timeout) or PERMANENT (will never succeed as it stands), NS-SH12-003. */
    @Column(name = "failure_class", length = 20)
    private String failureClass;

    /** When the delivery was dead-lettered: retries exhausted, or refused as permanent. */
    @Column(name = "dead_lettered_at")
    private LocalDateTime deadLetteredAt;

    protected EventDelivery() {
    }

    EventDelivery(OutboxEvent event, String handler) {
        this.event = event;
        this.handler = handler;
        this.status = PENDING;
        this.nextAttemptAt = dueNow();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * "Due immediately", truncated to whole seconds. The column keeps less precision than the JVM clock (MySQL
     * DATETIME keeps seconds, H2 microseconds) and the database rounds on insert, so an untruncated value can be
     * stored slightly later than the moment it was created; a dispatch in the same clock tick would then skip it.
     */
    private static LocalDateTime dueNow() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
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
        failed(error, maxAttempts, TRANSIENT);
    }

    /**
     * A failed attempt of the given class. A transient failure is retried with backoff until maxAttempts and then
     * dead-lettered; a permanent one is dead-lettered at once, since retrying cannot help (NS-SH12-003).
     */
    void failed(String error, int maxAttempts, String failureClass) {
        attempts++;
        lastError = error == null ? "Handler failed" : error.length() <= 1000 ? error : error.substring(0, 1000);
        updatedAt = LocalDateTime.now();
        this.failureClass = failureClass;
        if (attempts >= maxAttempts || PERMANENT.equals(failureClass)) {
            status = FAILED;
            deadLetteredAt = updatedAt;
        } else {
            nextAttemptAt = updatedAt.plusSeconds(5L << Math.min(attempts, 6));
        }
    }

    /**
     * Held back, not attempted: an earlier event of the same aggregate has not reached this handler yet (NS-API-004).
     * The reason stays on the delivery as evidence; it is tried again once the earlier one is through.
     */
    void heldFor(String reason, int seconds) {
        lastError = reason.length() <= 1000 ? reason : reason.substring(0, 1000);
        updatedAt = LocalDateTime.now();
        nextAttemptAt = dueNow().plusSeconds(seconds);
    }

    void ignored(String reason) {
        attempts++;
        status = IGNORED;
        lastError = reason.length() <= 1000 ? reason : reason.substring(0, 1000);
        deliveredAt = LocalDateTime.now();
        updatedAt = deliveredAt;
    }

    /** A delivery that can never succeed as it stands (an unsupported schema version): failed at once. */
    void refused(String reason) {
        attempts++;
        status = FAILED;
        failureClass = PERMANENT;
        deadLetteredAt = LocalDateTime.now();
        lastError = reason.length() <= 1000 ? reason : reason.substring(0, 1000);
        updatedAt = LocalDateTime.now();
    }

    void replay() {
        status = PENDING;
        attempts = 0;
        nextAttemptAt = dueNow();
        updatedAt = LocalDateTime.now();
        deadLetteredAt = null;
    }

    /** An operator asks for the next retry of a pending delivery now rather than after its backoff. */
    void expedite() {
        nextAttemptAt = dueNow();
        updatedAt = LocalDateTime.now();
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

    public String getFailureClass() {
        return failureClass;
    }

    public LocalDateTime getDeadLetteredAt() {
        return deadLetteredAt;
    }
}
