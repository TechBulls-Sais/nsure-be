package com.nsure.controls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Product-level straight-through processing rules (spec 12 C-15, C-16). Resolution is product value, then the global
 * control, then deny; a NULL ceiling inherits the global {@code claim.stp_ceiling}. No row: everything inherits.
 */
@Entity
@Table(name = "product_stp_policy")
public class ProductStpPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "stp_enabled", nullable = false)
    private boolean stpEnabled = true;

    @Column(name = "stp_ceiling", precision = 15, scale = 2)
    private BigDecimal stpCeiling;

    @Column(name = "requires_verified_payout", nullable = false)
    private boolean requiresVerifiedPayout = true;

    @Column(name = "max_claims_per_policy_per_year")
    private Integer maxClaimsPerPolicyPerYear;

    protected ProductStpPolicy() {
    }

    public ProductStpPolicy(Long productId) {
        this.productId = productId;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public boolean isStpEnabled() {
        return stpEnabled;
    }

    public void setStpEnabled(boolean stpEnabled) {
        this.stpEnabled = stpEnabled;
    }

    public BigDecimal getStpCeiling() {
        return stpCeiling;
    }

    public void setStpCeiling(BigDecimal stpCeiling) {
        this.stpCeiling = stpCeiling;
    }

    public boolean isRequiresVerifiedPayout() {
        return requiresVerifiedPayout;
    }

    public void setRequiresVerifiedPayout(boolean requiresVerifiedPayout) {
        this.requiresVerifiedPayout = requiresVerifiedPayout;
    }

    public Integer getMaxClaimsPerPolicyPerYear() {
        return maxClaimsPerPolicyPerYear;
    }

    public void setMaxClaimsPerPolicyPerYear(Integer maxClaimsPerPolicyPerYear) {
        this.maxClaimsPerPolicyPerYear = maxClaimsPerPolicyPerYear;
    }
}
