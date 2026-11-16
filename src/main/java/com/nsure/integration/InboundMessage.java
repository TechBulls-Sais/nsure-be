package com.nsure.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One provider callback, kept by (provider, provider reference) so a repeated callback returns the first result
 * instead of applying twice (AT-SH12-01, AT-SH04-01). Applied, Suspense (cash we could not apply) or Rejected.
 */
@Entity
@Table(name = "int_inbound_message")
public class InboundMessage {

    public static final String APPLIED = "APPLIED";
    public static final String SUSPENSE = "SUSPENSE";
    public static final String REJECTED = "REJECTED";
    public static final String REFUNDED = "REFUNDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_ref", nullable = false, length = 80)
    private String providerRef;

    @Column(name = "message_type", nullable = false, length = 40)
    private String messageType;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "business_ref", length = 60)
    private String businessRef;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "result_ref", length = 60)
    private String resultRef;

    @Column(length = 500)
    private String detail;

    @Column(name = "received_by", nullable = false, length = 100)
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    // Suspense clearance (Blueprint §7): requested by one person with a reason, applied when someone else approves.
    @Column(name = "clearance_action", length = 20)
    private String clearanceAction;

    @Column(name = "clearance_policy_ref", length = 40)
    private String clearancePolicyRef;

    @Column(name = "clearance_reason", length = 1000)
    private String clearanceReason;

    @Column(name = "clearance_requested_by", length = 100)
    private String clearanceRequestedBy;

    @Column(name = "clearance_requested_at")
    private LocalDateTime clearanceRequestedAt;

    @Column(name = "cleared_by", length = 100)
    private String clearedBy;

    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    // Split and partial allocation (NS-SH04-003): how much of this receipt has been applied so far, and the
    // lines a clearance request proposes ([{"policyRef":"POL-…","amount":"…"}]).
    @Column(name = "allocated_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;

    @Column(name = "clearance_allocations", length = 2000)
    private String clearanceAllocations;

    protected InboundMessage() {
    }

    InboundMessage(String provider, String providerRef, String messageType, String payloadHash, String businessRef,
            BigDecimal amount, String receivedBy) {
        this.provider = provider;
        this.providerRef = providerRef;
        this.messageType = messageType;
        this.payloadHash = payloadHash;
        this.businessRef = businessRef;
        this.amount = amount;
        this.receivedBy = receivedBy;
        this.receivedAt = LocalDateTime.now();
    }

    void outcome(String status, String resultRef, String detail) {
        this.status = status;
        this.resultRef = resultRef;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public String getBusinessRef() {
        return businessRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public String getResultRef() {
        return resultRef;
    }

    public String getDetail() {
        return detail;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    /** Records a clearance request on a receipt in suspense; nothing moves until someone else approves it. */
    public void requestClearance(String action, String policyRef, String reason, String actor) {
        requestClearance(action, policyRef, null, reason, actor);
    }

    /** A clearance request with the allocation lines it proposes (null for a refund or a whole-receipt allocation). */
    public void requestClearance(String action, String policyRef, String allocations, String reason, String actor) {
        this.clearanceAllocations = allocations;
        this.clearanceAction = action;
        this.clearancePolicyRef = policyRef;
        this.clearanceReason = reason;
        this.clearanceRequestedBy = actor;
        this.clearanceRequestedAt = LocalDateTime.now();
    }

    /** The approved clearance: APPLIED to the policy or REFUNDED to the payer. */
    public void cleared(String status, String resultRef, String detail, String checker) {
        outcome(status, resultRef, detail);
        this.clearedBy = checker;
        this.clearedAt = LocalDateTime.now();
    }

    /** A rejected request: the receipt stays in suspense and a new request can be made. */
    public void clearanceRejected() {
        this.clearanceAllocations = null;
        this.clearanceAction = null;
        this.clearancePolicyRef = null;
        this.clearanceReason = null;
        this.clearanceRequestedBy = null;
        this.clearanceRequestedAt = null;
    }

    public String getClearanceAction() {
        return clearanceAction;
    }

    public String getClearancePolicyRef() {
        return clearancePolicyRef;
    }

    public String getClearanceReason() {
        return clearanceReason;
    }

    public String getClearanceRequestedBy() {
        return clearanceRequestedBy;
    }

    public LocalDateTime getClearanceRequestedAt() {
        return clearanceRequestedAt;
    }

    public String getClearedBy() {
        return clearedBy;
    }

    public LocalDateTime getClearedAt() {
        return clearedAt;
    }

    /** Part of the receipt applied: it stays in suspense with the rest unapplied, ready for another request. */
    public void partlyApplied(BigDecimal applied, String detail) {
        this.allocatedAmount = this.allocatedAmount.add(applied);
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        clearanceRejected();
    }

    public void applied(BigDecimal applied) {
        this.allocatedAmount = this.allocatedAmount.add(applied);
    }

    /** What is still unapplied in suspense. */
    public BigDecimal getUnapplied() {
        return amount == null ? BigDecimal.ZERO : amount.subtract(allocatedAmount);
    }

    public BigDecimal getAllocatedAmount() {
        return allocatedAmount;
    }

    public String getClearanceAllocations() {
        return clearanceAllocations;
    }
}
