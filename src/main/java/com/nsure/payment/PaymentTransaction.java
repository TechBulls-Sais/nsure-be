package com.nsure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * One attempt to collect a premium through a payment provider (spec 13 §3). Separate from the premium payment, which
 * is the accounting fact: a period may have several attempts and at most one successful payment (S-7).
 *
 * <pre>
 *   CREATED ─▶ REDIRECTED ─▶ PENDING_VERIFICATION ─┬─▶ SETTLED   (policy activated)
 *                                                  ├─▶ FAILED
 *                                                  └─▶ EXPIRED
 * </pre>
 */
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    public static final String CREATED = "CREATED";
    public static final String REDIRECTED = "REDIRECTED";
    public static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";
    public static final String SETTLED = "SETTLED";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";
    public static final Set<String> TERMINAL = Set.of(SETTLED, FAILED, EXPIRED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "payment_method", nullable = false, length = 40)
    private String paymentMethod;

    @Column(name = "provider_token", unique = true, length = 120)
    private String providerToken;

    @Column(name = "provider_reference", nullable = false, unique = true, length = 120)
    private String providerReference;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 80)
    private String idempotencyKey;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 30)
    private String purpose;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "redirect_url", length = 500)
    private String redirectUrl;

    @Column(name = "provider_result_code", length = 20)
    private String providerResultCode;

    @Column(name = "provider_result_text", length = 500)
    private String providerResultText;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "redirected_at")
    private LocalDateTime redirectedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "verify_attempts", nullable = false)
    private int verifyAttempts;

    @Column(name = "settled_via", length = 20)
    private String settledVia;

    @Column(name = "premium_payment_id")
    private Long premiumPaymentId;

    @Version
    @Column(nullable = false)
    private long version;

    protected PaymentTransaction() {
    }

    PaymentTransaction(String policyRef, String customerRef, String provider, String paymentMethod,
            String providerReference, String idempotencyKey, BigDecimal amount, String currency, String purpose,
            LocalDate periodStart, LocalDate periodEnd, LocalDateTime expiresAt, String createdBy) {
        this.policyRef = policyRef;
        this.customerRef = customerRef;
        this.provider = provider;
        this.paymentMethod = paymentMethod;
        this.providerReference = providerReference;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.purpose = purpose;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.status = CREATED;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(status);
    }

    void redirected(String token, String url, LocalDateTime expires) {
        this.providerToken = token;
        this.redirectUrl = url;
        this.redirectedAt = LocalDateTime.now();
        if (expires != null) {
            this.expiresAt = expires;
        }
        this.status = REDIRECTED;
    }

    void inline(String token) {
        this.providerToken = token;
        this.status = PENDING_VERIFICATION;
    }

    void markPendingVerification() {
        this.status = PENDING_VERIFICATION;
    }

    void verified(String code, String text) {
        this.verifyAttempts++;
        this.lastVerifiedAt = LocalDateTime.now();
        this.providerResultCode = code;
        this.providerResultText = text;
    }

    void settle(Long premiumPaymentId, String via) {
        this.premiumPaymentId = premiumPaymentId;
        this.settledVia = via;
        this.settledAt = LocalDateTime.now();
        this.status = SETTLED;
    }

    void fail(String reason) {
        this.failureReason = reason;
        this.status = FAILED;
    }

    void expire(String reason) {
        this.failureReason = reason;
        this.status = EXPIRED;
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

    public String getProvider() {
        return provider;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getProviderToken() {
        return providerToken;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPurpose() {
        return purpose;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getStatus() {
        return status;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getProviderResultText() {
        return providerResultText;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public int getVerifyAttempts() {
        return verifyAttempts;
    }

    public String getSettledVia() {
        return settledVia;
    }

    public Long getPremiumPaymentId() {
        return premiumPaymentId;
    }
}
