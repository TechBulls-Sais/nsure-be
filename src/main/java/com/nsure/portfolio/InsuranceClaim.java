package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A claim on a policy. Its status is the workflow state (ClaimWorkflow): only the workflow writes it, so the
 * five check results, payee, assessor and adjudicator always agree with it.
 */
@Entity
@Table(name = "ins_claim")
public class InsuranceClaim extends CoverRecord {

    @Column(name = "claim_type", length = 80)
    private String claimType;

    @Column(length = 1000)
    private String description;

    @Column(name = "event_date", length = 20)
    private String eventDate;

    @Column(name = "policy_ref", length = 20)
    private String policyRef;

    @Column(name = "claimant_name", length = 200)
    private String claimantName;

    @Column(name = "claimant_mobile", length = 40)
    private String claimantMobile;

    @Column(name = "claimant_relationship", length = 80)
    private String claimantRelationship;

    // STP when every check passed and the claim was approved straight through; Manual Review otherwise.
    @Column(name = "processing_type", length = 20)
    private String processingType;

    // Why a claim whose checks all passed still went to manual review (STP off, over the ceiling, no payee).
    @Column(name = "stp_ineligible_reason", length = 300)
    private String stpIneligibleReason;

    /** Spec 12 C-12: whether the claim passed every STP gate, and the coded gate that stopped it if not. */
    @Column(name = "stp_eligible")
    private Boolean stpEligible;

    @Column(name = "stp_ineligible_code", length = 30)
    private String stpIneligibleCode;

    /** The written reason when the assessor approved their own claim under the break-glass authority (C-9). */
    @Column(name = "four_eyes_override_reason", length = 500)
    private String fourEyesOverrideReason;

    @Column(name = "lodged_at")
    private LocalDateTime lodgedAt;

    @Column(name = "check_policy_active", length = 10)
    private String checkPolicyActive;

    @Column(name = "check_waiting_period", length = 10)
    private String checkWaitingPeriod;

    @Column(name = "check_premiums_paid", length = 10)
    private String checkPremiumsPaid;

    @Column(name = "check_beneficiary_match", length = 10)
    private String checkBeneficiaryMatch;

    @Column(name = "check_no_duplicate", length = 10)
    private String checkNoDuplicate;

    // Who the claim pays: a beneficiary of the policy, confirmed by an assessor before approval.
    @Column(name = "payee_name", length = 200)
    private String payeeName;

    @Column(name = "payee_relationship", length = 80)
    private String payeeRelationship;

    @Column(name = "payee_mobile", length = 40)
    private String payeeMobile;

    @Column(name = "payee_payout_method", length = 80)
    private String payeePayoutMethod;

    @Column(name = "payee_account_ref", length = 80)
    private String payeeAccountRef;

    @Column(name = "payee_source", length = 40)
    private String payeeSource;

    @Column(name = "payout_verified", nullable = false)
    private boolean payoutVerified;

    @Column(name = "payout_verified_by", length = 100)
    private String payoutVerifiedBy;

    @Column(name = "payout_verified_at")
    private LocalDateTime payoutVerifiedAt;

    @Column(name = "recommended_by", length = 100)
    private String recommendedBy;

    @Column(name = "recommended_by_id")
    private Long recommendedById;

    @Column(name = "recommended_at")
    private LocalDateTime recommendedAt;

    @Column(name = "adjudicated_by", length = 100)
    private String adjudicatedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    // Whether four-eyes was enforced on approval; false records that the control was off at the time.
    @Column(name = "four_eyes_applied")
    private Boolean fourEyesApplied;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // Why it was approved although a soft check failed (ClaimWorkflow.SOFT_CHECKS); null when none failed.
    @Column(name = "check_override_reason", length = 500)
    private String checkOverrideReason;

    // The check rules the stored results came from (ClaimWorkflow.CHECK_RULES_VERSION); 1 is the old, buggy rules.
    @Column(name = "check_rules_version", nullable = false)
    private int checkRulesVersion = 1;

