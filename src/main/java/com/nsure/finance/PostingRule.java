package com.nsure.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One immutable version of how an event type posts: debit account, credit account and the payload field holding the
 * amount (NS-SH08-001, Blueprint §7). A change never edits a version: it proposes the next one, which a different
 * user approves, and which applies to events dated on or after its {@code effectiveFrom} (inclusive) until the next
 * approved version's {@code effectiveFrom} (exclusive). Every journal keeps the id of the version it posted with.
 */
@Entity
@Table(name = "fin_posting_rule")
public class PostingRule {

    /** Whether events of this type post at all under this version. */
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    /**
     * Deliberately not posted under the recognition basis in force (NS-SH08-002): no journal and no exception. Finance
     * switches basis by approving a version that posts.
     */
    public static final String NOT_POSTED = "NOT_POSTED";
    /** NS-SH08-003: a posting failure only raises a financial exception; the business action goes ahead. */
    public static final String CONTINUE = "CONTINUE";
    /** NS-SH08-003: a business action whose event could not post is refused. */
    public static final String BLOCK = "BLOCK";

    /** Approval of the version itself. */
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 60, updatable = false)
    private String eventType;

    @Column(name = "debit_account", nullable = false, length = 20, updatable = false)
    private String debitAccount;

    @Column(name = "credit_account", nullable = false, length = 20, updatable = false)
    private String creditAccount;

    @Column(name = "amount_field", nullable = false, length = 40, updatable = false)
    private String amountField;

    @Column(name = "business_domain", nullable = false, length = 20, updatable = false)
    private String domain;

    @Column(nullable = false, length = 200, updatable = false)
    private String description;

    @Column(nullable = false, length = 20, updatable = false)
    private String status;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "change_reason", length = 500, updatable = false)
    private String changeReason;

    /** Who proposed this version, and when it was recorded. */
    @Column(name = "updated_by", nullable = false, length = 100, updatable = false)
    private String proposedBy;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(name = "approval_status", nullable = false, length = 20)
    private String approvalStatus;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // NS-SH08-001/003: a version for one product (null = every product), the cost centre its journals carry, and
    // whether a posting failure blocks the originating business action.
    @Column(length = 100, updatable = false)
    private String product;

    @Column(name = "cost_centre", length = 20, updatable = false)
    private String costCentre;

    @Column(name = "failure_mode", nullable = false, length = 20, updatable = false)
    private String failureMode = CONTINUE;

    protected PostingRule() {
    }

    PostingRule scoped(String product, String costCentre, String failureMode) {
        this.product = product;
        this.costCentre = costCentre;
        this.failureMode = failureMode == null ? CONTINUE : failureMode;
        return this;
    }

    public String getProduct() {
        return product;
    }

    public String getCostCentre() {
        return costCentre;
    }

    public String getFailureMode() {
        return failureMode;
    }

    boolean isNotPosted() {
        return NOT_POSTED.equals(status);
    }

    PostingRule(String eventType, int version, String debitAccount, String creditAccount, String amountField,
            String domain, String description, String status, LocalDate effectiveFrom, String changeReason,
            String proposedBy) {
        this.eventType = eventType;
        this.version = version;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amountField = amountField;
        this.domain = domain;
        this.description = description;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
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

    boolean isActive() {
        return ACTIVE.equals(status);
    }

    boolean isApproved() {
        return APPROVED.equals(approvalStatus);
    }

    boolean isPending() {
        return PENDING_APPROVAL.equals(approvalStatus);
    }

    /** "Dr 1000 / Cr 4000 on amount, ACTIVE": what a version does, for evidence and comparison. */
    String summary() {
        return "Dr " + debitAccount + " / Cr " + creditAccount + " on " + amountField + ", " + domain + ", " + status
                + (product == null ? "" : ", product " + product) + (costCentre == null ? "" : ", " + costCentre)
                + (BLOCK.equals(failureMode) ? ", blocks on failure" : "");
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getDebitAccount() {
        return debitAccount;
    }

    public String getCreditAccount() {
        return creditAccount;
    }

    public String getAmountField() {
        return amountField;
    }

    public String getDomain() {
        return domain;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
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
