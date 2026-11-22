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
import java.util.UUID;

/** A treaty's monthly report of cessions to the reinsurer, with record count and control totals. One per month. */
@Entity
@Table(name = "ri_bordereau")
public class Bordereau {

    public static final String SUBMITTED = "SUBMITTED";
    // NS-SH07-004: the reinsurer acknowledges, may ask for a correction (then it is corrected and resubmitted), and
    // the account is settled.
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String CORRECTION_REQUESTED = "CORRECTION_REQUESTED";
    public static final String CORRECTED = "CORRECTED";
    public static final String SETTLED = "SETTLED";
    public static final String PREMIUM = "PREMIUM";
    public static final String CLAIMS = "CLAIMS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treaty_id", nullable = false)
    private Treaty treaty;

    // yyyy-MM
    @Column(name = "bordereau_period", nullable = false, length = 7)
    private String period;

    @Column(name = "record_count", nullable = false)
    private int recordCount;

    @Column(name = "total_ceded_premium", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalCededPremium;

    @Column(name = "total_ceded_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalCededSum;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, length = 20)
    private String kind = PREMIUM;

    @Column(name = "correction_count", nullable = false)
    private int correctionCount;

    @Column(name = "ack_reference", length = 60)
    private String ackReference;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "correction_note", length = 500)
    private String correctionNote;

    @Column(name = "settlement_reference", length = 60)
    private String settlementReference;

    @Column(name = "settled_amount", precision = 15, scale = 2)
    private BigDecimal settledAmount;

    @Column(name = "settled_by", length = 100)
    private String settledBy;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "total_recovery", precision = 15, scale = 2)
    private BigDecimal totalRecovery;

    protected Bordereau() {
    }

    Bordereau claims(BigDecimal totalRecovery) {
        this.kind = CLAIMS;
        this.totalRecovery = totalRecovery;
        return this;
    }

    void acknowledge(String reference, String actor) {
        this.status = ACKNOWLEDGED;
        this.ackReference = reference;
        this.acknowledgedBy = actor;
        this.acknowledgedAt = LocalDateTime.now();
    }

    void correctionRequested(String note) {
        this.status = CORRECTION_REQUESTED;
        this.correctionNote = note;
    }

    void corrected(int count, BigDecimal premium, BigDecimal sum, BigDecimal recovery) {
        this.status = CORRECTED;
        this.correctionCount++;
        this.recordCount = count;
        this.totalCededPremium = premium;
        this.totalCededSum = sum;
        if (recovery != null) {
            this.totalRecovery = recovery;
        }
    }

    void settle(String reference, BigDecimal amount, String actor) {
        this.status = SETTLED;
        this.settlementReference = reference;
        this.settledAmount = amount;
        this.settledBy = actor;
        this.settledAt = LocalDateTime.now();
    }

    public String getKind() {
        return kind;
    }

    public int getCorrectionCount() {
        return correctionCount;
    }

    public String getAckReference() {
        return ackReference;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public String getCorrectionNote() {
        return correctionNote;
    }

    public String getSettlementReference() {
        return settlementReference;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public String getSettledBy() {
        return settledBy;
    }

    public BigDecimal getTotalRecovery() {
        return totalRecovery;
    }

    Bordereau(Treaty treaty, String period, int recordCount, BigDecimal totalCededPremium, BigDecimal totalCededSum,
            String actor) {
        this.ref = "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.treaty = treaty;
        this.period = period;
        this.recordCount = recordCount;
        this.totalCededPremium = totalCededPremium;
        this.totalCededSum = totalCededSum;
        this.status = SUBMITTED;
        this.createdBy = actor;
        this.createdAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("BDX-%05d", id);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public Treaty getTreaty() {
        return treaty;
    }

    public String getPeriod() {
        return period;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public BigDecimal getTotalCededPremium() {
        return totalCededPremium;
    }

    public BigDecimal getTotalCededSum() {
        return totalCededSum;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
