package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One decision on a claim, kept for good (NS-SH05-004, §09: reopening preserves the prior outcome). The claim row
 * carries the decision that stands now; every decision ever made, including those a reopening set aside, is here.
 */
@Entity
@Table(name = "clm_decision")
public class ClaimDecision {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String REOPENED = "REOPENED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_ref", nullable = false, length = 20, updatable = false)
    private String claimRef;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(nullable = false, length = 20, updatable = false)
    private String decision;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(name = "amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "decided_by", nullable = false, length = 100, updatable = false)
    private String decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private LocalDateTime decidedAt;

    /** Set when a later decision supersedes this one; the record itself never changes otherwise. */
    @Column(name = "superseded_by")
    private Integer supersededBy;

    protected ClaimDecision() {
    }

    ClaimDecision(String claimRef, int sequenceNo, String decision, String reason, BigDecimal amount, String decidedBy) {
        this.claimRef = claimRef;
        this.sequenceNo = sequenceNo;
        this.decision = decision;
        this.reason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.amount = amount;
        this.decidedBy = decidedBy;
        this.decidedAt = LocalDateTime.now().withNano(0);
    }

    void supersededBy(int sequenceNo) {
        this.supersededBy = sequenceNo;
    }

    public Long getId() {
        return id;
    }

    public String getClaimRef() {
        return claimRef;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public Integer getSupersededBy() {
        return supersededBy;
    }
}
