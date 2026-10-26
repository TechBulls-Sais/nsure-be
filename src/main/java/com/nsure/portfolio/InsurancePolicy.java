package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A policy. Its status is the workflow state (PolicyWorkflow): only the workflow writes it once it exists. */
@Entity
@Table(name = "ins_policy")
public class InsurancePolicy extends CoverRecord {

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

    // Who last changed the state by hand (a cancellation) and why; the customer sees the note.
    @Column(name = "status_changed_by", length = 100)
    private String statusChangedBy;

    @Column(name = "status_note", length = 500)
    private String statusNote;

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

    public String getStatusChangedBy() {
        return statusChangedBy;
    }

    public String getStatusNote() {
        return statusNote;
    }

    public void noteStatusChange(String actor, String note) {
        this.statusChangedBy = actor;
        this.statusNote = note;
    }
}
