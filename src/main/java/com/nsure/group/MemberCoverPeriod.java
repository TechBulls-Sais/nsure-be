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

/**
 * One effective-dated slice of a member's cover episode (NS-GL04-004): the category, salary and accepted cover in force
 * from {@code validFrom} to {@code validTo} (inclusive; open while current). A change closes the open slice and opens
 * the next, so earlier terms are never overwritten. A backdated change that lands before later slices supersedes them
 * (they are kept, marked with the slice that replaced them) because the latest instruction wins from its date on.
 */
@Entity
@Table(name = "grp_member_cover")
public class MemberCoverPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private SchemeMember member;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private BenefitCategory category;

    @Column(name = "category_code", nullable = false, length = 20)
    private String categoryCode;

    @Column(precision = 15, scale = 2)
    private BigDecimal salary;

    @Column(name = "accepted_cover", nullable = false, precision = 15, scale = 2)
    private BigDecimal acceptedCover;

    @Column(name = "pending_cover", nullable = false, precision = 15, scale = 2)
    private BigDecimal pendingCover;

    @Column(name = "member_status", nullable = false, length = 30)
    private String status;

    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType;

    @Column(name = "change_id")
    private Long changeId;

    @Column(name = "superseded_by")
    private Long supersededBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected MemberCoverPeriod() {
    }

    MemberCoverPeriod(SchemeMember member, LocalDate validFrom, String changeType, Long changeId) {
        this.member = member;
        this.validFrom = validFrom;
        this.category = member.getCategory();
        this.categoryCode = member.getCategory().getCode();
        this.salary = member.getSalary();
        this.acceptedCover = member.getAcceptedCover();
        this.pendingCover = member.getPendingCover();
        this.status = member.getStatus();
        this.changeType = changeType;
        this.changeId = changeId;
        this.createdAt = LocalDateTime.now();
    }

    /** A transient slice for a member whose history predates cover periods: the member row as it stands. */
    static MemberCoverPeriod snapshot(SchemeMember member) {
        MemberCoverPeriod p = new MemberCoverPeriod(member, member.getJoinDate(), "JOIN", null);
        p.validTo = member.getExitDate();
        return p;
    }

    /** The underwriter's decision on the cover this period requested: accepted and pending amounts as decided. */
    void decided(SchemeMember member, Long decisionChangeId) {
        this.acceptedCover = member.getAcceptedCover();
        this.pendingCover = member.getPendingCover();
        this.status = member.getStatus();
        if (this.changeId == null) {
            this.changeId = decisionChangeId;
        }
    }

    void closeOn(LocalDate lastDay) {
        this.validTo = lastDay;
    }

    void supersede(Long by) {
        this.supersededBy = by;
    }

    boolean live() {
        return supersededBy == null;
    }

    public Long getId() {
        return id;
    }

    public SchemeMember getMember() {
        return member;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public BenefitCategory getCategory() {
        return category;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public BigDecimal getAcceptedCover() {
        return acceptedCover;
    }

    public BigDecimal getPendingCover() {
        return pendingCover;
    }

    public String getStatus() {
        return status;
    }

    public String getChangeType() {
        return changeType;
    }

    public Long getChangeId() {
        return changeId;
    }

    public Long getSupersededBy() {
        return supersededBy;
    }
}
