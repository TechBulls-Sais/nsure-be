package com.nsure.reinsurance;

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

/** One reinsurer's share of a treaty (NS-SH07-001); a treaty's shares add up to exactly 100%. */
@Entity
@Table(name = "ri_treaty_participant")
public class TreatyParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "treaty_id", nullable = false)
    private Treaty treaty;

    @Column(nullable = false, length = 150)
    private String reinsurer;

    @Column(name = "share_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal sharePercent;

    // "true" for the lead reinsurer (kept as text to stay portable across databases).
    @Column(nullable = false, length = 5)
    private String lead;

    protected TreatyParticipant() {
    }

    TreatyParticipant(Treaty treaty, String reinsurer, BigDecimal sharePercent, boolean lead) {
        this.treaty = treaty;
        this.reinsurer = reinsurer;
        this.sharePercent = sharePercent;
        this.lead = lead ? "true" : "false";
    }

    public String getReinsurer() {
        return reinsurer;
    }

    public BigDecimal getSharePercent() {
        return sharePercent;
    }

    public boolean isLead() {
        return "true".equals(lead);
    }
}
