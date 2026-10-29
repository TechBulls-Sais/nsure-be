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

/** The share of one premium (and of the policy's sum assured) ceded under a treaty. One per source event. */
@Entity
@Table(name = "ri_cession")
public class Cession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treaty_id", nullable = false)
    private Treaty treaty;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(nullable = false, length = 100)
    private String product;

    @Column(name = "source_event_id", nullable = false, unique = true, length = 36)
    private String sourceEventId;

    @Column(name = "sum_assured", nullable = false, precision = 15, scale = 2)
    private BigDecimal sumAssured;

    @Column(name = "ceded_sum", nullable = false, precision = 15, scale = 2)
    private BigDecimal cededSum;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal premium;

    @Column(name = "ceded_premium", nullable = false, precision = 15, scale = 2)
    private BigDecimal cededPremium;

    @Column(name = "ceded_share", nullable = false, precision = 9, scale = 6)
    private BigDecimal cededShare;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bordereau_id")
    private Bordereau bordereau;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Cession() {
    }

    Cession(Treaty treaty, String policyRef, String product, String sourceEventId, BigDecimal sumAssured,
            BigDecimal cededSum, BigDecimal premium, BigDecimal cededPremium, BigDecimal cededShare) {
        this.ref = "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.treaty = treaty;
        this.policyRef = policyRef;
        this.product = product;
        this.sourceEventId = sourceEventId;
        this.sumAssured = sumAssured;
        this.cededSum = cededSum;
        this.premium = premium;
        this.cededPremium = cededPremium;
        this.cededShare = cededShare;
        this.createdAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("CES-%05d", id);
    }

    void reportedOn(Bordereau bordereau) {
        this.bordereau = bordereau;
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

    public String getPolicyRef() {
        return policyRef;
    }

    public String getProduct() {
        return product;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public BigDecimal getSumAssured() {
        return sumAssured;
    }

    public BigDecimal getCededSum() {
        return cededSum;
    }

    public BigDecimal getPremium() {
        return premium;
    }

    public BigDecimal getCededPremium() {
        return cededPremium;
    }

    public BigDecimal getCededShare() {
        return cededShare;
    }

    public Bordereau getBordereau() {
        return bordereau;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
