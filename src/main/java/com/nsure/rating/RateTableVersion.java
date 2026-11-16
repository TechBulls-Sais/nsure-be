package com.nsure.rating;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * One immutable set of rates. A price or band change never edits a version: it closes the current one
 * (stamps valid_to) and adds the next, so a policy's premium stays traceable to the exact version (R-12).
 */
@Entity
@Table(name = "ins_rate_table_version")
public class RateTableVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_table_id", nullable = false)
    private RateTable rateTable;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(nullable = false, length = 20)
    private String source = "MANUAL";

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "change_summary", length = 4000)
    private String changeSummary;

    protected RateTableVersion() {
    }

    RateTableVersion(RateTable rateTable, int versionNo, LocalDate validFrom, String createdBy, String changeSummary) {
        this.rateTable = rateTable;
        this.versionNo = versionNo;
        this.validFrom = validFrom;
        this.createdBy = createdBy;
        this.changeSummary = changeSummary;
    }

    public Long getId() {
        return id;
    }

    public RateTable getRateTable() {
        return rateTable;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public String getSource() {
        return source;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    /** "RT-00001-v2", the value stamped onto a policy priced from this version. */
    public String getLabel() {
        return rateTable.getRef() + "-v" + versionNo;
    }

    void close(LocalDate on) {
        this.validTo = on;
        this.status = SUPERSEDED;
    }

    // ── spec 11 §3: the management workflow ──────────────────────────────────────────────────────────────

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String REJECTED = "REJECTED";

    @Column(nullable = false, length = 20)
    private String status = DRAFT;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "submitted_at")
    private java.time.LocalDateTime submittedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private java.time.LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "notice_issued_at")
    private LocalDate noticeIssuedAt;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    /** E-28: only DRAFT, PENDING_APPROVAL and REJECTED versions are mutable; PUBLISHED and SUPERSEDED never. */
    public boolean isEditable() {
        return DRAFT.equals(status) || REJECTED.equals(status);
    }

    void edited(String summary, String by) {
        if (!isEditable()) {
            throw new IllegalArgumentException(getLabel() + " is " + status + " and cannot be edited.");
        }
        this.status = DRAFT;
        this.rejectionReason = null;
        this.changeSummary = summary;
        this.createdBy = by;
        touch();
    }

    void submit(String by) {
        this.status = PENDING_APPROVAL;
        this.submittedBy = by;
        this.submittedAt = java.time.LocalDateTime.now();
        touch();
    }

    void approve(String by, LocalDate effective, LocalDate notice, String override) {
        this.approvedBy = by;
        this.approvedAt = java.time.LocalDateTime.now();
        this.effectiveFrom = effective;
        this.noticeIssuedAt = notice;
        this.overrideReason = override;
        this.status = SCHEDULED;
        touch();
    }

    void publish(LocalDate on) {
        this.validFrom = on;
        this.validTo = null;
        this.status = PUBLISHED;
        touch();
    }

    void reject(String reason) {
        this.status = REJECTED;
        this.rejectionReason = reason;
        touch();
    }

    void touch() {
        this.updatedAt = java.time.LocalDateTime.now().withNano(0);
    }

    void source(String value) {
        this.source = value;
    }

    public String getStatus() {
        return status;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public java.time.LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public java.time.LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public LocalDate getNoticeIssuedAt() {
        return noticeIssuedAt;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
