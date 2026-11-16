package com.nsure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

/**
 * Every exchange with a payment provider, raw (spec 01 BR-65, 13 S-19): the evidence when a payment is disputed.
 * Append-only and never shown to customers (spec 04 A-12); staff read it with READ_PAYMENT.
 */
@Entity
@Immutable
@Table(name = "payment_gateway_log")
public class PaymentGatewayLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 40)
    private String operation;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PaymentGatewayLog() {
    }

    PaymentGatewayLog(Long transactionId, String provider, String operation, String payload) {
        this.transactionId = transactionId;
        this.provider = provider;
        this.operation = operation;
        this.payload = payload == null ? "" : payload.length() > 4000 ? payload.substring(0, 4000) : payload;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getProvider() {
        return provider;
    }

    public String getOperation() {
        return operation;
    }

    public String getPayload() {
        return payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
