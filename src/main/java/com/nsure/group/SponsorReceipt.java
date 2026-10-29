package com.nsure.group;

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

/** A sponsor payment allocated to a scheme invoice. Unique per invoice and payer reference, so a resend is a no-op. */
@Entity
@Table(name = "grp_sponsor_receipt")
public class SponsorReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SchemeInvoice invoice;

    @Column(name = "payer_reference", nullable = false, length = 80)
    private String reference;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "outstanding_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal outstandingAfter;

    @Column(name = "received_by", nullable = false, length = 100)
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    protected SponsorReceipt() {
    }

    SponsorReceipt(String ref, SchemeInvoice invoice, String reference, BigDecimal amount, String receivedBy) {
        this.ref = ref;
        this.invoice = invoice;
        this.reference = reference;
        this.amount = amount;
        this.outstandingAfter = invoice.getOutstanding();
        this.receivedBy = receivedBy;
        this.receivedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public SchemeInvoice getInvoice() {
        return invoice;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getOutstandingAfter() {
        return outstandingAfter;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
