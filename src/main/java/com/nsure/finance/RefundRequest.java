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
 * PENDING_APPROVAL → PAYOUT_SENT (approved; payout instruction sent) → SETTLED (the payout rail confirmed it), or
 * REJECTED. The destination is verified at approval (NS-SH04-005).
 */
@Entity
@Table(name = "fin_refund")
public class RefundRequest {

    public static final String PENDING = "PENDING_APPROVAL";
    public static final String SETTLED = "SETTLED";
    public static final String REJECTED = "REJECTED";
    /** Approved; the payout instruction is with the rail and not yet confirmed (submission is not settlement). */
    public static final String PAYOUT_SENT = "PAYOUT_SENT";
    /** A refund the system raised before anyone knew where to pay it: Finance records the destination first. */
    public static final String TO_CONFIRM = "TO CONFIRM";

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

    // NS-SH04-005: how the destination was verified, who recorded it, and the payout instruction that paid it.
    @Column(name = "destination_check", length = 300)
    private String destinationCheck;

    @Column(name = "destination_set_by", length = 100)
    private String destinationSetBy;

    @Column(name = "payout_ref", length = 20)
    private String payoutRef;

    @Column(name = "payout_provider", length = 40)
    private String payoutProvider;

    @Column(name = "payout_reference", length = 60)
    private String payoutReference;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

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

    void destination(String payee, String method, String account, String actor) {
        this.payeeName = payee;
        this.payoutMethod = method;
        this.payoutAccount = account;
        this.destinationSetBy = actor;
    }

    void payoutSent(String check, String payoutRef, String provider, String providerReference) {
        this.destinationCheck = check;
        this.payoutRef = payoutRef;
        this.payoutProvider = provider;
        this.payoutReference = providerReference;
        this.status = PAYOUT_SENT;
    }

    void settled() {
        this.status = SETTLED;
        this.settledAt = LocalDateTime.now();
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

    public String getDestinationCheck() {
        return destinationCheck;
    }

    public String getDestinationSetBy() {
        return destinationSetBy;
    }

    public String getPayoutRef() {
        return payoutRef;
    }

    public String getPayoutProvider() {
        return payoutProvider;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }
}
