package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A policy serviced by an intermediary from a date. A newer assignment for the same policy ends this one (portfolio
 * transfer, AT-SH06-03); the ended row stays as history.
 */
@Entity
@Table(name = "dist_policy_assignment")
public class PolicyAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    // The policy ref while open, null once ended; unique, so a policy has at most one open assignment.
    @Column(name = "open_policy_ref", unique = true, length = 20)
    private String openPolicyRef;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intermediary_id", nullable = false)
    private Intermediary intermediary;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    @Column(name = "assigned_by", nullable = false, length = 100)
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    /** A transfer waits for a second person before it ends the current assignment (AT-SH06-03). */
    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";

    @Column(nullable = false, length = 20)
    private String status = APPLIED;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "transfer_reason", length = 500)
    private String transferReason;

    protected PolicyAssignment() {
    }

    /** A requested transfer: not open and not in force until approved. */
    static PolicyAssignment pendingTransfer(String policyRef, Intermediary to, LocalDate from, String reason,
            String actor) {
        PolicyAssignment row = new PolicyAssignment(policyRef, to, from, actor);
        row.openPolicyRef = null;
        row.status = PENDING;
        row.transferReason = reason;
        return row;
    }

    void approve(String actor) {
        this.status = APPLIED;
        this.openPolicyRef = policyRef;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void reject(String actor) {
        this.status = REJECTED;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    public String getStatus() {
        return status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getTransferReason() {
        return transferReason;
    }

    PolicyAssignment(String policyRef, Intermediary intermediary, LocalDate effectiveFrom, String actor) {
        this.policyRef = policyRef;
        this.openPolicyRef = policyRef;
        this.intermediary = intermediary;
        this.effectiveFrom = effectiveFrom;
        this.assignedBy = actor;
        this.assignedAt = LocalDateTime.now();
    }

    void end(LocalDate on) {
        this.endedOn = on;
        this.openPolicyRef = null;
    }

    /** In force on {@code day}: started on or before it and not ended by it. */
    boolean inForceOn(LocalDate day) {
        return APPLIED.equals(status) && !effectiveFrom.isAfter(day) && (endedOn == null || endedOn.isAfter(day));
    }

    public Long getId() {
        return id;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public boolean isOpen() {
        return openPolicyRef != null;
    }

    public Intermediary getIntermediary() {
        return intermediary;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEndedOn() {
        return endedOn;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }
}
