package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ins_policy")
public class InsurancePolicy extends CoverRecord implements TieredCover {

    // The insurer-approved form this policy was issued under, and its values for that form's extra fields (JSON).
    // A later form version never re-validates an existing policy (guide §15).
    @Column(name = "form_version_id")
    private Long formVersionId;

    @Column(name = "custom_attributes", length = 4000)
    private String customAttributes;

    // Optimistic locking: a write based on a stale copy fails with 409 instead of overwriting another change.
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(length = 60)
    private String tier;

    @Column(name = "rate_table_version", length = 30)
    private String rateTableVersion;

    // NS-IL01-006: the application this policy was issued from (issued once), and the key that makes staff issue
    // idempotent.
    @Column(name = "payer_name", length = 100)
    private String payerName;

    @Column(name = "payer_relationship", length = 60)
    private String payerRelationship;

    @Column(name = "premium_frequency", nullable = false, length = 20)
    private String premiumFrequency = "MONTHLY";

    /** Why the contract ended (NS-IL03): DEATH, CANCELLATION, EXPIRY, LAPSE, SURRENDER, MATURITY or OTHER. */
    @Column(name = "termination_reason", length = 30)
    private String terminationReason;

    @Column(name = "terminated_on")
    private LocalDate terminatedOn;

    public String getPayerName() {
        return payerName;
    }

    public String getPayerRelationship() {
        return payerRelationship;
    }

    public void setPayer(String name, String relationship) {
        this.payerName = name;
        this.payerRelationship = relationship;
    }

    public String getPremiumFrequency() {
        return premiumFrequency;
    }

    public void setPremiumFrequency(String premiumFrequency) {
        this.premiumFrequency = premiumFrequency;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public LocalDate getTerminatedOn() {
        return terminatedOn;
    }

    /** Records why and when the contract ended. */
    public void terminated(String reason, LocalDate on) {
        this.terminationReason = reason;
        this.terminatedOn = on;
    }

    /** Terms underwriting attached at issue: loading and exclusions (NS-IL02-005). */
    @Column(name = "uw_terms", length = 1000)
    private String uwTerms;

    public String getUwTerms() {
        return uwTerms;
    }

    public void setUwTerms(String uwTerms) {
        this.uwTerms = uwTerms;
    }

    @Column(name = "application_ref", length = 20, unique = true)
    private String applicationRef;

    @Column(name = "issue_key", length = 80, unique = true)
    private String issueKey;

    // Who last decided this policy's state by hand (cancelled it, or recorded its payment), when, and why.
    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    // Cover dates, set when the first premium is collected and moved on by every later one.
    @Column(name = "cover_start_date")
    private LocalDate coverStartDate;

    /** The selected policy term in years, where the product sells terms (section 06 Term Life). */
    @Column(name = "term_years")
    private Integer termYears;

    /** Cover ends here: the selected term or the cessation age, whichever is earlier. */
    @Column(name = "cover_end_date")
    private LocalDate coverEndDate;

    @Column(name = "next_premium_due")
    private LocalDate nextPremiumDue;

    @Column(name = "grace_period_end")
    private LocalDate gracePeriodEnd;

    // A Pending Payment policy expires once this passes; null holds it open (no deadline).
    @Column(name = "payment_due_by")
    private LocalDateTime paymentDueBy;

    @Column(name = "lapse_date")
    private LocalDate lapseDate;

    @Column(name = "payment_method", length = 40)
    private String paymentMethod;

    @Override
    public String getTier() {
        return tier;
    }

    @Override
    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getApplicationRef() {
        return applicationRef;
    }

    public void setApplicationRef(String applicationRef) {
        this.applicationRef = applicationRef;
    }

    public String getIssueKey() {
        return issueKey;
    }

    public void setIssueKey(String issueKey) {
        this.issueKey = issueKey;
    }

    @Override
    public String getRateTableVersion() {
        return rateTableVersion;
    }

    @Override
    public void setRateTableVersion(String rateTableVersion) {
        this.rateTableVersion = rateTableVersion;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void review(String reviewer, String note) {
        this.reviewedBy = reviewer;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNote = note;
    }

    public LocalDate getCoverStartDate() {
        return coverStartDate;
    }

    public Integer getTermYears() {
        return termYears;
    }

    public void setTermYears(Integer termYears) {
        this.termYears = termYears;
    }

    public LocalDate getCoverEndDate() {
        return coverEndDate;
    }

    public void setCoverEndDate(LocalDate coverEndDate) {
        this.coverEndDate = coverEndDate;
    }

    public void setCoverStartDate(LocalDate coverStartDate) {
        this.coverStartDate = coverStartDate;
    }

    public LocalDate getNextPremiumDue() {
        return nextPremiumDue;
    }

    public void setNextPremiumDue(LocalDate nextPremiumDue) {
        this.nextPremiumDue = nextPremiumDue;
    }

    public LocalDate getGracePeriodEnd() {
        return gracePeriodEnd;
    }

    public void setGracePeriodEnd(LocalDate gracePeriodEnd) {
        this.gracePeriodEnd = gracePeriodEnd;
    }

    public LocalDateTime getPaymentDueBy() {
        return paymentDueBy;
    }

    public void setPaymentDueBy(LocalDateTime paymentDueBy) {
        this.paymentDueBy = paymentDueBy;
    }

    public LocalDate getLapseDate() {
        return lapseDate;
    }

    public void setLapseDate(LocalDate lapseDate) {
        this.lapseDate = lapseDate;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public Long getFormVersionId() {
        return formVersionId;
    }

    public void setFormVersionId(Long formVersionId) {
        this.formVersionId = formVersionId;
    }

    public String getCustomAttributes() {
        return customAttributes;
    }

    public void setCustomAttributes(String customAttributes) {
        this.customAttributes = customAttributes;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    // The accepted quote this record was priced from (NS-IL01-001, NS-SH03-003).
    @jakarta.persistence.Column(name = "quote_ref", length = 20)
    private String quoteRef;

    @jakarta.persistence.Column(name = "quote_version")
    private Integer quoteVersion;

    public String getQuoteRef() {
        return quoteRef;
    }

    public Integer getQuoteVersion() {
        return quoteVersion;
    }

    public void setQuote(String quoteRef, Integer quoteVersion) {
        this.quoteRef = quoteRef;
        this.quoteVersion = quoteVersion;
    }

    // The product terms version this policy was issued under (NS-SH02-004, NS-IL03-001); set once, never changed.
    @jakarta.persistence.Column(name = "product_version_id")
    private Long productVersionId;

    public Long getProductVersionId() {
        return productVersionId;
    }

    void pinProductVersion(Long id) {
        if (this.productVersionId != null && !this.productVersionId.equals(id)) {
            throw new IllegalStateException("Policy " + getRef() + " is already pinned to product terms "
                    + this.productVersionId + ".");
        }
        this.productVersionId = id;
    }

    /** Moves an in-force policy to newer terms; only an approved terms migration calls this (NS-SH02-004). */
    void migrateProductVersion(Long id) {
        this.productVersionId = id;
    }
}
