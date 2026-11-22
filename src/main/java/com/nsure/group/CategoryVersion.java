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
 * A category's terms for one scheme year (NS-GL05-005): the rate and free-cover limit in force from {@code effectiveFrom}
 * to {@code effectiveTo}. A renewal closes the expiring version and opens the next year's, so an invoice for a month
 * before the renewal date keeps the expiring rate even when the renewal was agreed early.
 */
@Entity
@Table(name = "grp_category_version")
public class CategoryVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private BenefitCategory category;

    @Column(name = "policy_year", nullable = false)
    private int policyYear;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

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

    @Column(name = "source_ref", nullable = false, length = 40)
    private String sourceRef;

    /** The contractual cessation age for the year (members reaching it leave cover); null uses the demo default. */
    @Column(name = "cessation_age")
    private Integer cessationAge;

    void cessationAge(Integer age) {
        this.cessationAge = age;
    }

    public Integer getCessationAge() {
        return cessationAge;
    }

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CategoryVersion() {
    }

    CategoryVersion(BenefitCategory category, int policyYear, LocalDate effectiveFrom, BigDecimal ratePerMille,
            BigDecimal freeCoverLimit, String sourceRef, String createdBy) {
        this.category = category;
        this.policyYear = policyYear;
        this.effectiveFrom = effectiveFrom;
        this.benefitBasis = category.getBenefitBasis();
        this.salaryMultiple = category.getSalaryMultiple();
        this.fixedCover = category.getFixedCover();
        this.freeCoverLimit = freeCoverLimit;
        this.ratePerMille = ratePerMille;
        this.sourceRef = sourceRef;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    void closeOn(LocalDate lastDay) {
        this.effectiveTo = lastDay;
    }

    boolean covers(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public Long getId() {
        return id;
    }

    public BenefitCategory getCategory() {
        return category;
    }

    public int getPolicyYear() {
        return policyYear;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
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

    public String getSourceRef() {
        return sourceRef;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
