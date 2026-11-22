package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * The payer's standing authority to collect premiums (NS-SH04-002): a bank debit order or a mobile-money wallet. At
 * most one is active per policy; a replaced or cancelled mandate is kept with who cancelled it and why.
 */
@Entity
@Table(name = "col_mandate")
public class CollectionMandate {

    public static final String DEBIT_ORDER = "DEBIT_ORDER";
    public static final String MOBILE_WALLET = "MOBILE_WALLET";
    public static final String ACTIVE = "ACTIVE";
    public static final String CANCELLED = "CANCELLED";
    /** Copied from the payment mandate captured on the policy's application. */
    public static final String FROM_APPLICATION = "APPLICATION";
    public static final String FROM_STAFF = "STAFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "active_policy_ref", unique = true, length = 20)
    private String activePolicyRef;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 60)
    private String institution;

    @Column(name = "account_number", nullable = false, length = 60)
    private String accountNumber;

    @Column(name = "account_holder", length = 150)
    private String accountHolder;

    @Column(name = "debit_day")
    private Integer debitDay;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected CollectionMandate() {
    }

    CollectionMandate(String ref, String policyRef, String channel, String institution, String accountNumber,
            String accountHolder, Integer debitDay, String source, String actor) {
        this.ref = ref;
        this.policyRef = policyRef;
        this.activePolicyRef = policyRef;
        this.channel = channel;
        this.institution = institution;
        this.accountNumber = accountNumber;
        this.accountHolder = accountHolder;
        this.debitDay = debitDay;
        this.status = ACTIVE;
        this.source = source;
        this.createdBy = actor;
        this.createdAt = LocalDateTime.now();
    }

    void cancel(String reason, String actor) {
        this.status = CANCELLED;
        this.activePolicyRef = null;
        this.cancelReason = reason;
        this.cancelledBy = actor;
        this.cancelledAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    /** The account or wallet number with all but the last four characters hidden. */
    public String maskedAccount() {
        return mask(accountNumber);
    }

    static String mask(String account) {
        if (account == null) {
            return null;
        }
        String trimmed = account.replaceAll("\\s", "");
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getChannel() {
        return channel;
    }

    public String getInstitution() {
        return institution;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountHolder() {
        return accountHolder;
    }

    public Integer getDebitDay() {
        return debitDay;
    }

    public String getStatus() {
        return status;
    }

    public String getSource() {
        return source;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
