package com.nsure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * One instruction to pay a benefit out to a payee (NS-SH05-007, §09 payment state machine, NS-TXN-003). Created and
 * approved in the same transaction as the decision that authorises the liability, submitted to the payout rail, and
 * settled, failed or returned only on the rail's answer:
 *
 * <pre>
 *   CREATED ─▶ APPROVED ─▶ SUBMITTED ─▶ PENDING ─┬─▶ SETTLED ─▶ RETURNED
 *                │                       │       ├─▶ FAILED
 *                └─▶ CANCELLED           └─▶ UNKNOWN (no answer in time; reconciled with the bank before any retry)
 * </pre>
 *
 * {@code openKey} is set while the instruction is not final and cleared when it is, so the unique index allows at
 * most one live instruction per benefit; {@code idempotencyKey} makes each attempt unique (exactly-once effect).
 */
@Entity
@Table(name = "pay_instruction")
public class PayoutInstruction {

    public static final String CREATED = "CREATED";
    public static final String APPROVED = "APPROVED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String PENDING = "PENDING";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String SETTLED = "SETTLED";
    public static final String FAILED = "FAILED";
    public static final String RETURNED = "RETURNED";
    public static final String CANCELLED = "CANCELLED";

    /** States after which nothing more is expected from the rail (RETURNED can still follow SETTLED). */
    public static final Set<String> FINAL = Set.of(SETTLED, FAILED, RETURNED, CANCELLED);
    /** The money is (or may be) with the bank: nothing else may be sent for the same benefit. */
    public static final Set<String> IN_FLIGHT = Set.of(SUBMITTED, PENDING, UNKNOWN);

    public static final String EXCEPTION_OPEN = "OPEN";
    public static final String EXCEPTION_RESOLVED = "RESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instruction_ref", nullable = false, unique = true, length = 20, updatable = false)
    private String ref;

    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private String sourceType;

    @Column(name = "source_ref", nullable = false, length = 30, updatable = false)
    private String sourceRef;

    @Column(name = "policy_ref", length = 30, updatable = false)
    private String policyRef;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 80, updatable = false)
    private String idempotencyKey;

    @Column(name = "open_key", unique = true, length = 60)
    private String openKey;

    @Column(name = "payee_name", nullable = false, length = 150, updatable = false)
    private String payeeName;

    @Column(name = "payout_method", nullable = false, length = 40, updatable = false)
    private String payoutMethod;

    @Column(name = "account_ref", nullable = false, length = 60, updatable = false)
    private String accountRef;

    @Column(nullable = false, precision = 15, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 30)
    private String provider;

    @Column(name = "provider_reference", unique = true, length = 60)
    private String providerReference;

    @Column(name = "replaces_ref", length = 20, updatable = false)
    private String replacesRef;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "outcome_at")
    private LocalDateTime outcomeAt;

    @Column(name = "failure_code", length = 20)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "exception_state", length = 20)
    private String exceptionState;

    @Column(name = "exception_note", length = 500)
    private String exceptionNote;

    @Column(name = "exception_resolved_by", length = 100)
    private String exceptionResolvedBy;

    @Column(name = "exception_resolved_at")
    private LocalDateTime exceptionResolvedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected PayoutInstruction() {
    }

    PayoutInstruction(String ref, String sourceType, String sourceRef, String policyRef, int attemptNo,
            String payeeName, String payoutMethod, String accountRef, BigDecimal amount, String currency,
            String replacesRef, String createdBy) {
        this.ref = ref;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.policyRef = policyRef;
        this.attemptNo = attemptNo;
        this.idempotencyKey = sourceType + ":" + sourceRef + ":" + attemptNo;
        this.openKey = sourceType + ":" + sourceRef;
        this.payeeName = payeeName;
        this.payoutMethod = payoutMethod;
        this.accountRef = accountRef;
        this.amount = amount;
        this.currency = currency;
        this.replacesRef = replacesRef;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.status = CREATED;
    }

    void approve(String actor) {
        this.status = APPROVED;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void submitted(String provider, String providerReference, String actor) {
        this.status = SUBMITTED;
        this.provider = provider;
        this.providerReference = providerReference;
        this.submittedBy = actor;
        this.submittedAt = LocalDateTime.now();
    }

    void moveTo(String next) {
        this.status = next;
        if (FINAL.contains(next)) {
            this.outcomeAt = LocalDateTime.now();
            if (!SETTLED.equals(next)) {
                this.openKey = null;
            }
        }
        if (SETTLED.equals(next)) {
            // A settled payment is no longer "live" for a resend; a later return re-opens the benefit for a new one.
            this.openKey = null;
        }
    }

    void failure(String code, String reason) {
        this.failureCode = code;
        this.failureReason = reason;
    }

    void openException(String note) {
        this.exceptionState = EXCEPTION_OPEN;
        this.exceptionNote = note;
        this.exceptionResolvedBy = null;
        this.exceptionResolvedAt = null;
    }

    void resolveException(String note, String actor) {
        this.exceptionState = EXCEPTION_RESOLVED;
        if (note != null) {
            this.exceptionNote = this.exceptionNote == null ? note : this.exceptionNote + " · " + note;
            if (this.exceptionNote.length() > 500) {
                this.exceptionNote = this.exceptionNote.substring(0, 499) + "…";
            }
        }
        this.exceptionResolvedBy = actor;
        this.exceptionResolvedAt = LocalDateTime.now();
    }

    public boolean isExceptionOpen() {
        return EXCEPTION_OPEN.equals(exceptionState);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayeeName() {
        return payeeName;
    }

    public String getPayoutMethod() {
        return payoutMethod;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getReplacesRef() {
        return replacesRef;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public LocalDateTime getOutcomeAt() {
        return outcomeAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getExceptionState() {
        return exceptionState;
    }

    public String getExceptionNote() {
        return exceptionNote;
    }

    public String getExceptionResolvedBy() {
        return exceptionResolvedBy;
    }

    public LocalDateTime getExceptionResolvedAt() {
        return exceptionResolvedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
