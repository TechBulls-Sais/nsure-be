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

    @Column(name = "image_url", length = 255)
    private String imageUrl;

    @Column(name = "image_focus", length = 40)
    private String imageFocus;

    @Column(name = "need_key", length = 40)
    private String needKey;

    @Column(name = "need_label", length = 60)
    private String needLabel;

    @Column(name = "need_icon", length = 40)
    private String needIcon;

    @Column(length = 40)
    private String highlight;

    @Column(length = 300)
    private String summary;

    @Column(length = 300)
    private String fits;

    /** One benefit per line. */
    @Column(length = 1000)
    private String benefits;

    /** What the cover is paid per, e.g. "day" for hospital cash; blank for a lump sum. */
    @Column(name = "cover_unit", length = 20)
    private String coverUnit;

    /** Two-line landing page card headline, one line per row. */
    @Column(length = 120)
    private String tagline;

    /** Position on the public landing page; null when the product is not featured there. */
    @Column(name = "featured_order")
    private Integer featuredOrder;

    /** Funeral, Life Cover or Health, as on the demo server catalogue. */
    @Column(name = "product_type", length = 40)
    private String productType;

    /** Days after cover starts before a claim's waiting-period check passes (demo server default 30). */
    @Column(name = "waiting_period_days", nullable = false)
    private int waitingPeriodDays = 30;

    /** Days after a missed premium before the policy lapses (demo server default 14). */
    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays = 14;

    public Long getId() {
        return id;
    }

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

    public int getDisplayOrder() {
        return displayOrder;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getImageFocus() {
        return imageFocus;
    }

    public String getNeedKey() {
        return needKey;
    }

    public String getNeedLabel() {
        return needLabel;
    }

    public String getNeedIcon() {
        return needIcon;
    }

    public String getHighlight() {
        return highlight;
    }

    public String getSummary() {
        return summary;
    }

    public String getFits() {
        return fits;
    }

    public String getBenefits() {
        return benefits;
    }

    public String getCoverUnit() {
        return coverUnit;
    }

    public String getTagline() {
        return tagline;
    }

    public Integer getFeaturedOrder() {
        return featuredOrder;
    }

    public String getProductType() {
        return productType;
    }

    public int getWaitingPeriodDays() {
        return waitingPeriodDays;
    }

    public int getGracePeriodDays() {
        return gracePeriodDays;
    }
}
