package com.nsure.group;

import com.nsure.portfolio.GroupScheme;
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
import java.time.LocalDateTime;

/**
 * A supplementary debit or credit on an issued invoice (NS-GL05-002): member changes that reach back into an invoiced
 * month are billed or credited here, line by line against the original invoice lines, and the issued invoice itself is
 * never edited. A credit reduces the invoice's balance; any credit beyond it is held for the sponsor.
 */
@Entity
@Table(name = "grp_adjustment")
public class SchemeAdjustment {

    public static final String DEBIT = "DEBIT";
    public static final String CREDIT = "CREDIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SchemeInvoice invoice;

    @Column(name = "adjustment_type", nullable = false, length = 10)
    private String type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "applied_to_invoice", nullable = false, precision = 15, scale = 2)
    private BigDecimal appliedToInvoice;

    @Column(name = "held_as_credit", nullable = false, precision = 15, scale = 2)
    private BigDecimal heldAsCredit;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "source_ref", length = 40)
    private String sourceRef;

    @Column(length = 300)
    private String reason;

    @Column(name = "policy_year")
    private Integer policyYear;

    @Column(name = "issued_by", nullable = false, length = 100)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    protected SchemeAdjustment() {
    }

    SchemeAdjustment(String ref, SchemeInvoice invoice, BigDecimal amount, int lineCount, String sourceRef,
            String reason, String issuedBy) {
        this.ref = ref;
        this.scheme = invoice.getScheme();
        this.invoice = invoice;
        this.type = amount.signum() >= 0 ? DEBIT : CREDIT;
        this.amount = amount;
        this.appliedToInvoice = BigDecimal.ZERO.setScale(2);
        this.heldAsCredit = BigDecimal.ZERO.setScale(2);
        this.lineCount = lineCount;
        this.sourceRef = sourceRef;
        this.reason = reason == null || reason.length() <= 300 ? reason : reason.substring(0, 297) + "...";
        this.policyYear = invoice.getPolicyYear();
        this.issuedBy = issuedBy;
        this.issuedAt = LocalDateTime.now();
    }

    void settled(BigDecimal applied, BigDecimal held) {
        this.appliedToInvoice = applied;
        this.heldAsCredit = held;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public GroupScheme getScheme() {
        return scheme;
    }

    public SchemeInvoice getInvoice() {
        return invoice;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAppliedToInvoice() {
        return appliedToInvoice;
    }

    public BigDecimal getHeldAsCredit() {
        return heldAsCredit;
    }

    public int getLineCount() {
        return lineCount;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getReason() {
        return reason;
    }

    public Integer getPolicyYear() {
        return policyYear;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }
}
