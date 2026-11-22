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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One reconciliation result: a statement line (side STATEMENT) or a receipt the statement does not show (side
 * SYSTEM). Anything but MATCHED is an exception that stays open, with an owner, until someone resolves it.
 */
@Entity
@Table(name = "bil_recon_item")
public class ReconItem {

    public static final String MATCHED = "MATCHED";
    /** On the statement, but no receipt here. */
    public static final String MISSING_RECEIPT = "MISSING_RECEIPT";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    /** The same reference twice on the statement. */
    public static final String DUPLICATE = "DUPLICATE";
    /**
     * NS-SH04-006: the provider settled money for an attempt this system closed as failed or expired, or has not
     * confirmed yet (a late settlement); it needs someone to apply or refund it.
     */
    public static final String LATE_SETTLEMENT = "LATE_SETTLEMENT";
    /** A receipt here that the statement does not show yet: late settlement, or money that never arrived. */
    public static final String NOT_ON_STATEMENT = "NOT_ON_STATEMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id", nullable = false)
    private Statement statement;

    @Column(nullable = false, length = 10)
    private String side;

    @Column(name = "line_no")
    private Integer lineNo;

    @Column(name = "provider_ref", nullable = false, length = 120)
    private String providerRef;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "matched_type", length = 30)
    private String matchedType;

    @Column(name = "matched_ref", length = 120)
    private String matchedRef;

    @Column(name = "expected_amount", precision = 19, scale = 2)
    private BigDecimal expectedAmount;

    @Column(length = 100)
    private String owner;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(length = 500)
    private String resolution;

    protected ReconItem() {
    }

    ReconItem(Statement statement, String side, Integer lineNo, String providerRef, BigDecimal amount,
            String currency, LocalDate valueDate, String status, String matchedType, String matchedRef,
            BigDecimal expectedAmount, String owner) {
        this.statement = statement;
        this.side = side;
        this.lineNo = lineNo;
        this.providerRef = providerRef;
        this.amount = amount;
        this.currency = currency;
        this.valueDate = valueDate;
        this.status = status;
        this.matchedType = matchedType;
        this.matchedRef = matchedRef;
        this.expectedAmount = expectedAmount;
        this.owner = MATCHED.equals(status) ? null : owner;
    }

    public boolean isOpen() {
        return !MATCHED.equals(status) && resolvedAt == null;
    }

    void assign(String owner) {
        this.owner = owner;
    }

    void resolve(String resolution, String actor) {
        this.resolution = resolution;
        this.resolvedBy = actor;
        this.resolvedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Statement getStatement() {
        return statement;
    }

    public String getSide() {
        return side;
    }

    public Integer getLineNo() {
        return lineNo;
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

    public LocalDate getValueDate() {
        return valueDate;
    }

    public String getStatus() {
        return status;
    }

    public String getMatchedType() {
        return matchedType;
    }

    public String getMatchedRef() {
        return matchedRef;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public String getOwner() {
        return owner;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public String getResolution() {
        return resolution;
    }
}
