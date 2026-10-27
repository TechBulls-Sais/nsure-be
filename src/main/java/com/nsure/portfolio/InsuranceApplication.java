package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_application")
public class InsuranceApplication extends CoverRecord implements TieredCover {

    @Column(length = 60)
    private String tier;

    @Column(name = "rate_table_version", length = 30)
    private String rateTableVersion;

    @Override
    public String getTier() {
        return tier;
    }

    @Override
    public void setTier(String tier) {
        this.tier = tier;
    }

    @Override
    public String getRateTableVersion() {
        return rateTableVersion;
    }

    @Override
    public void setRateTableVersion(String rateTableVersion) {
        this.rateTableVersion = rateTableVersion;
    }
}
