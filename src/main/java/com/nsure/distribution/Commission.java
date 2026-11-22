package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One commission ledger line: an accrual on a premium, or a clawback (negative) of an accrual when the policy is
 * cancelled. Each line is tied to the event that caused it, so a redelivered event adds nothing (AT-SH06-01).
 */
@Entity
@Table(name = "dist_commission")
public class Commission {

    public static final String ACCRUAL = "ACCRUAL";
    public static final String CLAWBACK = "CLAWBACK";
    public static final String ACCRUED = "ACCRUED";
    public static final String STATEMENTED = "STATEMENTED";
    public static final String PAID = "PAID";
    /** Held off statements while the intermediary disputes it (NS-SH06-005). */
    public static final String DISPUTED = "DISPUTED";
    public static final String INITIAL = "INITIAL";
    public static final String RENEWAL = "RENEWAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intermediary_id", nullable = false)
    private Intermediary intermediary;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(nullable = false, length = 100)
    private String product;

    @Column(name = "source_event_id", nullable = false, unique = true, length = 80)
    private String sourceEventId;

    @Column(name = "line_kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "basis_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal basisAmount;

    @Column(name = "rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal ratePercent;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reverses_id", unique = true)
    private Commission reverses;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id")
    private CommissionStatement statement;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // NS-SH06-003: the receipt, rule and agreement behind the line, whether it is initial or renewal commission,
    // the premium's date and a readable calculation trace.
    @Column(name = "receipt_ref", length = 20)
    private String receiptRef;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(length = 20)
    private String basis;

    @Column(name = "calc_trace", length = 500)
    private String calcTrace;

    @Column(name = "premium_date")
    private java.time.LocalDate premiumDate;

    @Column(name = "agreement_ref", length = 20)
    private String agreementRef;

    @Column(name = "dispute_reason", length = 500)
    private String disputeReason;

    @Column(name = "disputed_by", length = 100)
    private String disputedBy;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    protected Commission() {
    }

    Commission traced(String receiptRef, Long ruleId, String basis, String trace, java.time.LocalDate premiumDate,
            String agreementRef) {
        this.receiptRef = receiptRef;
        this.ruleId = ruleId;
        this.basis = basis;
        this.calcTrace = trace == null || trace.length() <= 500 ? trace : trace.substring(0, 500);
        this.premiumDate = premiumDate;
        this.agreementRef = agreementRef;
        return this;
    }

    void dispute(String reason, String actor) {
        this.status = DISPUTED;
        this.disputeReason = reason;
        this.disputedBy = actor;
        this.disputedAt = LocalDateTime.now();
    }

    void undispute(String note) {
        this.status = ACCRUED;
        String resolved = disputeReason + " · resolved: " + note;
        this.disputeReason = resolved.length() <= 500 ? resolved : resolved.substring(0, 500);
    }

    public String getReceiptRef() {
        return receiptRef;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public String getBasis() {
        return basis;
    }

    public String getCalcTrace() {
        return calcTrace;
    }

    public java.time.LocalDate getPremiumDate() {
        return premiumDate;
    }

    public String getAgreementRef() {
        return agreementRef;
    }

    public String getDisputeReason() {
        return disputeReason;
    }

    public String getDisputedBy() {
        return disputedBy;
    }

    private Commission(Intermediary intermediary, String policyRef, String product, String sourceEventId, String kind,
            BigDecimal basisAmount, BigDecimal ratePercent, BigDecimal amount, Commission reverses) {
        this.ref = "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.intermediary = intermediary;
        this.policyRef = policyRef;
        this.product = product;
        this.sourceEventId = sourceEventId;
        this.kind = kind;
        this.basisAmount = basisAmount;
        this.ratePercent = ratePercent;
        this.amount = amount;
        this.status = ACCRUED;
        this.reverses = reverses;
        this.createdAt = LocalDateTime.now();
    }

    static Commission accrual(Intermediary intermediary, String policyRef, String product, String sourceEventId,
            BigDecimal premium, BigDecimal ratePercent, BigDecimal amount) {
        return new Commission(intermediary, policyRef, product, sourceEventId, ACCRUAL, premium, ratePercent, amount,
                null);
    }

    /** Reverses {@code original} in full, owed by the same intermediary. */
    static Commission clawback(Commission original, String sourceEventId) {
        Commission line = new Commission(original.intermediary, original.policyRef, original.product,
                sourceEventId + "/" + original.ref, CLAWBACK, original.basisAmount, original.ratePercent,
                original.amount.negate(), original);
        line.receiptRef = original.receiptRef;
        line.ruleId = original.ruleId;
        line.basis = CLAWBACK;
        line.agreementRef = original.agreementRef;
        line.premiumDate = original.premiumDate;
        return line;
    }

    void numbered() {
        this.ref = String.format("COM-%05d", id);
    }

    void statemented(CommissionStatement statement) {
        this.statement = statement;
        this.status = STATEMENTED;
    }

    void paid() {
        this.status = PAID;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public Intermediary getIntermediary() {
        return intermediary;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getProduct() {
        return product;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getKind() {
        return kind;
    }

    public BigDecimal getBasisAmount() {
        return basisAmount;
    }

    public BigDecimal getRatePercent() {
        return ratePercent;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public Commission getReverses() {
        return reverses;
    }

    public CommissionStatement getStatement() {
        return statement;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
