package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

/**
 * A collected premium for one policy period: the accounting fact (spec 13 S-7). The database holds at most one per
 * policy and period start (unique key), so a premium can never be collected twice for a period, whatever path
 * collected it (spec 01 BR-66, 13 S-9).
 */
@Entity
@Immutable
@Table(name = "premium_payment")
public class PremiumPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_method", nullable = false, length = 40)
    private String paymentMethod;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "receipt_ref", length = 20)
    private String receiptRef;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "collected_by", nullable = false, length = 100)
    private String collectedBy;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    protected PremiumPayment() {
    }

    PremiumPayment(InsurancePolicy policy, LocalDate periodStart, LocalDate periodEnd, String paymentMethod,
            String receiptRef, Long transactionId, String collectedBy) {
        this.policyId = policy.getId();
        this.policyRef = policy.getRef();
        this.customerRef = policy.getCustomerRef();
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.amount = policy.getPremium();
        this.currency = "BWP";
        this.paymentMethod = paymentMethod;
        this.status = "SUCCESSFUL";
        this.receiptRef = receiptRef;
        this.transactionId = transactionId;
        this.collectedBy = collectedBy;
        this.paidAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getStatus() {
        return status;
    }

    public String getReceiptRef() {
        return receiptRef;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getCollectedBy() {
        return collectedBy;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }
}
