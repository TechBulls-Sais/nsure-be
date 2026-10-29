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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One effective-dated change to a member episode (join, leave, salary, underwriting). Never updated. */
@Entity
@Table(name = "grp_member_change")
public class MemberChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private SchemeMember member;

    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "salary_before", precision = 15, scale = 2)
    private BigDecimal salaryBefore;

    @Column(name = "salary_after", precision = 15, scale = 2)
    private BigDecimal salaryAfter;

    @Column(name = "cover_before", precision = 15, scale = 2)
    private BigDecimal coverBefore;

    @Column(name = "cover_after", precision = 15, scale = 2)
    private BigDecimal coverAfter;

    @Column(name = "pending_after", precision = 15, scale = 2)
    private BigDecimal pendingAfter;

    @Column(name = "batch_ref", length = 20)
    private String batchRef;

    @Column(length = 500)
    private String detail;

    @Column(name = "changed_by", nullable = false, length = 100)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    protected MemberChange() {
    }

    MemberChange(SchemeMember member, String changeType, LocalDate effectiveDate, BigDecimal salaryBefore,
            BigDecimal coverBefore, String batchRef, String detail, String changedBy) {
        this.member = member;
        this.changeType = changeType;
        this.effectiveDate = effectiveDate;
        this.salaryBefore = salaryBefore;
        this.salaryAfter = member.getSalary();
        this.coverBefore = coverBefore;
        this.coverAfter = member.getAcceptedCover();
        this.pendingAfter = member.getPendingCover();
        this.batchRef = batchRef;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.changedBy = changedBy;
        this.changedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public SchemeMember getMember() {
        return member;
    }

    public String getChangeType() {
        return changeType;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public BigDecimal getSalaryBefore() {
        return salaryBefore;
    }

    public BigDecimal getSalaryAfter() {
        return salaryAfter;
    }

    public BigDecimal getCoverBefore() {
        return coverBefore;
    }

    public BigDecimal getCoverAfter() {
        return coverAfter;
    }

    public BigDecimal getPendingAfter() {
        return pendingAfter;
    }

    public String getBatchRef() {
        return batchRef;
    }

    public String getDetail() {
        return detail;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }
}
