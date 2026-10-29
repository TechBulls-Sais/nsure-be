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

    protected Recovery() {
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
}
