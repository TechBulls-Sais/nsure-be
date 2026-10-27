package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ins_policy")
public class InsurancePolicy extends CoverRecord implements TieredCover {

    @Column(length = 60)
    private String tier;

    @Column(name = "rate_table_version", length = 30)
    private String rateTableVersion;

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
}
