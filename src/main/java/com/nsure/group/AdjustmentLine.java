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

/**
 * One member's line on a supplementary adjustment: what was billed for the month before (the original line plus any
 * earlier adjustments), what the cover periods now earn, and the difference. References the original invoice line,
 * or none for a backdated joiner the invoice did not include.
 */
@Entity
@Table(name = "grp_adjustment_line")
public class AdjustmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "adjustment_id", nullable = false)
    private SchemeAdjustment adjustment;

    @Column(name = "original_line_id")
    private Long originalLineId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "member_key", nullable = false, length = 40)
    private String memberKey;

    @Column(name = "member_name", nullable = false, length = 130)
    private String memberName;

    @Column(name = "category_code", length = 20)
    private String categoryCode;

    @Column(name = "change_type", length = 20)
    private String changeType;

    @Column(name = "cover_after", precision = 15, scale = 2)
    private BigDecimal coverAfter;

    @Column(name = "days_covered", nullable = false)
    private int daysCovered;

    @Column(name = "billed_before", nullable = false, precision = 15, scale = 2)
    private BigDecimal billedBefore;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal recalculated;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    protected AdjustmentLine() {
    }

    AdjustmentLine(SchemeAdjustment adjustment, Long originalLineId, SchemeMember member, String changeType,
            CoverHistory.Priced priced, BigDecimal billedBefore) {
        this.adjustment = adjustment;
        this.originalLineId = originalLineId;
        this.memberId = member.getId();
        this.memberKey = member.getMemberKey();
        this.memberName = member.fullName();
        this.categoryCode = priced.categoryCode();
        this.changeType = changeType;
        this.coverAfter = priced.cover();
        this.daysCovered = priced.daysCovered();
        this.billedBefore = billedBefore;
        this.recalculated = priced.premium();
        this.amount = priced.premium().subtract(billedBefore);
    }

    public Long getId() {
        return id;
    }

    public SchemeAdjustment getAdjustment() {
        return adjustment;
    }

    public Long getOriginalLineId() {
        return originalLineId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getMemberKey() {
        return memberKey;
    }

    public String getMemberName() {
        return memberName;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getChangeType() {
        return changeType;
    }

    public BigDecimal getCoverAfter() {
        return coverAfter;
    }

    public int getDaysCovered() {
        return daysCovered;
    }

    public BigDecimal getBilledBefore() {
        return billedBefore;
    }

    public BigDecimal getRecalculated() {
        return recalculated;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
