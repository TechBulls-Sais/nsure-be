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

/**
 * Someone a policy pays out to (demo server Beneficiary, at most five per policy). A claim's payee is the
 * beneficiary named as the claimant, else the primary one, else the first.
 */
@Entity
@Table(name = "ins_policy_beneficiary")
public class PolicyBeneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private InsurancePolicy policy;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, length = 80)
    private String relationship;

    @Column(name = "national_id", length = 40)
    private String nationalId;

    @Column(length = 40)
    private String mobile;

    @Column(name = "payout_method", length = 80)
    private String payoutMethod;

    @Column(name = "account_ref", length = 80)
    private String accountRef;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected PolicyBeneficiary() {
    }

    public PolicyBeneficiary(InsurancePolicy policy, int displayOrder) {
        this.policy = policy;
        this.displayOrder = displayOrder;
    }

    public Long getId() {
        return id;
    }

    public InsurancePolicy getPolicy() {
        return policy;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getRelationship() {
        return relationship;
    }

    public void setRelationship(String relationship) {
        this.relationship = relationship;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getPayoutMethod() {
        return payoutMethod;
    }

    public void setPayoutMethod(String payoutMethod) {
        this.payoutMethod = payoutMethod;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public void setAccountRef(String accountRef) {
        this.accountRef = accountRef;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
