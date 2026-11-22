package com.nsure.reinsurance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The reinsurer's share of a paid claim, due from the reinsurer. Recorded apart from the customer's payout, which is
 * never reduced or delayed by it (AT-SH07-03).
 */
@Entity
@Table(name = "ri_recovery")
public class Recovery {

    public static final String EXPECTED = "EXPECTED";
    public static final String RECEIVED = "RECEIVED";
    /** Approved as not recoverable (NS-SH07-005); the gross claim stays paid in full. */
    public static final String WRITTEN_OFF = "WRITTEN_OFF";
    // Stages of an expected recovery: nothing pending, disputed by the reinsurer, or a write-off awaiting approval.
    public static final String NONE = "NONE";
    public static final String DISPUTED = "DISPUTED";
    public static final String WRITE_OFF_PENDING = "WRITE_OFF_PENDING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treaty_id", nullable = false)
    private Treaty treaty;

    @Column(name = "claim_ref", nullable = false, length = 20)
    private String claimRef;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "source_event_id", nullable = false, unique = true, length = 36)
    private String sourceEventId;

    @Column(name = "claim_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal claimAmount;

    @Column(name = "ceded_share", nullable = false, precision = 9, scale = 6)
    private BigDecimal cededShare;

    @Column(name = "recovery_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal recoveryAmount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // SH07: the reinsurer's payment of the recovery, when it arrives.
    @Column(name = "received_amount", precision = 15, scale = 2)
    private BigDecimal receivedAmount;

    @Column(name = "received_reference", length = 60)
    private String receivedReference;

    @Column(name = "received_note", length = 500)
    private String receivedNote;

    @Column(name = "received_by", length = 100)
    private String receivedBy;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    // NS-SH07-005: the recovery ledger — disputes, approved write-offs, the event date and the claim as paid.
    @Column(nullable = false, length = 20)
    private String stage = NONE;

    // NS-SH07-004: the claims bordereau the recovery was reported on.
    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "bordereau_id")
    private Bordereau bordereau;

    void reportedOn(Bordereau bordereau) {
        this.bordereau = bordereau;
    }

    public Bordereau getBordereau() {
        return bordereau;
    }

    @Column(name = "event_date")
    private java.time.LocalDate eventDate;

    @Column(name = "claim_paid_amount", precision = 15, scale = 2)
    private BigDecimal claimPaidAmount;

    @Column(name = "original_amount", precision = 15, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "dispute_reason", length = 500)
    private String disputeReason;

    @Column(name = "disputed_by", length = 100)
    private String disputedBy;

    @Column(name = "disputed_at")
    private LocalDateTime disputedAt;

    @Column(name = "write_off_amount", precision = 15, scale = 2)
    private BigDecimal writeOffAmount;

    @Column(name = "write_off_reason", length = 500)
    private String writeOffReason;

    @Column(name = "write_off_requested_by", length = 100)
    private String writeOffRequestedBy;

    @Column(name = "write_off_requested_at")
    private LocalDateTime writeOffRequestedAt;

    @Column(name = "write_off_decided_by", length = 100)
    private String writeOffDecidedBy;

    @Column(name = "write_off_decided_at")
    private LocalDateTime writeOffDecidedAt;

    protected Recovery() {
    }

    Recovery dated(java.time.LocalDate eventDate) {
        this.eventDate = eventDate;
        return this;
    }

    void claimPaid(BigDecimal paid) {
        this.claimPaidAmount = paid;
    }

    /** The claim was paid at a different amount than approved: the expectation follows it, keeping the first one. */
    void reexpect(BigDecimal claimAmount, BigDecimal recoveryAmount) {
        if (originalAmount == null) {
            originalAmount = this.recoveryAmount;
        }
        this.claimAmount = claimAmount;
        this.recoveryAmount = recoveryAmount;
    }

    void dispute(String reason, String actor) {
        this.stage = DISPUTED;
        this.disputeReason = reason;
        this.disputedBy = actor;
        this.disputedAt = LocalDateTime.now();
    }

    void resolveDispute(BigDecimal agreed, String note) {
        if (agreed != null && agreed.compareTo(recoveryAmount) != 0) {
            if (originalAmount == null) {
                originalAmount = recoveryAmount;
            }
            this.recoveryAmount = agreed;
        }
        this.stage = NONE;
        this.disputeReason = disputeReason + " · resolved: " + note;
        if (this.disputeReason.length() > 500) {
            this.disputeReason = this.disputeReason.substring(0, 500);
        }
    }

    void requestWriteOff(BigDecimal amount, String reason, String actor) {
        this.stage = WRITE_OFF_PENDING;
        this.writeOffAmount = amount;
        this.writeOffReason = reason;
        this.writeOffRequestedBy = actor;
        this.writeOffRequestedAt = LocalDateTime.now();
    }

    void decideWriteOff(boolean approved, String actor) {
        this.writeOffDecidedBy = actor;
        this.writeOffDecidedAt = LocalDateTime.now();
        this.stage = NONE;
        if (approved) {
            this.status = WRITTEN_OFF;
        } else {
            this.writeOffReason = writeOffReason + " · rejected by " + actor;
            if (this.writeOffReason.length() > 500) {
                this.writeOffReason = this.writeOffReason.substring(0, 500);
            }
        }
    }

    public String getStage() {
        return stage;
    }

    public java.time.LocalDate getEventDate() {
        return eventDate;
    }

    public BigDecimal getClaimPaidAmount() {
        return claimPaidAmount;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public String getDisputeReason() {
        return disputeReason;
    }

    public String getDisputedBy() {
        return disputedBy;
    }

    public LocalDateTime getDisputedAt() {
        return disputedAt;
    }

    public BigDecimal getWriteOffAmount() {
        return writeOffAmount;
    }

    public String getWriteOffReason() {
        return writeOffReason;
    }

    public String getWriteOffRequestedBy() {
        return writeOffRequestedBy;
    }

    public LocalDateTime getWriteOffRequestedAt() {
        return writeOffRequestedAt;
    }

    public String getWriteOffDecidedBy() {
        return writeOffDecidedBy;
    }

    public LocalDateTime getWriteOffDecidedAt() {
        return writeOffDecidedAt;
    }

    Recovery(Treaty treaty, String claimRef, String policyRef, String sourceEventId, BigDecimal claimAmount,
            BigDecimal cededShare, BigDecimal recoveryAmount) {
        this.ref = "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.treaty = treaty;
        this.claimRef = claimRef;
        this.policyRef = policyRef;
        this.sourceEventId = sourceEventId;
        this.claimAmount = claimAmount;
        this.cededShare = cededShare;
        this.recoveryAmount = recoveryAmount;
        this.status = EXPECTED;
        this.createdAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("REC-%05d", id);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public Treaty getTreaty() {
        return treaty;
    }

    public String getClaimRef() {
        return claimRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public BigDecimal getClaimAmount() {
        return claimAmount;
    }

    public BigDecimal getCededShare() {
        return cededShare;
    }

    public BigDecimal getRecoveryAmount() {
        return recoveryAmount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    void received(BigDecimal amount, String reference, String note, String by) {
        this.receivedAmount = amount;
        this.receivedReference = reference;
        this.receivedNote = note;
        this.receivedBy = by;
        this.receivedAt = LocalDateTime.now();
        this.status = RECEIVED;
    }

    public BigDecimal getReceivedAmount() {
        return receivedAmount;
    }

    public String getReceivedReference() {
        return receivedReference;
    }

    public String getReceivedNote() {
        return receivedNote;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
