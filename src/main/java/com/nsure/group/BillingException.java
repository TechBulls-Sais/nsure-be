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
 * A billing exception for group finance to work (AT-GL05-02, NS-GL05-004): a short payment, sponsor money that could
 * not be allocated, a disputed line, or a lapse escalated because disputes or unallocated money are unresolved. Raised
 * automatically, resolved automatically when the cause clears, or resolved by hand with a note.
 */
@Entity
@Table(name = "grp_billing_exception")
public class BillingException {

    public static final String SHORT_PAYMENT = "SHORT_PAYMENT";
    public static final String UNALLOCATED = "UNALLOCATED";
    public static final String DISPUTE = "DISPUTE";
    public static final String LAPSE_ESCALATED = "LAPSE_ESCALATED";
    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "exception_type", nullable = false, length = 30)
    private String type;

    @Column(name = "invoice_ref", length = 20)
    private String invoiceRef;

    @Column(name = "payment_ref", length = 20)
    private String paymentRef;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(name = "exception_status", nullable = false, length = 20)
    private String status;

    @Column(name = "raised_by", nullable = false, length = 100)
    private String raisedBy;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 300)
    private String resolutionNote;

    protected BillingException() {
    }

    BillingException(GroupScheme scheme, String type, String invoiceRef, String paymentRef, BigDecimal amount,
            String detail, String raisedBy) {
        this.scheme = scheme;
        this.type = type;
        this.invoiceRef = invoiceRef;
        this.paymentRef = paymentRef;
        this.amount = amount;
        this.detail = detail.length() <= 500 ? detail : detail.substring(0, 497) + "...";
        this.status = OPEN;
        this.raisedBy = raisedBy;
        this.raisedAt = LocalDateTime.now();
    }

    void update(BigDecimal amount, String detail) {
        this.amount = amount;
        this.detail = detail.length() <= 500 ? detail : detail.substring(0, 497) + "...";
    }

    void resolve(String actor, String note) {
        this.status = RESOLVED;
        this.resolvedBy = actor;
        this.resolvedAt = LocalDateTime.now();
        this.resolutionNote = note == null || note.length() <= 300 ? note : note.substring(0, 297) + "...";
    }

    public Long getId() {
        return id;
    }

    public GroupScheme getScheme() {
        return scheme;
    }

    public String getType() {
        return type;
    }

    public String getInvoiceRef() {
        return invoiceRef;
    }

    public String getPaymentRef() {
        return paymentRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDetail() {
        return detail;
    }

    public String getStatus() {
        return status;
    }

    public String getRaisedBy() {
        return raisedBy;
    }

    public LocalDateTime getRaisedAt() {
        return raisedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }
}
