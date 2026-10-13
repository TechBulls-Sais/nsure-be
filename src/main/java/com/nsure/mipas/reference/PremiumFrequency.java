package com.nsure.mipas.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "premium_frequency")
public class PremiumFrequency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "frequency_name", nullable = false, unique = true, length = 50)
    private String frequencyName;

    @Column(nullable = false)
    private int months;

    @Column(name = "short_label", length = 20)
    private String shortLabel;

    @Column(name = "period_word", length = 20)
    private String periodWord;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected PremiumFrequency() {
    }

    public Long getId() {
        return id;
    }

    public String getFrequencyName() {
        return frequencyName;
    }

    public int getMonths() {
        return months;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public String getPeriodWord() {
        return periodWord;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
