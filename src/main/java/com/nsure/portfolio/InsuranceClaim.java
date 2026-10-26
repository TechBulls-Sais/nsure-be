package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

    @Column(name = "payout_amount")
    private Integer payoutAmount;

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

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Integer getPayoutAmount() {
        return payoutAmount;
    }

    public void setPayoutAmount(Integer payoutAmount) {
        this.payoutAmount = payoutAmount;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void pay(String reference) {
        this.payoutReference = reference;
        this.paidAt = LocalDateTime.now();
    }
}
