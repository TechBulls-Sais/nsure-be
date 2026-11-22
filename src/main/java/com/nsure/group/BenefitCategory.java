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

    /**
     * Days after first becoming eligible within which a member still gets free cover; a later entrant's cover is all
     * referred to underwriting (NS-GL03-003). Null: no late-entry rule is configured.
     */
    @Column(name = "late_entry_days")
    private Integer lateEntryDays;

    // ── approved factors, cessation, waiting period, cap and eligibility (NS-GL02-001) ──────────────

    /** The employment class the category covers, e.g. "Permanent staff". */
    @Column(name = "employment_class", length = 60)
    private String employmentClass;

    /** CLASS_1 (office) to CLASS_4 (heavy manual): the occupation class the rates assume. */
    @Column(name = "occupation_class", length = 20)
    private String occupationClass;

    /** The salary a multiple applies to: BASIC, PENSIONABLE or TOTAL_EARNINGS. */
    @Column(name = "salary_basis", length = 20)
    private String salaryBasis;

    /** Cover ceases at this age (benefit cessation, separate from scheme renewal); null: no cessation age set. */
    @Column(name = "exit_age")
    private Integer exitAge;

    /** Days from entry before a claim (other than for an accident) is admitted; null: none. */
    @Column(name = "waiting_days")
    private Integer waitingDays;

    /** The most cover any member of the category may have, whatever the formula gives; null: no cap. */
    @Column(name = "max_cover", precision = 15, scale = 2)
    private BigDecimal maxCover;

    @Column(name = "eligibility_rule", length = 300)
    private String eligibilityRule;

    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    /** The category's dated versions (AT-GL02-02); empty for categories set up before versions were kept. */
    @jakarta.persistence.OneToMany(mappedBy = "category", fetch = FetchType.LAZY)
    @jakarta.persistence.OrderBy("versionNo")
    private java.util.List<PlanRecords.CategoryVersion> versions = new java.util.ArrayList<>();

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

    void lateEntry(Integer days) {
        this.lateEntryDays = days;
    }

    void factors(String employmentClass, String occupationClass, String salaryBasis, Integer exitAge,
            Integer waitingDays, BigDecimal maxCover, String eligibilityRule) {
        this.employmentClass = employmentClass;
        this.occupationClass = occupationClass;
        this.salaryBasis = salaryBasis;
        this.exitAge = exitAge;
        this.waitingDays = waitingDays;
        this.maxCover = maxCover;
        this.eligibilityRule = eligibilityRule;
    }

    /** Records the terms as they now stand as a new version effective from the date. */
    PlanRecords.CategoryVersion snapshot(java.time.LocalDate effectiveFrom, String reason, String impact, String actor) {
        PlanRecords.CategoryVersion v = new PlanRecords.CategoryVersion();
        v.category = this;
        v.versionNo = versions.isEmpty() ? 1 : versions.get(versions.size() - 1).versionNo + 1;
        v.effectiveFrom = effectiveFrom;
        v.benefitBasis = benefitBasis;
        v.salaryMultiple = salaryMultiple;
        v.fixedCover = fixedCover;
        v.maxCover = maxCover;
        v.freeCoverLimit = freeCoverLimit;
        v.ratePerMille = ratePerMille;
        v.changeReason = reason;
        v.impact = impact == null || impact.length() <= 2000 ? impact : impact.substring(0, 1997) + "...";
        v.changedBy = actor;
        v.changedAt = LocalDateTime.now();
        versions.add(v);
        this.versionNo = v.versionNo;
        return v;
    }

    public java.util.List<PlanRecords.CategoryVersion> getVersions() {
        return versions;
    }

    /**
     * The cover the formula in force on {@code date} gives (AT-GL02-02): the latest version effective by then, the
     * earliest one for a date before any version, or the current terms when no versions are kept.
     */
    BigDecimal coverFor(BigDecimal salary, java.time.LocalDate date) {
        if (versions.isEmpty() || date == null) {
            return coverFor(salary);
        }
        PlanRecords.CategoryVersion chosen = versions.get(0);
        for (PlanRecords.CategoryVersion v : versions) {
            if (!v.effectiveFrom.isAfter(date)) {
                chosen = v;
            }
        }
        return chosen.coverFor(salary);
    }

    public String getEmploymentClass() {
        return employmentClass;
    }

    public String getOccupationClass() {
        return occupationClass;
    }

    public String getSalaryBasis() {
        return salaryBasis;
    }

    public Integer getExitAge() {
        return exitAge;
    }

    public Integer getWaitingDays() {
        return waitingDays;
    }

    public BigDecimal getMaxCover() {
        return maxCover;
    }

    public String getEligibilityRule() {
        return eligibilityRule;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public Integer getLateEntryDays() {
        return lateEntryDays;
    }

    boolean salaryBased() {
        return SALARY_MULTIPLE.equals(benefitBasis);
    }

    /**
     * The cover this category's formula gives for a salary: salary × multiple, or the fixed amount, capped at the
     * category's maximum cover when one is set.
     */
    BigDecimal coverFor(BigDecimal salary) {
        BigDecimal cover;
        if (salaryBased()) {
            cover = salary == null ? BigDecimal.ZERO.setScale(2)
                    : salary.multiply(salaryMultiple).setScale(2, RoundingMode.HALF_UP);
        } else {
            cover = fixedCover.setScale(2, RoundingMode.HALF_UP);
        }
        return maxCover != null && cover.compareTo(maxCover) > 0 ? maxCover.setScale(2, RoundingMode.HALF_UP) : cover;
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
