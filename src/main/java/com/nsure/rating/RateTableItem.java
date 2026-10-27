package com.nsure.rating;

import com.nsure.reference.RelationshipType;
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

/** One cell of the rate grid: a life type and an inclusive age band, its cover and monthly premium. */
@Entity
@Table(name = "ins_rate_table_item")
public class RateTableItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private RateTableVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_type_id", nullable = false)
    private RelationshipType lifeType;

    @Column(name = "age_from", nullable = false)
    private int ageFrom;

    @Column(name = "age_to", nullable = false)
    private int ageTo;

    @Column(name = "cover_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal coverAmount;

    @Column(name = "monthly_premium", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPremium;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected RateTableItem() {
    }

    RateTableItem(RateTableVersion version, RelationshipType lifeType, int ageFrom, int ageTo,
                  BigDecimal coverAmount, BigDecimal monthlyPremium, int displayOrder) {
        this.version = version;
        this.lifeType = lifeType;
        this.ageFrom = ageFrom;
        this.ageTo = ageTo;
        this.coverAmount = coverAmount;
        this.monthlyPremium = monthlyPremium;
        this.displayOrder = displayOrder;
    }

    public RateTableVersion getVersion() {
        return version;
    }

    public RelationshipType getLifeType() {
        return lifeType;
    }

    public int getAgeFrom() {
        return ageFrom;
    }

    public int getAgeTo() {
        return ageTo;
    }

    public BigDecimal getCoverAmount() {
        return coverAmount;
    }

    public BigDecimal getMonthlyPremium() {
        return monthlyPremium;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
