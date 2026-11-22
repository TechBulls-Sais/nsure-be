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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The risk on one policy ceded under a treaty (NS-SH07-002/003): calculated at issue and again on every benefit
 * change, per life (the life's other policies under the same treaty count towards retention and capacity), under the
 * treaty in force on the event date. A change never edits a version: it supersedes it with the next one, so the
 * original treaty and share stay on record. Premium cessions and claim recoveries use the version in force.
 */
@Entity
@Table(name = "ri_exposure")
public class Exposure {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String ENDED = "ENDED";
    /** No approved treaty covered the product on the event date (AT-SH07-01): nothing is ceded. */
    public static final String NO_TREATY = "NO_TREATY";

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

    @Column(nullable = false)
    private int version;

    @Column(name = "sum_assured", nullable = false, precision = 15, scale = 2)
    private BigDecimal sumAssured;

    @Column(name = "aggregate_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal aggregateSum;

    @Column(name = "retained_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal retainedSum;

    @Column(name = "ceded_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal cededSum;

    @Column(name = "ceded_share", nullable = false, precision = 9, scale = 6)
    private BigDecimal cededShare;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "treaty_id")
    private Treaty treaty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facultative_id")
    private Facultative facultative;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    @Column(name = "source_event_id", nullable = false, unique = true, length = 36)
    private String sourceEventId;

    @Column(name = "source_event_type", nullable = false, length = 60)
    private String sourceEventType;

    @Column(nullable = false, length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_id")
    private Exposure supersedes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Exposure() {
    }

    Exposure(String policyRef, String customerRef, String product, int version, BigDecimal sumAssured,
            BigDecimal aggregateSum, BigDecimal retainedSum, BigDecimal cededSum, BigDecimal cededShare, Treaty treaty,
            String status, LocalDate effectiveFrom, String sourceEventId, String sourceEventType, String reason,
            Exposure supersedes) {
        this.ref = "TMP-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.policyRef = policyRef;
        this.customerRef = customerRef;
        this.product = product;
        this.version = version;
        this.sumAssured = sumAssured;
        this.aggregateSum = aggregateSum;
        this.retainedSum = retainedSum;
        this.cededSum = cededSum;
        this.cededShare = cededShare;
        this.treaty = treaty;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.sourceEventId = sourceEventId;
        this.sourceEventType = sourceEventType;
        this.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.supersedes = supersedes;
        this.createdAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("EXP-%05d", id);
    }

    void end(String status, LocalDate on) {
        this.status = status;
        this.endedOn = on;
    }

    void facultative(Facultative facultative) {
        this.facultative = facultative;
    }

    /** In force on {@code day}: started on or before it and not ended by it. */
    boolean inForceOn(LocalDate day) {
        return !effectiveFrom.isAfter(day) && (endedOn == null || endedOn.isAfter(day));
    }

    public boolean isLive() {
        return ACTIVE.equals(status) || NO_TREATY.equals(status);
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

    public int getVersion() {
        return version;
    }

    public BigDecimal getSumAssured() {
        return sumAssured;
    }

    public BigDecimal getAggregateSum() {
        return aggregateSum;
    }

    public BigDecimal getRetainedSum() {
        return retainedSum;
    }

    public BigDecimal getCededSum() {
        return cededSum;
    }

    public BigDecimal getCededShare() {
        return cededShare;
    }

    public Treaty getTreaty() {
        return treaty;
    }

    public Facultative getFacultative() {
        return facultative;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEndedOn() {
        return endedOn;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getSourceEventType() {
        return sourceEventType;
    }

    public String getReason() {
        return reason;
    }

    public Exposure getSupersedes() {
        return supersedes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
