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

    protected Commission() {
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
        return new Commission(original.intermediary, original.policyRef, original.product,
                sourceEventId + "/" + original.ref, CLAWBACK, original.basisAmount, original.ratePercent,
                original.amount.negate(), original);
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
