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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A lump-sum sponsor remittance (NS-GL05-003), or a credit held for the sponsor from a supplementary credit. It is
 * allocated to invoices oldest first, to named invoices or by member rows; whatever cannot be allocated is held as
 * unallocated money (in suspense) and never silently changes anyone's cover.
 */
@Entity
@Table(name = "grp_sponsor_payment")
public class SponsorPayment {

    public static final String CASH = "CASH";
    public static final String CREDIT_NOTE = "CREDIT_NOTE";
    public static final String ALLOCATED = "ALLOCATED";
    public static final String PARTLY_ALLOCATED = "PARTLY_ALLOCATED";
    public static final String UNALLOCATED = "UNALLOCATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "payer_reference", nullable = false, length = 80)
    private String reference;

    @Column(name = "payment_kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "allocation_rule", nullable = false, length = 20)
    private String rule;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal allocated;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal unallocated;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(name = "payment_status", nullable = false, length = 20)
    private String status;

    @Column(name = "unmatched_detail", length = 1000)
    private String unmatchedDetail;

    @Column(length = 300)
    private String note;

    @Column(name = "received_by", nullable = false, length = 100)
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    protected SponsorPayment() {
    }

    SponsorPayment(String ref, GroupScheme scheme, String reference, String kind, String rule, BigDecimal amount,
            LocalDate receivedDate, String note, String receivedBy) {
        this.ref = ref;
        this.scheme = scheme;
        this.reference = reference;
        this.kind = kind;
        this.rule = rule;
        this.amount = amount;
        this.allocated = BigDecimal.ZERO.setScale(2);
        this.unallocated = amount;
        this.receivedDate = receivedDate;
        this.status = UNALLOCATED;
        this.note = note;
        this.receivedBy = receivedBy;
        this.receivedAt = LocalDateTime.now();
    }

    void allocate(BigDecimal part) {
        this.allocated = allocated.add(part);
        this.unallocated = amount.subtract(allocated);
        this.status = unallocated.signum() <= 0 ? ALLOCATED : allocated.signum() > 0 ? PARTLY_ALLOCATED : UNALLOCATED;
    }

    void unmatched(String detail) {
        this.unmatchedDetail = detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 997) + "...";
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

    public String getReference() {
        return reference;
    }

    public String getKind() {
        return kind;
    }

    public String getRule() {
        return rule;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAllocated() {
        return allocated;
    }

    public BigDecimal getUnallocated() {
        return unallocated;
    }

    public LocalDate getReceivedDate() {
        return receivedDate;
    }

    public String getStatus() {
        return status;
    }

    public String getUnmatchedDetail() {
        return unmatchedDetail;
    }

    public String getNote() {
        return note;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
