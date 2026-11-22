package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A staff-keyed reversal of a premium receipt (NS-SH04-005): requested by one Finance user with the bank's reason,
 * carried out only when a different person approves it. PENDING_APPROVAL → APPROVED (reversed) or REJECTED.
 */
@Entity
@Table(name = "bil_reversal_request")
public class ReversalRequest {

    public static final String PENDING = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "receipt_ref", nullable = false, length = 20)
    private String receiptRef;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "reversal_ref", length = 20)
    private String reversalRef;

    protected ReversalRequest() {
    }

    ReversalRequest(String ref, String receiptRef, String policyRef, String kind, BigDecimal amount, String reason,
            String requestedBy) {
        this.ref = ref;
        this.receiptRef = receiptRef;
        this.policyRef = policyRef;
        this.kind = kind;
        this.amount = amount;
        this.reason = reason;
        this.status = PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = LocalDateTime.now();
    }

    void decide(String status, String by, String note, String reversalRef) {
        this.status = status;
        this.decidedBy = by;
        this.decidedAt = LocalDateTime.now();
        this.decisionNote = note;
        this.reversalRef = reversalRef;
    }

    public String getRef() {
        return ref;
    }

    public String getReceiptRef() {
        return receiptRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getKind() {
        return kind;
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

    public String getRequestedBy() {
        return requestedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getReversalRef() {
        return reversalRef;
    }
}
