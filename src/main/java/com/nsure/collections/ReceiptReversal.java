package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A premium receipt that the bank returned, reversed or charged back (NS-SH04-005), referencing the original. */
@Entity
@Table(name = "bil_receipt_reversal")
public class ReceiptReversal {

    public static final String RETURNED = "RETURNED";
    public static final String REVERSED = "REVERSED";
    public static final String CHARGEBACK = "CHARGEBACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "receipt_ref", nullable = false, unique = true, length = 20)
    private String receiptRef;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "reversed_by", nullable = false, length = 100)
    private String reversedBy;

    @Column(name = "reversed_at", nullable = false)
    private LocalDateTime reversedAt;

    protected ReceiptReversal() {
    }

    ReceiptReversal(String ref, String receiptRef, String policyRef, String kind, BigDecimal amount, String reason,
            Long transactionId, String reversedBy) {
        this.ref = ref;
        this.receiptRef = receiptRef;
        this.policyRef = policyRef;
        this.kind = kind;
        this.amount = amount;
        this.reason = reason;
        this.transactionId = transactionId;
        this.reversedBy = reversedBy;
        this.reversedAt = LocalDateTime.now();
    }

    public String getRef() {
        return ref;
    }

    public String getReceiptRef() {
        return receiptRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getKind() {
        return kind;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getReversedBy() {
        return reversedBy;
    }

    public LocalDateTime getReversedAt() {
        return reversedAt;
    }
}
