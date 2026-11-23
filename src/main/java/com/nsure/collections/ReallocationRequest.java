package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A request to move money already applied to one instalment onto another instalment of the same policy (SH04
 * workspace): asked for by one Finance user with a reason, carried out only when a different person approves it.
 * PENDING_APPROVAL → APPROVED (moved) or REJECTED.
 */
@Entity
@Table(name = "bil_reallocation_request")
public class ReallocationRequest {

    public static final String PENDING = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "allocation_ref", nullable = false, length = 20)
    private String allocationRef;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "source_ref", nullable = false, length = 60)
    private String sourceRef;

    @Column(name = "from_instalment_ref", nullable = false, length = 20)
    private String fromInstalmentRef;

    @Column(name = "to_instalment_ref", nullable = false, length = 20)
    private String toInstalmentRef;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "new_allocation_ref", length = 20)
    private String newAllocationRef;

    protected ReallocationRequest() {
    }

    ReallocationRequest(String ref, Allocation allocation, Instalment to, String reason, String requestedBy) {
        this.ref = ref;
        this.allocationRef = allocation.getRef();
        this.policyRef = allocation.getPolicyRef();
        this.sourceRef = allocation.getSourceRef();
        this.fromInstalmentRef = allocation.getInstalment().getRef();
        this.toInstalmentRef = to.getRef();
        this.amount = allocation.getAmount();
        this.reason = reason;
        this.status = PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = LocalDateTime.now();
    }

    void decide(String status, String by, String note, String newAllocationRef) {
        this.status = status;
        this.decidedBy = by;
        this.decidedAt = LocalDateTime.now();
        this.decisionNote = note;
        this.newAllocationRef = newAllocationRef;
    }

    public String getRef() {
        return ref;
    }

    public String getAllocationRef() {
        return allocationRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getFromInstalmentRef() {
        return fromInstalmentRef;
    }

    public String getToInstalmentRef() {
        return toInstalmentRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getNewAllocationRef() {
        return newAllocationRef;
    }
}
