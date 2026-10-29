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

    protected Bordereau() {
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
