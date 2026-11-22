package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;

/**
 * A group scheme (GL01). The members and premium columns are a denormalised snapshot that com.nsure.group keeps in
 * step whenever membership or cover changes, so the portfolio dashboard stays coherent with live membership.
 */
@Entity
@Table(name = "ins_scheme")
public class GroupScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking (NS-TXN-001): a write based on a stale copy is refused with 409, never overwrites.
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 80)
    private String sector;

    @Column(nullable = false, length = 8)
    private String initials;

    @Column(nullable = false)
    private int members;

    @Column(nullable = false)
    private int premium;

    @Column(nullable = false, length = 20)
    private String renewal;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "inception_date", nullable = false)
    private LocalDate inceptionDate;

    @Column(name = "renewal_date", nullable = false)
    private LocalDate renewalDate;

    @Column(name = "billing_frequency", nullable = false, length = 20)
    private String billingFrequency;

    @Column(name = "scheme_status", nullable = false, length = 20)
    private String status;

    protected GroupScheme() {
    }

    public GroupScheme(String ref, String name, String sector, String initials, String color, int displayOrder,
            LocalDate inceptionDate, LocalDate renewalDate, String renewal, String billingFrequency) {
        this.ref = ref;
        this.name = name;
        this.sector = sector;
        this.initials = initials;
        this.color = color;
        this.displayOrder = displayOrder;
        this.inceptionDate = inceptionDate;
        this.renewalDate = renewalDate;
        this.renewal = renewal;
        this.billingFrequency = billingFrequency;
        // NS-GL01-004: a new master policy is in set-up, with no cover, until it is activated from accepted terms.
        this.status = "SETUP";
    }

    /** Refreshes the dashboard snapshot from live membership: active members and whole-pula monthly premium. */
    public void syncTotals(int members, int premium) {
        this.members = members;
        this.premium = premium;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getInceptionDate() {
        return inceptionDate;
    }

    public LocalDate getRenewalDate() {
        return renewalDate;
    }

    public String getBillingFrequency() {
        return billingFrequency;
    }

    public String getStatus() {
        return status;
    }

    public String getRef() {
        return ref;
    }

    public String getName() {
        return name;
    }

    public String getSector() {
        return sector;
    }

    public String getInitials() {
        return initials;
    }

    public int getMembers() {
        return members;
    }

    public int getPremium() {
        return premium;
    }

    public String getRenewal() {
        return renewal;
    }

    public String getColor() {
        return color;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    /** The contract year: 1 at inception, one more at each renewal (NS-GL05-005). */
    @Column(name = "policy_year", nullable = false)
    private int policyYear = 1;

    @Column(name = "quote_ref", length = 20)
    private String quoteRef;

    public int getPolicyYear() {
        return policyYear;
    }

    public String getQuoteRef() {
        return quoteRef;
    }

    public void setQuoteRef(String quoteRef) {
        this.quoteRef = quoteRef;
    }

    /**
     * SETUP, ACTIVE, SUSPENDED or CLOSED. A scheme is never deleted once it has members or money (NS-GL01-006), and
     * becomes ACTIVE only through {@link #activate} (NS-GL01-004).
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /** Moves the scheme into its next policy year, with no gap or overlap (NS-GL05-006). */
    public void renewTo(LocalDate nextRenewal, String label) {
        this.renewalDate = nextRenewal;
        this.renewal = label;
        this.policyYear = this.policyYear + 1;
    }

    // ── set-up terms (NS-GL01-003) and activation (NS-GL01-004) ─────────────────────────────────────

    @Column(name = "sponsor_ref", length = 20)
    private String sponsorRef;

    @Column(name = "payer_name", length = 150)
    private String payerName;

    @Column(name = "broker_ref", length = 30)
    private String brokerRef;

    @Column(name = "billing_method", length = 20)
    private String billingMethod;

    @Column(name = "billing_due_day")
    private Integer billingDueDay;

    /** What an inactive-at-work response does (AT-GL03-02): REFER all cover to underwriting, or DEFER cover. */
    @Column(name = "aaw_rule", nullable = false, length = 10)
    private String aawRule = "REFER";

    /** YES: cover up to the free-cover limit is admitted while the excess is underwritten; NO: all of it waits. */
    @Column(name = "partial_cover", nullable = false, length = 3)
    private String partialCover = "YES";

    @Column(name = "wording_ref", length = 20)
    private String wordingRef;

    @Column(name = "wording_version")
    private Integer wordingVersion;

    @Column(name = "inception_conditions", length = 1000)
    private String inceptionConditions;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "activated_at")
    private java.time.LocalDateTime activatedAt;

    /** Records the master policy's set-up terms; checked by the caller (legal sponsor, payer, broker, billing, rules). */
    public void setUpTerms(String sponsorRef, String payerName, String brokerRef, String billingMethod,
            Integer billingDueDay, String aawRule, boolean partialCoverAllowed) {
        this.sponsorRef = sponsorRef;
        this.payerName = payerName;
        this.brokerRef = brokerRef;
        this.billingMethod = billingMethod;
        this.billingDueDay = billingDueDay;
        this.aawRule = aawRule;
        this.partialCover = partialCoverAllowed ? "YES" : "NO";
    }

    /** Puts the master policy in force on the approved wording version and the inception conditions met. */
    public void activate(String quoteRef, String wordingRef, int wordingVersion, String inceptionConditions,
            String actor) {
        if (!"SETUP".equals(status)) {
            throw new IllegalStateException("Scheme " + ref + " is " + status + ", not in set-up");
        }
        this.quoteRef = quoteRef;
        this.wordingRef = wordingRef;
        this.wordingVersion = wordingVersion;
        this.inceptionConditions = inceptionConditions;
        this.activatedBy = actor;
        this.activatedAt = java.time.LocalDateTime.now();
        this.status = "ACTIVE";
    }

    public String getSponsorRef() {
        return sponsorRef;
    }

    public String getPayerName() {
        return payerName;
    }

    public String getBrokerRef() {
        return brokerRef;
    }

    public String getBillingMethod() {
        return billingMethod;
    }

    public Integer getBillingDueDay() {
        return billingDueDay;
    }

    public String getAawRule() {
        return aawRule;
    }

    public boolean isPartialCoverAllowed() {
        return !"NO".equals(partialCover);
    }

    public String getWordingRef() {
        return wordingRef;
    }

    public Integer getWordingVersion() {
        return wordingVersion;
    }

    public String getInceptionConditions() {
        return inceptionConditions;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public java.time.LocalDateTime getActivatedAt() {
        return activatedAt;
    }
}
