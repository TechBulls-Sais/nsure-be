package com.nsure.rating;

import com.nsure.portfolio.CoverTier;
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

/** Who a tier may cover: one relationship, its age range and how many such lives (spec 03 section 2.6). */
@Entity
@Table(name = "ins_life_assured_rule")
public class LifeAssuredRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tier_id", nullable = false)
    private CoverTier tier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_type_id", nullable = false)
    private RelationshipType relationship;

    @Column(name = "min_age")
    private Integer minAge;

    @Column(name = "max_age")
    private Integer maxAge;

    @Column(name = "max_count", nullable = false)
    private int maxCount;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected LifeAssuredRule() {
    }

    /** The entry ages a released product configuration sets for a plan's lives (NS-SH02-001). */
    public LifeAssuredRule(CoverTier tier, RelationshipType relationship, Integer minAge, Integer maxAge, int maxCount,
            int displayOrder) {
        this.tier = tier;
        this.relationship = relationship;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.maxCount = maxCount;
        this.displayOrder = displayOrder;
    }

    public void ages(Integer minAge, Integer maxAge) {
        this.minAge = minAge;
        this.maxAge = maxAge;
    }

    public CoverTier getTier() {
        return tier;
    }

    public RelationshipType getRelationship() {
        return relationship;
    }

    public Integer getMinAge() {
        return minAge;
    }

    public Integer getMaxAge() {
        return maxAge;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
