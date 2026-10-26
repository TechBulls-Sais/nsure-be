package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_product")
public class InsuranceProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 40)
    private String icon;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false, length = 40)
    private String cover;

    @Column(nullable = false, length = 20)
    private String premium;

    @Column(name = "live_count", nullable = false)
    private int liveCount;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** Days after cover starts before a claim's waiting-period check passes (demo server default 30). */
    @Column(name = "waiting_period_days", nullable = false)
    private int waitingPeriodDays = 30;

    /** Days after a missed premium before the policy lapses (demo server default 14). */
    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays = 14;

    public String getName() {
        return name;
    }

    public String getIcon() {
        return icon;
    }

    public String getColor() {
        return color;
    }

    public String getDescription() {
        return description;
    }

    public String getCover() {
        return cover;
    }

    public String getPremium() {
        return premium;
    }

    public int getLiveCount() {
        return liveCount;
    }

    public void setLiveCount(int liveCount) {
        this.liveCount = liveCount;
    }

    public int getWaitingPeriodDays() {
        return waitingPeriodDays;
    }

    public int getGracePeriodDays() {
        return gracePeriodDays;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
