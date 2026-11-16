package com.nsure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The provider-side record of the hosted checkout simulator: what the "provider" was asked to charge and what the
 * customer did on its page. It stands in for DPO's own transaction record until DPO credentials exist (B2/B3).
 */
@Entity
@Table(name = "simulated_checkout")
public class SimulatedCheckout {

    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";
    public static final String DECLINED = "DECLINED";

    @Id
    @Column(length = 120)
    private String token;

    @Column(nullable = false, length = 120)
    private String reference;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "charged_amount", precision = 15, scale = 2)
    private BigDecimal chargedAmount;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    protected SimulatedCheckout() {
    }

    SimulatedCheckout(String token, String reference, BigDecimal amount, String currency, String description) {
        this.token = token;
        this.reference = reference;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.outcome = PENDING;
        this.createdAt = LocalDateTime.now();
    }

    void decide(String outcome, BigDecimal charged) {
        this.outcome = outcome;
        this.chargedAmount = charged;
        this.decidedAt = LocalDateTime.now();
    }

    public String getToken() {
        return token;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getChargedAmount() {
        return chargedAmount;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDescription() {
        return description;
    }
}
