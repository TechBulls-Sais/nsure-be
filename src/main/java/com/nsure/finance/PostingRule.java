package com.nsure.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * How one event type posts: debit account, credit account and the payload field holding the amount (NS-SH08-001).
 * Each change bumps the version, which every journal records, so a posting can be traced to the rule that made it.
 */
@Entity
@Table(name = "fin_posting_rule")
public class PostingRule {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, unique = true, length = 60)
    private String eventType;

    @Column(name = "debit_account", nullable = false, length = 20)
    private String debitAccount;

    @Column(name = "credit_account", nullable = false, length = 20)
    private String creditAccount;

    @Column(name = "amount_field", nullable = false, length = 40)
    private String amountField;

    @Column(name = "business_domain", nullable = false, length = 20)
    private String domain;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected PostingRule() {
    }

    PostingRule(String eventType) {
        this.eventType = eventType;
    }

    void change(String debitAccount, String creditAccount, String amountField, String domain, String description,
            String status, String actor) {
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amountField = amountField;
        this.domain = domain;
        this.description = description;
        this.status = status;
        this.version++;
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
    }

    boolean isActive() {
        return ACTIVE.equals(status);
    }

    public String getEventType() {
        return eventType;
    }

    public String getDebitAccount() {
        return debitAccount;
    }

    public String getCreditAccount() {
        return creditAccount;
    }

    public String getAmountField() {
        return amountField;
    }

    public String getDomain() {
        return domain;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
