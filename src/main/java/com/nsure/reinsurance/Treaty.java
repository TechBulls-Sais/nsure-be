package com.nsure.reinsurance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A reinsurance treaty: quota share (a fixed percentage of every policy) or surplus (the sum assured above the
 * retention). Drafted by one user and approved by another; only an approved treaty cedes (AT-SH07-01).
 */
@Entity
@Table(name = "ri_treaty")
public class Treaty {

    public static final String QUOTA_SHARE = "QUOTA_SHARE";
    public static final String SURPLUS = "SURPLUS";
    /** A policy-specific arrangement made when a reinsurer accepts a facultative referral (NS-SH07-002). */
    public static final String FACULTATIVE = "FACULTATIVE";
    /** Non-proportional (NS-SH07-001): per-claim excess of loss above a priority, up to a limit; cedes no premium share. */
    public static final String EXCESS_OF_LOSS = "EXCESS_OF_LOSS";
    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 150)
    private String reinsurer;

    @Column(name = "treaty_type", nullable = false, length = 20)
    private String treatyType;

    // Product names, comma separated.
    @Column(nullable = false, length = 500)
    private String products;

    @Column(name = "ceded_percent", precision = 5, scale = 2)
    private BigDecimal cededPercent;

    @Column(precision = 15, scale = 2)
    private BigDecimal retention;

    // NS-SH07-002: the most ceded on one life (null = no limit); above it the excess is referred for facultative.
    @Column(precision = 15, scale = 2)
    private BigDecimal capacity;

    // The one policy a facultative arrangement covers; null for an automatic treaty.
    @Column(name = "policy_ref", length = 20)
    private String policyRef;

    // NS-SH07-001: version and the version it amends; reinsurance commission, premium basis and reporting terms; and
    // the excess-of-loss model (priority and limit per claim).
    @Column(nullable = false)
    private int version = 1;

    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "amends_id")
    private Treaty amends;

    @Column(name = "commission_percent", precision = 5, scale = 2)
    private BigDecimal commissionPercent;

    @Column(name = "premium_basis", length = 20)
    private String premiumBasis;

    @Column(name = "reporting_frequency", length = 20)
    private String reportingFrequency;

    @Column(name = "bordereau_format", length = 20)
    private String bordereauFormat;

    @Column(name = "xl_priority", precision = 15, scale = 2)
    private BigDecimal xlPriority;

    @Column(name = "xl_limit", precision = 15, scale = 2)
    private BigDecimal xlLimit;

    @Column(name = "amendment_reason", length = 500)
    private String amendmentReason;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to", nullable = false)
    private LocalDate effectiveTo;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    protected Treaty() {
    }

    Treaty(String name, String reinsurer, String treatyType, List<String> products, BigDecimal cededPercent,
            BigDecimal retention, LocalDate effectiveFrom, LocalDate effectiveTo, String actor) {
        this.ref = "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.name = name;
        this.reinsurer = reinsurer;
        this.treatyType = treatyType;
        this.products = String.join(",", products);
        this.cededPercent = cededPercent;
        this.retention = retention;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.status = DRAFT;
        this.createdBy = actor;
        this.createdAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("TRT-%05d", id);
    }

    void capacity(BigDecimal capacity) {
        this.capacity = capacity;
    }

    void terms(BigDecimal commissionPercent, String premiumBasis, String reportingFrequency, String bordereauFormat,
            BigDecimal xlPriority, BigDecimal xlLimit) {
        this.commissionPercent = commissionPercent;
        this.premiumBasis = premiumBasis;
        this.reportingFrequency = reportingFrequency;
        this.bordereauFormat = bordereauFormat;
        this.xlPriority = xlPriority;
        this.xlLimit = xlLimit;
    }

    void amendmentOf(Treaty original, String reason) {
        this.amends = original;
        this.version = original.version + 1;
        this.amendmentReason = reason;
    }

    /** An approved amendment takes over from {@code from}: this version stops the day before. */
    void endBefore(LocalDate from) {
        this.effectiveTo = from.minusDays(1);
    }

    /** Proportional treaties cede premium per policy; excess of loss recovers per claim only. */
    public boolean isProportional() {
        return QUOTA_SHARE.equals(treatyType) || SURPLUS.equals(treatyType) || FACULTATIVE.equals(treatyType);
    }

    /** Excess-of-loss recovery on one claim: the part above the priority, up to the limit. */
    public BigDecimal xlRecovery(BigDecimal claim) {
        if (!EXCESS_OF_LOSS.equals(treatyType) || xlPriority == null || xlLimit == null || claim == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return claim.subtract(xlPriority).max(BigDecimal.ZERO).min(xlLimit).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public int getVersion() {
        return version;
    }

    public Treaty getAmends() {
        return amends;
    }

    public BigDecimal getCommissionPercent() {
        return commissionPercent;
    }

    public String getPremiumBasis() {
        return premiumBasis;
    }

    public String getReportingFrequency() {
        return reportingFrequency;
    }

    public String getBordereauFormat() {
        return bordereauFormat;
    }

    public BigDecimal getXlPriority() {
        return xlPriority;
    }

    public BigDecimal getXlLimit() {
        return xlLimit;
    }

    public String getAmendmentReason() {
        return amendmentReason;
    }

    /** A facultative arrangement for one policy, approved when the reinsurer's acceptance is captured. */
    static Treaty facultative(String name, String reinsurer, String product, String policyRef, BigDecimal percent,
            LocalDate from, String offeredBy, String acceptedBy) {
        Treaty t = new Treaty(name, reinsurer, FACULTATIVE, List.of(product), percent, null, from,
                LocalDate.of(2099, 12, 31), offeredBy == null ? acceptedBy : offeredBy);
        t.policyRef = policyRef;
        t.approve(acceptedBy);
        return t;
    }

    /** Ceded automatically on every policy of its products (quota share or surplus), unlike facultative. */
    public boolean isAutomatic() {
        return !FACULTATIVE.equals(treatyType);
    }

    public BigDecimal getCapacity() {
        return capacity;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    void approve(String actor) {
        this.status = APPROVED;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    public List<String> productList() {
        return Arrays.stream(products.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    public boolean covers(String product) {
        return product != null && productList().stream().anyMatch(p -> p.equalsIgnoreCase(product.trim()));
    }

    public boolean inForceOn(LocalDate day) {
        return !effectiveFrom.isAfter(day) && !effectiveTo.isBefore(day);
    }

    /** Same product and overlapping dates (automatic treaties only; facultative ones cover a single policy). */
    boolean overlaps(Treaty other) {
        if (!isAutomatic() || !other.isAutomatic()) {
            return false;
        }
        // A proportional treaty and an excess-of-loss cover may sit on the same product; two of a kind may not.
        if (isProportional() != other.isProportional()) {
            return false;
        }
        boolean dates = !effectiveFrom.isAfter(other.getEffectiveTo()) && !other.getEffectiveFrom().isAfter(effectiveTo);
        return dates && productList().stream().anyMatch(other::covers);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getName() {
        return name;
    }

    public String getReinsurer() {
        return reinsurer;
    }

    public String getTreatyType() {
        return treatyType;
    }

    public String getProducts() {
        return products;
    }

    public BigDecimal getCededPercent() {
        return cededPercent;
    }

    public BigDecimal getRetention() {
        return retention;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }
}
