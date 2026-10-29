package com.nsure.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A financial exception: a business event that could not post (no active rule, unknown account). It stays visible
 * until Finance retries it after fixing the mapping (NS-SH08-003); the business action itself is not blocked.
 */
@Entity
@Table(name = "fin_exception")
public class UnpostedEvent {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 80)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "source_ref", nullable = false, length = 60)
    private String sourceRef;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "journal_ref", length = 20)
    private String journalRef;

    protected UnpostedEvent() {
    }

    UnpostedEvent(String eventKey, String eventType, String sourceRef, BigDecimal amount, String reason) {
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.sourceRef = sourceRef;
        this.amount = amount;
        this.reason = reason;
        this.status = OPEN;
        this.createdAt = LocalDateTime.now();
    }

    void stillFailing(String reason) {
        this.reason = reason;
    }

    void resolved(String journalRef, String actor) {
        this.status = RESOLVED;
        this.journalRef = journalRef;
        this.resolvedBy = actor;
        this.resolvedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public String getJournalRef() {
        return journalRef;
    }
}
