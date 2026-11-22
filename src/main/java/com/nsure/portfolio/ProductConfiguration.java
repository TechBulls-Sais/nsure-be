package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_product_config")
public class ProductConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 40)
    private String domain;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "effective_on", nullable = false, length = 20)
    private String effectiveOn;

    @Column(name = "min_age", nullable = false)
    private int minAge;

    @Column(name = "max_age", nullable = false)
    private int maxAge;

    @Column(name = "waiting_months", nullable = false)
    private int waitingMonths;

    @Column(name = "cover_amount", nullable = false)
    private int coverAmount;

    @Column(name = "premium_amount", nullable = false)
    private int premiumAmount;

    @Column(nullable = false, length = 20)
    private String frequency;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    @Column(nullable = false, length = 40)
    private String underwriting;

    @Column(nullable = false, length = 500)
    private String benefits;

    @Column(nullable = false, length = 300)
    private String channels;

    @Column(nullable = false, length = 200)
    private String wording;

    @Column(nullable = false)
    private boolean approvals;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    // ── release (SH02): where it is sold, typed rules, dependencies, approval and publication ──

    @Column(name = "catalogue_product", length = 100)
    private String catalogueProduct;

    @Column(name = "plan_name", length = 60)
    private String planName;

    @Column(name = "rules", length = 4000)
    private String rules;

    @Column(name = "rate_table_ref", length = 20)
    private String rateTableRef;

    @Column(name = "manifest", length = 4000)
    private String manifest;

    @Column(name = "manifest_checksum", length = 64)
    private String manifestChecksum;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private java.time.LocalDateTime approvedAt;

    @Column(name = "released_by", length = 100)
    private String releasedBy;

    @Column(name = "released_at")
    private java.time.LocalDateTime releasedAt;

    @Column(name = "withdrawn_by", length = 100)
    private String withdrawnBy;

    @Column(name = "withdrawn_at")
    private java.time.LocalDateTime withdrawnAt;

    @Column(name = "release_note", length = 500)
    private String releaseNote;

    @Column(name = "published_tier_id")
    private Long publishedTierId;

    @Column(name = "product_version_id")
    private Long productVersionId;

    @Column(name = "imported_from", length = 200)
    private String importedFrom;

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEffectiveOn() {
        return effectiveOn;
    }

    public void setEffectiveOn(String effectiveOn) {
        this.effectiveOn = effectiveOn;
    }

    public int getMinAge() {
        return minAge;
    }

    public void setMinAge(int minAge) {
        this.minAge = minAge;
    }

    public int getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(int maxAge) {
        this.maxAge = maxAge;
    }

    public int getWaitingMonths() {
        return waitingMonths;
    }

    public void setWaitingMonths(int waitingMonths) {
        this.waitingMonths = waitingMonths;
    }

    public int getCoverAmount() {
        return coverAmount;
    }

    public void setCoverAmount(int coverAmount) {
        this.coverAmount = coverAmount;
    }

    public int getPremiumAmount() {
        return premiumAmount;
    }

    public void setPremiumAmount(int premiumAmount) {
        this.premiumAmount = premiumAmount;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public int getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(int graceDays) {
        this.graceDays = graceDays;
    }

    public String getUnderwriting() {
        return underwriting;
    }

    public void setUnderwriting(String underwriting) {
        this.underwriting = underwriting;
    }

    public String getBenefits() {
        return benefits;
    }

    public void setBenefits(String benefits) {
        this.benefits = benefits;
    }

    public String getChannels() {
        return channels;
    }

    public void setChannels(String channels) {
        this.channels = channels;
    }

    public String getWording() {
        return wording;
    }

    public void setWording(String wording) {
        this.wording = wording;
    }

    public boolean isApprovals() {
        return approvals;
    }

    public void setApprovals(boolean approvals) {
        this.approvals = approvals;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(int versionNo) {
        this.versionNo = versionNo;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getCatalogueProduct() {
        return catalogueProduct;
    }

    public void setCatalogueProduct(String catalogueProduct) {
        this.catalogueProduct = catalogueProduct;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public String getRules() {
        return rules;
    }

    public void setRules(String rules) {
        this.rules = rules;
    }

    public String getRateTableRef() {
        return rateTableRef;
    }

    public void setRateTableRef(String rateTableRef) {
        this.rateTableRef = rateTableRef;
    }

    public String getManifest() {
        return manifest;
    }

    public void setManifest(String manifest) {
        this.manifest = manifest;
    }

    public String getManifestChecksum() {
        return manifestChecksum;
    }

    public void setManifestChecksum(String manifestChecksum) {
        this.manifestChecksum = manifestChecksum;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public java.time.LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(java.time.LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public void setReleasedBy(String releasedBy) {
        this.releasedBy = releasedBy;
    }

    public java.time.LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(java.time.LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getWithdrawnBy() {
        return withdrawnBy;
    }

    public void setWithdrawnBy(String withdrawnBy) {
        this.withdrawnBy = withdrawnBy;
    }

    public java.time.LocalDateTime getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(java.time.LocalDateTime withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }

    public String getReleaseNote() {
        return releaseNote;
    }

    public void setReleaseNote(String releaseNote) {
        this.releaseNote = releaseNote;
    }

    public Long getPublishedTierId() {
        return publishedTierId;
    }

    public void setPublishedTierId(Long publishedTierId) {
        this.publishedTierId = publishedTierId;
    }

    public Long getProductVersionId() {
        return productVersionId;
    }

    public void setProductVersionId(Long productVersionId) {
        this.productVersionId = productVersionId;
    }

    public String getImportedFrom() {
        return importedFrom;
    }

    public void setImportedFrom(String importedFrom) {
        this.importedFrom = importedFrom;
    }
}
