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
 * The simulated provider's own record that it received money under a reference. It stands in for a bank or wallet
 * status API until real provider credentials exist (B2/B3/B4); {@link ProviderStatusClient} reads it.
 */
@Entity
@Table(name = "sim_provider_receipt")
public class SimulatedProviderReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_ref", nullable = false, length = 80)
    private String providerRef;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "recorded_by", nullable = false, length = 100)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    protected SimulatedProviderReceipt() {
    }

    SimulatedProviderReceipt(String provider, String providerRef, BigDecimal amount, String currency,
            String recordedBy) {
        this.provider = provider;
        this.providerRef = providerRef;
        this.amount = amount;
        this.currency = currency;
        this.recordedBy = recordedBy;
        this.recordedAt = LocalDateTime.now();
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

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }
}
