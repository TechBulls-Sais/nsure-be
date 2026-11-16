package com.nsure.collections;

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

/**
 * Money applied to one instalment (NS-SH04-003): from a premium receipt, or part of a suspense receipt. A reversal
 * marks it reversed and keeps it; it is never deleted.
 */
@Entity
@Table(name = "bil_allocation")
public class Allocation {

    /** A premium receipt (INV-…) that settled the instalment. */
    public static final String RECEIPT = "RECEIPT";
    /** Part of a receipt held in suspense (its inbound message id). */
    public static final String SUSPENSE = "SUSPENSE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instalment_id", nullable = false)
    private Instalment instalment;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_ref", nullable = false, length = 60)
    private String sourceRef;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "allocated_by", nullable = false, length = 100)
    private String allocatedBy;

    @Column(name = "allocated_at", nullable = false)
    private LocalDateTime allocatedAt;

    @Column(name = "reversed_by", length = 100)
    private String reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_ref", length = 20)
    private String reversalRef;

    protected Allocation() {
    }

    Allocation(String ref, Instalment instalment, String sourceType, String sourceRef, BigDecimal amount,
            String allocatedBy) {
        this.ref = ref;
        this.instalment = instalment;
        this.policyRef = instalment.getPolicyRef();
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.amount = amount;
        this.allocatedBy = allocatedBy;
        this.allocatedAt = LocalDateTime.now();
    }

    void reverse(String reversalRef, String actor) {
        this.reversalRef = reversalRef;
        this.reversedBy = actor;
        this.reversedAt = LocalDateTime.now();
    }

    public boolean isLive() {
        return reversedAt == null;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public Instalment getInstalment() {
        return instalment;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getAllocatedBy() {
        return allocatedBy;
    }

    public LocalDateTime getAllocatedAt() {
        return allocatedAt;
    }

    public String getReversedBy() {
        return reversedBy;
    }

    public LocalDateTime getReversedAt() {
        return reversedAt;
    }

    public String getReversalRef() {
        return reversalRef;
    }
}
