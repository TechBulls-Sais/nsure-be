package com.nsure.group;

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

/** A category's expiring and proposed terms on one renewal version (the renewal comparison, GL05 workspace). */
@Entity
@Table(name = "grp_renewal_term")
public class RenewalTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    RenewalVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    BenefitCategory category;

    @Column(name = "category_code", nullable = false, length = 20)
    String categoryCode;

    @Column(nullable = false)
    int members;

    @Column(nullable = false, precision = 17, scale = 2)
    BigDecimal cover;

    @Column(name = "expiring_rate", nullable = false, precision = 9, scale = 4)
    BigDecimal expiringRate;

    @Column(name = "proposed_rate", nullable = false, precision = 9, scale = 4)
    BigDecimal proposedRate;

    @Column(name = "expiring_free_cover", nullable = false, precision = 15, scale = 2)
    BigDecimal expiringFreeCover;

    @Column(name = "proposed_free_cover", nullable = false, precision = 15, scale = 2)
    BigDecimal proposedFreeCover;

    @Column(name = "cessation_age", nullable = false)
    int cessationAge;

    @Column(name = "expiring_annual", nullable = false, precision = 15, scale = 2)
    BigDecimal expiringAnnual;

    @Column(name = "proposed_annual", nullable = false, precision = 15, scale = 2)
    BigDecimal proposedAnnual;

    protected RenewalTerm() {
    }

    RenewalTerm(RenewalVersion version, BenefitCategory category) {
        this.version = version;
        this.category = category;
        this.categoryCode = category.getCode();
    }
}
