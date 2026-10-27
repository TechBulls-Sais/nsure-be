package com.nsure.rating;

import com.nsure.portfolio.CoverTier;
import com.nsure.portfolio.InsuranceProduct;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** The rate table for one product plan (cover tier); its prices live in immutable versions. */
@Entity
@Table(name = "ins_rate_table")
public class RateTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private InsuranceProduct product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tier_id", nullable = false)
    private CoverTier tier;

    @Column(nullable = false)
    private boolean active = true;

    protected RateTable() {
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public InsuranceProduct getProduct() {
        return product;
    }

    public CoverTier getTier() {
        return tier;
    }

    public boolean isActive() {
        return active;
    }

    /** "{product} – {plan}", as the demo server titles its rate tables. */
    public String getTitle() {
        return product.getName() + " – " + tier.getName();
    }
}
