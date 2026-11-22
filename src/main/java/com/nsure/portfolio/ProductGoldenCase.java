package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One golden case of a product configuration (NS-SH03-006): a life's date of birth, the date it is priced on and the
 * premium an actuary expects, or none when the configuration must refuse it (outside entry ages, no rate). A proposed
 * case counts only once someone other than its proposer approves it; a changed expected value is a new baseline that
 * supersedes the old one, so a calculation change can never quietly rewrite what was validated.
 */
@Entity
@Table(name = "ins_product_golden_case")
public class ProductGoldenCase {

    public static final String PROPOSED = "PROPOSED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_ref", nullable = false, length = 20, updatable = false)
    private String configRef;

    @Column(nullable = false, length = 120, updatable = false)
    private String label;

    @Column(name = "date_of_birth", nullable = false, updatable = false)
    private LocalDate dateOfBirth;

    @Column(name = "priced_on", nullable = false, updatable = false)
    private LocalDate pricedOn;

    @Column(name = "expected_premium", precision = 15, scale = 2, updatable = false)
    private BigDecimal expectedPremium;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "baseline_no", nullable = false, updatable = false)
    private int baselineNo;

    @Column(name = "supersedes_id", updatable = false)
    private Long supersedesId;

    @Column(length = 300, updatable = false)
    private String reason;

    @Column(name = "proposed_by", nullable = false, length = 100, updatable = false)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false, updatable = false)
    private LocalDateTime proposedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "last_result", length = 10)
    private String lastResult;

    @Column(name = "last_actual", length = 200)
    private String lastActual;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    protected ProductGoldenCase() {
    }

    ProductGoldenCase(String configRef, String label, LocalDate dateOfBirth, LocalDate pricedOn,
            BigDecimal expectedPremium, int baselineNo, Long supersedesId, String reason, String proposedBy) {
        this.configRef = configRef;
        this.label = label;
        this.dateOfBirth = dateOfBirth;
        this.pricedOn = pricedOn;
        this.expectedPremium = expectedPremium;
        this.baselineNo = baselineNo;
        this.supersedesId = supersedesId;
        this.reason = reason;
        this.proposedBy = proposedBy;
        this.proposedAt = LocalDateTime.now();
        this.status = PROPOSED;
    }

    void approve(String actor) {
        this.status = APPROVED;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void reject(String actor) {
        this.status = REJECTED;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void supersede() {
        this.status = SUPERSEDED;
    }

    void ran(String result, String actual) {
        this.lastResult = result;
        this.lastActual = actual == null || actual.length() <= 200 ? actual : actual.substring(0, 199) + "…";
        this.lastRunAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getConfigRef() {
        return configRef;
    }

    public String getLabel() {
        return label;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public LocalDate getPricedOn() {
        return pricedOn;
    }

    public BigDecimal getExpectedPremium() {
        return expectedPremium;
    }

    public String getStatus() {
        return status;
    }

    public int getBaselineNo() {
        return baselineNo;
    }

    public Long getSupersedesId() {
        return supersedesId;
    }

    public String getReason() {
        return reason;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public LocalDateTime getProposedAt() {
        return proposedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getLastResult() {
        return lastResult;
    }

    public String getLastActual() {
        return lastActual;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }
}
