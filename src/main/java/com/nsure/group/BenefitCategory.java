package com.nsure.group;

import com.nsure.portfolio.GroupScheme;
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
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * A scheme's benefit category (GL02): how cover is set (a salary multiple or a fixed amount), the free-cover limit
 * above which cover needs underwriting, the monthly rate per 1000 of cover and the entry ages. Amendments apply
 * prospectively: members keep the cover recorded on their own episode.
 */
@Entity
@Table(name = "grp_benefit_category")
public class BenefitCategory {

    public static final String SALARY_MULTIPLE = "SALARY_MULTIPLE";
    public static final String FIXED = "FIXED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "category_code", nullable = false, length = 20)
    private String code;

    @Column(name = "category_name", nullable = false, length = 100)
    private String name;

    @Column(name = "benefit_basis", nullable = false, length = 20)
    private String benefitBasis;

    @Column(name = "salary_multiple", precision = 9, scale = 4)
    private BigDecimal salaryMultiple;

    @Column(name = "fixed_cover", precision = 15, scale = 2)
    private BigDecimal fixedCover;

    @Column(name = "free_cover_limit", nullable = false, precision = 15, scale = 2)
    private BigDecimal freeCoverLimit;

    @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
    private BigDecimal ratePerMille;

    @Column(name = "min_entry_age", nullable = false)
    private int minEntryAge;

    @Column(name = "max_entry_age", nullable = false)
    private int maxEntryAge;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected BenefitCategory() {
    }

    BenefitCategory(GroupScheme scheme, String code) {
        this.scheme = scheme;
        this.code = code;
    }

    void define(String name, String benefitBasis, BigDecimal salaryMultiple, BigDecimal fixedCover,
            BigDecimal freeCoverLimit, BigDecimal ratePerMille, int minEntryAge, int maxEntryAge, String actor) {
        this.name = name;
        this.benefitBasis = benefitBasis;
        this.salaryMultiple = SALARY_MULTIPLE.equals(benefitBasis) ? salaryMultiple : null;
        this.fixedCover = FIXED.equals(benefitBasis) ? fixedCover : null;
        this.freeCoverLimit = freeCoverLimit;
        this.ratePerMille = ratePerMille;
        this.minEntryAge = minEntryAge;
        this.maxEntryAge = maxEntryAge;
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
    }

    boolean salaryBased() {
        return SALARY_MULTIPLE.equals(benefitBasis);
    }

    /** The cover this category's formula gives for a salary: salary × multiple, or the fixed amount. */
    BigDecimal coverFor(BigDecimal salary) {
        if (salaryBased()) {
            return salary == null ? BigDecimal.ZERO.setScale(2)
                    : salary.multiply(salaryMultiple).setScale(2, RoundingMode.HALF_UP);
        }
        return fixedCover.setScale(2, RoundingMode.HALF_UP);
    }

    /** A full month's premium for an amount of cover: cover / 1000 × rate per mille. */
    BigDecimal monthlyPremium(BigDecimal cover) {
        return cover.multiply(ratePerMille).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public GroupScheme getScheme() {
        return scheme;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getBenefitBasis() {
        return benefitBasis;
    }

    public BigDecimal getSalaryMultiple() {
        return salaryMultiple;
    }

    public BigDecimal getFixedCover() {
        return fixedCover;
    }

    public BigDecimal getFreeCoverLimit() {
        return freeCoverLimit;
    }

    public BigDecimal getRatePerMille() {
        return ratePerMille;
    }

    public int getMinEntryAge() {
        return minEntryAge;
    }

    public int getMaxEntryAge() {
        return maxEntryAge;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
