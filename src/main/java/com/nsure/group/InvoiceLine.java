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

/**
 * One member's premium on a scheme invoice, keeping the cover, rate and days used so the charge can be explained:
 * accepted cover / 1000 × rate per mille × days covered / days in the month (AT-GL05-01). Never updated.
 */
@Entity
@Table(name = "grp_invoice_line")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SchemeInvoice invoice;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "member_key", nullable = false, length = 40)
    private String memberKey;

    @Column(name = "member_name", nullable = false, length = 130)
    private String memberName;

    @Column(name = "category_code", nullable = false, length = 20)
    private String categoryCode;

    @Column(name = "accepted_cover", nullable = false, precision = 15, scale = 2)
    private BigDecimal acceptedCover;

    @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
    private BigDecimal ratePerMille;

    @Column(name = "days_covered", nullable = false)
    private int daysCovered;

    @Column(name = "days_in_period", nullable = false)
    private int daysInPeriod;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal premium;

    protected InvoiceLine() {
    }

    InvoiceLine(SchemeInvoice invoice, SchemeMember member, BigDecimal ratePerMille, int daysCovered,
            int daysInPeriod, BigDecimal premium) {
        this.invoice = invoice;
        this.memberId = member.getId();
        this.memberKey = member.getMemberKey();
        this.memberName = member.fullName();
        this.categoryCode = member.getCategory().getCode();
        this.acceptedCover = member.getAcceptedCover();
        this.ratePerMille = ratePerMille;
        this.daysCovered = daysCovered;
        this.daysInPeriod = daysInPeriod;
        this.premium = premium;
    }

    public Long getId() {
        return id;
    }

    public SchemeInvoice getInvoice() {
        return invoice;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getMemberKey() {
        return memberKey;
    }

    public String getMemberName() {
        return memberName;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public BigDecimal getAcceptedCover() {
        return acceptedCover;
    }

    public BigDecimal getRatePerMille() {
        return ratePerMille;
    }

    public int getDaysCovered() {
        return daysCovered;
    }

    public int getDaysInPeriod() {
        return daysInPeriod;
    }

    public BigDecimal getPremium() {
        return premium;
    }
}
