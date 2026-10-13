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

    @Column(nullable = false, unique = true, length = 20)
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
}
