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
}
