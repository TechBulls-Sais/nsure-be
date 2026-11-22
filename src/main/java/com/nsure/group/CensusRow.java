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

/**
 * One staged census line exactly as submitted (values kept as text, so an unparseable value can be shown back) with
 * its outcome: VALID / INVALID after validation, APPLIED / REJECTED after commit, and the member it created or changed.
 */
@Entity
@Table(name = "grp_census_row")
public class CensusRow {

    public static final String VALID = "VALID";
    public static final String INVALID = "INVALID";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    /** Valid, but waiting for an authorised review before it may apply (AT-GL04-03). */
    public static final String HELD = "HELD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private CensusBatch batch;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "member_key", length = 40)
    private String memberKey;

    @Column(name = "first_name", length = 60)
    private String firstName;

    @Column(name = "last_name", length = 60)
    private String lastName;

    @Column(name = "date_of_birth", length = 20)
    private String dateOfBirth;

    @Column(name = "category_code", length = 20)
    private String categoryCode;

    @Column(length = 30)
    private String salary;

    @Column(name = "row_action", length = 20)
    private String action;

    @Column(name = "effective_date", length = 20)
    private String effectiveDate;

    @Column(name = "row_status", nullable = false, length = 20)
    private String status;

    @Column(name = "error_detail", length = 1000)
    private String error;

    @Column(name = "member_id")
    private Long memberId;

    /** Optional template column (Y/N): the member's actively-at-work declaration at the entry or increase date. */
    @Column(name = "actively_at_work", length = 5)
    private String activelyAtWork;

    /** Optional template column: the date the member first became eligible, to recognise a late entrant. */
    @Column(name = "eligible_date", length = 20)
    private String eligibleDate;

    /** The member change this row created when applied: the transaction id batch reconciliation traces. */
    @Column(name = "change_id")
    private Long changeId;

    /** Optional template column: the sponsor's own key for this row, applied at most once per scheme. */
    @Column(name = "row_key", length = 80)
    private String rowKey;

    /** Why the row is held for review, and who reviewed it with what note. */
    @Column(name = "hold_reason", length = 500)
    private String holdReason;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private java.time.LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 300)
    private String reviewNote;

    /** Supplementary adjustments the applied row created (GL04 workspace: created transaction ids). */
    @Column(name = "adjustment_refs", length = 200)
    private String adjustmentRefs;

    /** Correction batches: the original line this row corrects, and on an original row what corrected it. */
    @Column(name = "corrects_line")
    private Integer correctsLine;

    @Column(name = "corrected_by", length = 40)
    private String correctedBy;

    @Column(name = "national_id", length = 30)
    private String nationalId;

    @Column(name = "relationship", length = 20)
    private String relationship;

    /** Preview (NS-GL04-005): a full month's premium on the member's cover before and after the row. */
    @Column(name = "premium_before", precision = 15, scale = 2)
    private java.math.BigDecimal premiumBefore;

    @Column(name = "premium_after", precision = 15, scale = 2)
    private java.math.BigDecimal premiumAfter;

    protected CensusRow() {
    }

    CensusRow(CensusBatch batch, int lineNo, String[] cells) {
        this.batch = batch;
        this.lineNo = lineNo;
        this.memberKey = clip(cells[0], 40);
        this.firstName = clip(cells[1], 60);
        this.lastName = clip(cells[2], 60);
        this.dateOfBirth = clip(cells[3], 20);
        this.categoryCode = clip(cells[4], 20);
        this.salary = clip(cells[5], 30);
        this.action = clip(cells[6], 20);
        this.effectiveDate = clip(cells[7], 20);
        this.activelyAtWork = cells.length > 8 ? clip(cells[8], 5) : null;
        this.eligibleDate = cells.length > 9 ? clip(cells[9], 20) : null;
        this.rowKey = cells.length > 10 ? clip(cells[10] == null ? null : cells[10].trim(), 80) : null;
        this.nationalId = cells.length > 11 ? clip(cells[11] == null ? null : cells[11].trim(), 30) : null;
        this.relationship = cells.length > 12 ? clip(cells[12] == null ? null : cells[12].trim(), 20) : null;
        if (cells.length > 13 && cells[13] != null && cells[13].trim().matches("\\d{1,6}")) {
            this.correctsLine = Integer.valueOf(cells[13].trim());
        }
    }

    void hold(String reason) {
        this.holdReason = clip(reason, 500);
    }

    void reviewed(String actor, String note) {
        this.reviewedBy = actor;
        this.reviewNote = clip(note, 300);
        this.reviewedAt = java.time.LocalDateTime.now();
    }

    void adjustments(String refs) {
        this.adjustmentRefs = clip(refs, 200);
    }

    void correctedBy(String ref) {
        this.correctedBy = clip(ref, 40);
    }

    void premiums(java.math.BigDecimal before, java.math.BigDecimal after) {
        this.premiumBefore = before;
        this.premiumAfter = after;
    }

    public String getHoldReason() {
        return holdReason;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public java.time.LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public String getAdjustmentRefs() {
        return adjustmentRefs;
    }

    public Integer getCorrectsLine() {
        return correctsLine;
    }

    public String getCorrectedBy() {
        return correctedBy;
    }

    public String getNationalId() {
        return nationalId;
    }

    public String getRelationship() {
        return relationship;
    }

    public java.math.BigDecimal getPremiumBefore() {
        return premiumBefore;
    }

    public java.math.BigDecimal getPremiumAfter() {
        return premiumAfter;
    }

    void outcome(String status, String error) {
        this.status = status;
        this.error = error == null || error.length() <= 1000 ? error : error.substring(0, 997) + "...";
    }

    void applied(Long memberId, Long changeId) {
        this.status = APPLIED;
        this.memberId = memberId;
        this.changeId = changeId;
    }

    static String clip(String value, int max) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public CensusBatch getBatch() {
        return batch;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getMemberKey() {
        return memberKey;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getSalary() {
        return salary;
    }

    public String getAction() {
        return action;
    }

    public String getEffectiveDate() {
        return effectiveDate;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getActivelyAtWork() {
        return activelyAtWork;
    }

    public String getEligibleDate() {
        return eligibleDate;
    }

    public Long getChangeId() {
        return changeId;
    }

    public String getRowKey() {
        return rowKey;
    }
}
