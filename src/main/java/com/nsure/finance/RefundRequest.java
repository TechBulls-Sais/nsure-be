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
 * A controlled refund (AT-SH10-01): raised by one person with a reason, paid only when someone else approves it.
 * PENDING_APPROVAL → SETTLED (approved and paid) or REJECTED.
 */
@Entity
@Table(name = "fin_refund")
public class RefundRequest {

    public static final String PENDING = "PENDING_APPROVAL";
    public static final String SETTLED = "SETTLED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(name = "source_ref", length = 60)
    private String sourceRef;

    @Column(name = "policy_ref", length = 40)
    private String policyRef;

    @Column(name = "payee_name", nullable = false, length = 150)
    private String payeeName;

    @Column(name = "payout_method", nullable = false, length = 40)
    private String payoutMethod;

    @Column(name = "payout_account", nullable = false, length = 60)
    private String payoutAccount;

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

    protected RefundRequest() {
    }

    RefundRequest(String ref, String source, String sourceRef, String policyRef, String payeeName, String payoutMethod,
            String payoutAccount, BigDecimal amount, String reason, String requestedBy) {
        this.ref = ref;
        this.source = source;
        this.sourceRef = sourceRef;
        this.policyRef = policyRef;
        this.payeeName = payeeName;
        this.payoutMethod = payoutMethod;
        this.payoutAccount = payoutAccount;
        this.amount = amount;
        this.reason = reason;
        this.status = PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = LocalDateTime.now();
    }

    void decide(String status, String by, String note) {
        this.status = status;
        this.decidedBy = by;
        this.decidedAt = LocalDateTime.now();
        this.decisionNote = note;
    }

    public String getRef() {
        return ref;
    }

    public String getSource() {
        return source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getPayeeName() {
        return payeeName;
    }

    public String getPayoutMethod() {
        return payoutMethod;
    }

    public String getPayoutAccount() {
        return payoutAccount;
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
}
