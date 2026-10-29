package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Commission rates for a product sold by one type of intermediary, from a date. The rule in force on a day is the
 * one with the latest effective date on or before it. Rules are never edited; a new rate is a new rule.
 */
@Entity
@Table(name = "dist_commission_rule")
public class CommissionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String product;

    @Column(name = "intermediary_type", nullable = false, length = 20)
    private String intermediaryType;

    @Column(name = "initial_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal initialRatePercent;

    @Column(name = "renewal_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal renewalRatePercent;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CommissionRule() {
    }

    CommissionRule(String product, String intermediaryType, BigDecimal initialRatePercent,
            BigDecimal renewalRatePercent, LocalDate effectiveFrom, String actor) {
        this.product = product;
        this.intermediaryType = intermediaryType;
        this.initialRatePercent = initialRatePercent;
        this.renewalRatePercent = renewalRatePercent;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = actor;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getProduct() {
        return product;
    }

    public String getIntermediaryType() {
        return intermediaryType;
    }

    public BigDecimal getInitialRatePercent() {
        return initialRatePercent;
    }

    public BigDecimal getRenewalRatePercent() {
        return renewalRatePercent;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
