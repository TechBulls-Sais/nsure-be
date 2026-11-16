package com.nsure.audit.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

/**
 * One piece of append-only audit evidence (NS-SH11-005): who did what to which record, when, why, and under which
 * correlation id. Each record carries the hash of the one before it, so editing or removing any record breaks the
 * chain and {@link EvidenceChain#verify()} names the first place it breaks (AT-SH11-03). Nothing in the
 * application updates or deletes evidence.
 */
@Entity
@Immutable
@Table(name = "audit_evidence")
public class AuditEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_no", nullable = false, unique = true)
    private long sequenceNo;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(nullable = false, length = 120)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_ref", length = 80)
    private String entityRef;

    @Column(length = 1000)
    private String reason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;

    protected AuditEvidence() {
    }

    AuditEvidence(long sequenceNo, EvidenceChain.Pending pending, String prevHash) {
        this.sequenceNo = sequenceNo;
        this.occurredAt = pending.occurredAt();
        this.actor = pending.actor();
        this.action = pending.action();
        this.entityType = pending.entityType();
        this.entityRef = pending.entityRef();
        this.reason = pending.reason();
        this.correlationId = pending.correlationId();
        this.prevHash = prevHash;
        this.hash = EvidenceChain.hash(this);
    }

    public Long getId() {
        return id;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityRef() {
        return entityRef;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
