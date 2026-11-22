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

/** One renewal quotation version: the rate change and the per-category terms offered, with the negotiation note. */
@Entity
@Table(name = "grp_renewal_version")
public class RenewalVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "renewal_id", nullable = false)
    SchemeRenewal renewal;

    @Column(name = "version_no", nullable = false)
    int versionNo;

    @Column(name = "rate_change_percent", nullable = false, precision = 7, scale = 2)
    BigDecimal rateChangePercent;

    @Column(name = "expiring_premium", nullable = false, precision = 15, scale = 2)
    BigDecimal expiringPremium;

    @Column(name = "annual_premium", nullable = false, precision = 15, scale = 2)
    BigDecimal annualPremium;

    @Column(name = "version_status", nullable = false, length = 20)
    String status;

    @Column(length = 500)
    String note;

    @Column(name = "created_by", nullable = false, length = 100)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;

    protected RenewalVersion() {
    }

    RenewalVersion(SchemeRenewal renewal, int versionNo, BigDecimal rateChangePercent, String note, String createdBy) {
        this.renewal = renewal;
        this.versionNo = versionNo;
        this.rateChangePercent = rateChangePercent;
        this.expiringPremium = BigDecimal.ZERO.setScale(2);
        this.annualPremium = BigDecimal.ZERO.setScale(2);
        this.status = "PROPOSED";
        this.note = note == null || note.length() <= 500 ? note : note.substring(0, 497) + "...";
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }
}