    // NS-SH05-001: the covered life the claim is for (null id: the policyholder), and the current case reserve.
    @Column(name = "covered_life_id")
    private Long coveredLifeId;

    @Column(name = "covered_life_name", length = 200)
    private String coveredLifeName;

    @Column(name = "reserve_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal reserveAmount = BigDecimal.ZERO;

    // Optimistic locking (NS-TXN-001): a write based on a stale copy is refused with 409, never overwrites.
    @jakarta.persistence.Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "payout_amount", precision = 19, scale = 2)
    private BigDecimal payoutAmount;

    @Column(name = "payout_reference", length = 60)
    private String payoutReference;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    public String getClaimType() {
        return claimType;
    }

    public void setClaimType(String claimType) {
        this.claimType = claimType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEventDate() {
        return eventDate;
    }

    public void setEventDate(String eventDate) {
        this.eventDate = eventDate;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public void setPolicyRef(String policyRef) {
        this.policyRef = policyRef;
    }

    public String getClaimantName() {
        return claimantName;
    }

    public void setClaimantName(String claimantName) {
        this.claimantName = claimantName;
    }

    public String getClaimantMobile() {
        return claimantMobile;
    }

    public void setClaimantMobile(String claimantMobile) {
        this.claimantMobile = claimantMobile;
    }

    public String getClaimantRelationship() {
        return claimantRelationship;
    }

    public void setClaimantRelationship(String claimantRelationship) {
        this.claimantRelationship = claimantRelationship;
    }

    public String getProcessingType() {
        return processingType;
    }

    public void setProcessingType(String processingType) {
        this.processingType = processingType;
    }

    public String getStpIneligibleReason() {
        return stpIneligibleReason;
    }

    public void setStpIneligibleReason(String stpIneligibleReason) {
        this.stpIneligibleReason = stpIneligibleReason;
    }

    public String getCheckPolicyActive() {
        return checkPolicyActive;
    }

    public void setCheckPolicyActive(String checkPolicyActive) {
        this.checkPolicyActive = checkPolicyActive;
    }

    public String getCheckWaitingPeriod() {
        return checkWaitingPeriod;
    }

    public void setCheckWaitingPeriod(String checkWaitingPeriod) {
        this.checkWaitingPeriod = checkWaitingPeriod;
    }

    public String getCheckPremiumsPaid() {
        return checkPremiumsPaid;
    }

    public void setCheckPremiumsPaid(String checkPremiumsPaid) {
        this.checkPremiumsPaid = checkPremiumsPaid;
    }

    public String getCheckBeneficiaryMatch() {
        return checkBeneficiaryMatch;
    }

    public void setCheckBeneficiaryMatch(String checkBeneficiaryMatch) {
        this.checkBeneficiaryMatch = checkBeneficiaryMatch;
    }

    public String getCheckNoDuplicate() {
        return checkNoDuplicate;
    }

    public void setCheckNoDuplicate(String checkNoDuplicate) {
        this.checkNoDuplicate = checkNoDuplicate;
    }

    public String getPayeeName() {
        return payeeName;
    }

    public void setPayeeName(String payeeName) {
        this.payeeName = payeeName;
    }

    public String getPayeeRelationship() {
        return payeeRelationship;
    }

    public void setPayeeRelationship(String payeeRelationship) {
        this.payeeRelationship = payeeRelationship;
    }

    public String getPayeeMobile() {
        return payeeMobile;
    }

    public void setPayeeMobile(String payeeMobile) {
        this.payeeMobile = payeeMobile;
    }

    public String getPayeePayoutMethod() {
        return payeePayoutMethod;
    }

    public void setPayeePayoutMethod(String payeePayoutMethod) {
        this.payeePayoutMethod = payeePayoutMethod;
    }

    public String getPayeeAccountRef() {
        return payeeAccountRef;
    }

    public void setPayeeAccountRef(String payeeAccountRef) {
        this.payeeAccountRef = payeeAccountRef;
    }

    public String getPayeeSource() {
        return payeeSource;
    }

    public void setPayeeSource(String payeeSource) {
        this.payeeSource = payeeSource;
    }

    public boolean isPayoutVerified() {
        return payoutVerified;
    }

    public String getPayoutVerifiedBy() {
        return payoutVerifiedBy;
    }

    public LocalDateTime getPayoutVerifiedAt() {
        return payoutVerifiedAt;
    }

    public void verifyPayout(String verifier) {
        this.payoutVerified = true;
        this.payoutVerifiedBy = verifier;
        this.payoutVerifiedAt = LocalDateTime.now();
    }

    public void clearPayoutVerification() {
        this.payoutVerified = false;
        this.payoutVerifiedBy = null;
        this.payoutVerifiedAt = null;
    }

    public String getRecommendedBy() {
        return recommendedBy;
    }

    public Long getRecommendedById() {
        return recommendedById;
    }

    public LocalDateTime getRecommendedAt() {
        return recommendedAt;
    }

    public void recommend(String assessor, Long assessorId) {
        this.recommendedBy = assessor;
        this.recommendedById = assessorId;
        this.recommendedAt = LocalDateTime.now();
    }

    public String getAdjudicatedBy() {
        return adjudicatedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    /** Approve or reject by a person; null adjudicator is the system (STP). */
    public void resolve(String adjudicator) {
        this.adjudicatedBy = adjudicator;
        this.resolvedAt = LocalDateTime.now();
    }

    public Boolean getFourEyesApplied() {
        return fourEyesApplied;
    }

    public void setFourEyesApplied(Boolean fourEyesApplied) {
        this.fourEyesApplied = fourEyesApplied;
    }

    public Boolean getStpEligible() {
        return stpEligible;
    }

    public void setStpEligible(Boolean stpEligible) {
        this.stpEligible = stpEligible;
    }

    public String getStpIneligibleCode() {
        return stpIneligibleCode;
    }

    public void setStpIneligibleCode(String stpIneligibleCode) {
        this.stpIneligibleCode = stpIneligibleCode;
    }

    public LocalDateTime getLodgedAt() {
        return lodgedAt;
    }

    public void setLodgedAt(LocalDateTime lodgedAt) {
        this.lodgedAt = lodgedAt;
    }

    public String getFourEyesOverrideReason() {
        return fourEyesOverrideReason;
    }

    public void setFourEyesOverrideReason(String fourEyesOverrideReason) {
        this.fourEyesOverrideReason = fourEyesOverrideReason;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public int getCheckRulesVersion() {
        return checkRulesVersion;
    }

    public void setCheckRulesVersion(int checkRulesVersion) {
        this.checkRulesVersion = checkRulesVersion;
    }

    public String getCheckOverrideReason() {
        return checkOverrideReason;
    }

    public void setCheckOverrideReason(String checkOverrideReason) {
        this.checkOverrideReason = checkOverrideReason;
    }

    public BigDecimal getPayoutAmount() {
        return payoutAmount;
    }

    public void setPayoutAmount(BigDecimal payoutAmount) {
        this.payoutAmount = payoutAmount;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    /** The payout instruction is with the provider; it is not paid until the provider confirms it. */
    public void sendPayout(String reference) {
        this.payoutReference = reference;
        this.paidAt = null;
    }

    /** The provider confirmed the payout settled. */
    public void confirmPayout() {
        this.paidAt = LocalDateTime.now();
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public Long getCoveredLifeId() {
        return coveredLifeId;
    }

    public String getCoveredLifeName() {
        return coveredLifeName;
    }

    void coveredLife(Long id, String name) {
        this.coveredLifeId = id;
        this.coveredLifeName = name;
    }

    public BigDecimal getReserveAmount() {
        return reserveAmount;
    }

    void setReserveAmount(BigDecimal reserveAmount) {
        this.reserveAmount = reserveAmount;
    }

    /** A rejected claim is reviewed again: the earlier decision stays in the history, not on the claim. */
    void reopen() {
        this.adjudicatedBy = null;
        this.resolvedAt = null;
        this.rejectionReason = null;
    }
}
