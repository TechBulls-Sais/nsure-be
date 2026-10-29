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

    /** Same product and overlapping dates. */
    boolean overlaps(Treaty other) {
        boolean dates = !effectiveFrom.isAfter(other.effectiveTo) && !other.effectiveFrom.isAfter(effectiveTo);
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
