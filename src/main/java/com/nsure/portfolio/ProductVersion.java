package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One immutable version of a product's contract terms (NS-SH02-004, NS-IL03-001): waiting period, grace period,
 * beneficiary limit and the wording it is sold under. A change proposes the next version, a different user approves
 * it, and it applies to new business from {@code effectiveFrom} (inclusive). A policy is pinned to the version in
 * force when it was issued and keeps it for life unless an approved change explicitly migrates it.
 */
@Entity
@Table(name = "ins_product_version")
public class ProductVersion {

    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_name", nullable = false, length = 100, updatable = false)
    private String product;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "waiting_period_days", nullable = false, updatable = false)
    private int waitingPeriodDays;

    @Column(name = "grace_period_days", nullable = false, updatable = false)
    private int gracePeriodDays;

    @Column(name = "max_beneficiaries", nullable = false, updatable = false)
    private int maxBeneficiaries;

    @Column(name = "wording_ref", nullable = false, length = 120, updatable = false)
    private String wordingRef;

    @Column(name = "change_reason", length = 500, updatable = false)
    private String changeReason;

    @Column(name = "proposed_by", nullable = false, length = 100, updatable = false)
    private String proposedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(name = "approval_status", nullable = false, length = 20)
    private String approvalStatus;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    protected ProductVersion() {
    }

    ProductVersion(String product, int versionNo, LocalDate effectiveFrom, int waitingPeriodDays, int gracePeriodDays,
            int maxBeneficiaries, String wordingRef, String changeReason, String proposedBy) {
        this.product = product;
        this.versionNo = versionNo;
        this.effectiveFrom = effectiveFrom;
        this.waitingPeriodDays = waitingPeriodDays;
        this.gracePeriodDays = gracePeriodDays;
        this.maxBeneficiaries = maxBeneficiaries;
        this.wordingRef = wordingRef;
        this.changeReason = changeReason;
        this.proposedBy = proposedBy;
        this.recordedAt = LocalDateTime.now().withNano(0);
        this.approvalStatus = PENDING_APPROVAL;
    }

    void approve(String by) {
        this.approvalStatus = APPROVED;
        this.approvedBy = by;
        this.approvedAt = LocalDateTime.now().withNano(0);
    }

    void reject(String by, String reason) {
        this.approvalStatus = REJECTED;
        this.approvedBy = by;
        this.approvedAt = LocalDateTime.now().withNano(0);
        this.rejectionReason = reason;
    }

    boolean isApproved() {
        return APPROVED.equals(approvalStatus);
    }

    boolean isPending() {
        return PENDING_APPROVAL.equals(approvalStatus);
    }

    /** "waiting 90 days, grace 30 days, 5 beneficiaries, Standard life wording v1.0". */
    String summary() {
        return "waiting " + waitingPeriodDays + " days, grace " + gracePeriodDays + " days, " + maxBeneficiaries
                + " beneficiaries, " + wordingRef;
    }

    /** "Term Life v2", the label shown on a policy. */
    public String getLabel() {
        return product + " v" + versionNo;
    }

    public Long getId() {
        return id;
    }

    public String getProduct() {
        return product;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public int getWaitingPeriodDays() {
        return waitingPeriodDays;
    }

    public int getGracePeriodDays() {
        return gracePeriodDays;
    }

    public int getMaxBeneficiaries() {
        return maxBeneficiaries;
    }

    public String getWordingRef() {
        return wordingRef;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }
}
