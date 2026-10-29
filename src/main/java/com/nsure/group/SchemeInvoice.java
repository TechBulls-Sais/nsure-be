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
 * A scheme's premium invoice for one month (GL05), one per scheme and period. A short sponsor payment leaves an
 * explicit outstanding balance and the invoice Partially Paid; it is Paid only when nothing is outstanding.
 */
@Entity
@Table(name = "grp_invoice")
public class SchemeInvoice {

    public static final String ISSUED = "ISSUED";
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String PAID = "PAID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "billing_period", nullable = false, length = 7)
    private String period;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal outstanding;

    @Column(name = "invoice_status", nullable = false, length = 20)
    private String status;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    @Column(name = "issued_by", nullable = false, length = 100)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    protected SchemeInvoice() {
    }

    SchemeInvoice(String ref, GroupScheme scheme, String period, LocalDate periodStart, LocalDate periodEnd,
            String issuedBy) {
        this.ref = ref;
        this.scheme = scheme;
        this.period = period;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.amount = BigDecimal.ZERO.setScale(2);
        this.paidAmount = BigDecimal.ZERO.setScale(2);
        this.outstanding = BigDecimal.ZERO.setScale(2);
        this.status = ISSUED;
        this.issuedBy = issuedBy;
        this.issuedAt = LocalDateTime.now();
    }

    void totals(BigDecimal amount, int memberCount) {
        this.amount = amount;
        this.outstanding = amount.subtract(paidAmount);
        this.memberCount = memberCount;
    }

    void allocate(BigDecimal receipt) {
        this.paidAmount = paidAmount.add(receipt);
        this.outstanding = amount.subtract(paidAmount);
        this.status = outstanding.signum() <= 0 ? PAID : PARTIALLY_PAID;
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

    public String getPeriod() {
        return period;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getOutstanding() {
        return outstanding;
    }

    public String getStatus() {
        return status;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }
}
