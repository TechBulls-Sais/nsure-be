package com.nsure.reinsurance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A risk referred for facultative placement (NS-SH07-002): the part of a life's ceded sum above the automatic treaty's
 * capacity. REFERRED → OFFERED (sent to a reinsurer) → ACCEPTED (the reinsurer's acceptance captured: it becomes a
 * policy-specific facultative treaty that cedes from then on) or DECLINED (the insurer retains it).
 */
@Entity
@Table(name = "ri_facultative")
public class Facultative {

    public static final String REFERRED = "REFERRED";
    public static final String OFFERED = "OFFERED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(nullable = false, length = 100)
    private String product;

    @Column(name = "sum_assured", nullable = false, precision = 15, scale = 2)
    private BigDecimal sumAssured;

    @Column(name = "aggregate_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal aggregateSum;

    @Column(name = "excess_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal excessSum;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "treaty_id")
    private Treaty treaty;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "referred_reason", nullable = false, length = 500)
    private String referredReason;

    @Column(name = "referred_at", nullable = false)
    private LocalDateTime referredAt;

    @Column(length = 150)
    private String reinsurer;

    @Column(name = "offered_sum", precision = 15, scale = 2)
    private BigDecimal offeredSum;

    @Column(name = "offered_by", length = 100)
    private String offeredBy;

    @Column(name = "offered_at")
    private LocalDateTime offeredAt;

    @Column(name = "accepted_sum", precision = 15, scale = 2)
    private BigDecimal acceptedSum;

    @Column(name = "acceptance_ref", length = 60)
    private String acceptanceRef;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fac_treaty_id")
    private Treaty facTreaty;

    protected Facultative() {
    }

    Facultative(String policyRef, String customerRef, String product, BigDecimal sumAssured, BigDecimal aggregateSum,
            BigDecimal excessSum, Treaty treaty, String reason) {
        this.ref = "TMP-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.policyRef = policyRef;
        this.customerRef = customerRef;
        this.product = product;
        this.sumAssured = sumAssured;
        this.aggregateSum = aggregateSum;
        this.excessSum = excessSum;
        this.treaty = treaty;
        this.status = REFERRED;
        this.referredReason = reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.referredAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("FAC-%05d", id);
    }

    void offered(String reinsurer, BigDecimal sum, String actor) {
        this.status = OFFERED;
        this.reinsurer = reinsurer;
        this.offeredSum = sum;
        this.offeredBy = actor;
        this.offeredAt = LocalDateTime.now();
    }

    void accepted(BigDecimal sum, String acceptanceRef, String note, Treaty facTreaty, String actor) {
        this.status = ACCEPTED;
        this.acceptedSum = sum;
        this.acceptanceRef = acceptanceRef;
        this.decisionNote = note;
        this.facTreaty = facTreaty;
        this.decidedBy = actor;
        this.decidedAt = LocalDateTime.now();
    }

    void declined(String note, String actor) {
        this.status = DECLINED;
        this.decisionNote = note;
        this.decidedBy = actor;
        this.decidedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getProduct() {
        return product;
    }

    public BigDecimal getSumAssured() {
        return sumAssured;
    }

    public BigDecimal getAggregateSum() {
        return aggregateSum;
    }

    public BigDecimal getExcessSum() {
        return excessSum;
    }

    public Treaty getTreaty() {
        return treaty;
    }

    public String getStatus() {
        return status;
    }

    public String getReferredReason() {
        return referredReason;
    }

    public LocalDateTime getReferredAt() {
        return referredAt;
    }

    public String getReinsurer() {
        return reinsurer;
    }

    public BigDecimal getOfferedSum() {
        return offeredSum;
    }

    public String getOfferedBy() {
        return offeredBy;
    }

    public LocalDateTime getOfferedAt() {
        return offeredAt;
    }

    public BigDecimal getAcceptedSum() {
        return acceptedSum;
    }

    public String getAcceptanceRef() {
        return acceptanceRef;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public Treaty getFacTreaty() {
        return facTreaty;
    }
}
