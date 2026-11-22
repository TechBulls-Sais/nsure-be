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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Plan records (GL02): product benefit rules, versioned scheme/category benefit terms and dated category versions. */
public final class PlanRecords {

    private PlanRecords() {
    }

    /**
     * The product's rule for one benefit parameter: its default, bounds or allowed values, and whether a scheme or
     * category may override it (NS-GL02-002). Seeded with the section 08 source values and labelled demo defaults.
     */
    @Entity(name = "GroupProductRule")
    @Table(name = "grp_product_rule")
    public static class ProductRule {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "benefit_type", nullable = false, length = 30)
        String benefitType;
        @Column(name = "param_code", nullable = false, length = 40)
        String parameter;
        @Column(nullable = false, length = 150)
        String label;
        /** NUMBER, AMOUNT, CHOICE or TEXT. */
        @Column(name = "value_type", nullable = false, length = 20)
        String valueType;
        @Column(name = "default_value", length = 100)
        String defaultValue;
        @Column(name = "min_value", length = 30)
        String minValue;
        @Column(name = "max_value", length = 30)
        String maxValue;
        @Column(name = "allowed_values", length = 200)
        String allowedValues;
        @Column(nullable = false, length = 3)
        String overridable;
        @Column(nullable = false, length = 200)
        String source;
        @Column(name = "display_order", nullable = false)
        int displayOrder;

        boolean canOverride() {
            return "YES".equals(overridable);
        }
    }

    /**
     * One version of a scheme's terms for a benefit, at scheme level ({@code categoryCode} null) or for one category:
     * whether the benefit is included and its parameter values. PENDING until a second person approves it; it then
     * applies from its effective date (NS-GL02-006, GL02 version approval).
     */
    @Entity(name = "GroupBenefitTerms")
    @Table(name = "grp_benefit_terms")
    public static class BenefitTerms {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "benefit_type", nullable = false, length = 30)
        String benefitType;
        @Column(name = "category_code", length = 20)
        String categoryCode;
        @Column(name = "version_no", nullable = false)
        int versionNo;
        @Column(nullable = false, length = 3)
        String included;
        @JdbcTypeCode(SqlTypes.LONGVARCHAR)
        @Column(name = "parameters")
        String parameters;
        @Column(name = "effective_from", nullable = false)
        LocalDate effectiveFrom;
        @Column(name = "approval_status", nullable = false, length = 20)
        String approvalStatus;
        @Column(name = "change_reason", length = 500)
        String changeReason;
        @JdbcTypeCode(SqlTypes.LONGVARCHAR)
        @Column(name = "impact")
        String impact;
        @Column(name = "updated_by", nullable = false, length = 100)
        String updatedBy;
        @Column(name = "updated_at", nullable = false)
        LocalDateTime updatedAt;
        @Column(name = "approved_by", length = 100)
        String approvedBy;
        @Column(name = "approved_at")
        LocalDateTime approvedAt;
    }

    /**
     * A category's cover terms from a date (GL02 workspace, AT-GL02-02): a change made later never rewrites the cover
     * of a join or salary change dated before it, which uses the version in force on its effective date.
     */
    @Entity(name = "GroupCategoryVersion")
    @Table(name = "grp_category_version")
    public static class CategoryVersion {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "category_id", nullable = false)
        BenefitCategory category;
        @Column(name = "version_no", nullable = false)
        int versionNo;
        @Column(name = "effective_from", nullable = false)
        LocalDate effectiveFrom;
        @Column(name = "benefit_basis", nullable = false, length = 20)
        String benefitBasis;
        @Column(name = "salary_multiple", precision = 9, scale = 4)
        BigDecimal salaryMultiple;
        @Column(name = "fixed_cover", precision = 15, scale = 2)
        BigDecimal fixedCover;
        @Column(name = "max_cover", precision = 15, scale = 2)
        BigDecimal maxCover;
        @Column(name = "free_cover_limit", nullable = false, precision = 15, scale = 2)
        BigDecimal freeCoverLimit;
        @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
        BigDecimal ratePerMille;
        @Column(name = "change_reason", length = 500)
        String changeReason;
        @Column(length = 2000)
        String impact;
        @Column(name = "changed_by", nullable = false, length = 100)
        String changedBy;
        @Column(name = "changed_at", nullable = false)
        LocalDateTime changedAt;

        /** Cover under this version's formula: salary × multiple or the fixed amount, capped at the category maximum. */
        BigDecimal coverFor(BigDecimal salary) {
            BigDecimal cover = BenefitCategory.SALARY_MULTIPLE.equals(benefitBasis)
                    ? (salary == null ? BigDecimal.ZERO : salary.multiply(salaryMultiple))
                    : fixedCover;
            cover = cover.setScale(2, java.math.RoundingMode.HALF_UP);
            return maxCover != null && cover.compareTo(maxCover) > 0 ? maxCover : cover;
        }
    }
}
