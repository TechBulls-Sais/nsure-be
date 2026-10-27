package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_cover_tier")
public class CoverTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private InsuranceProduct product;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "cover_amount", nullable = false)
    private int coverAmount;

    @Column(nullable = false)
    private int premium;

    @Column(name = "premium_frequency", nullable = false, length = 20)
    private String premiumFrequency = "Monthly";

    @Column(nullable = false)
    private boolean popular;

    @Column(length = 500)
    private String benefits;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active = true;

    protected CoverTier() {
    }

    public Long getId() {
        return id;
    }

    public InsuranceProduct getProduct() {
        return product;
    }

    public String getName() {
        return name;
    }

    public int getCoverAmount() {
        return coverAmount;
    }

    public int getPremium() {
        return premium;
    }

    public String getPremiumFrequency() {
        return premiumFrequency;
    }

    public boolean isPopular() {
        return popular;
    }

    public String getBenefits() {
        return benefits;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }
}
