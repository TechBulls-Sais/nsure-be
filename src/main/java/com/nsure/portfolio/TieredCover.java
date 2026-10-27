package com.nsure.portfolio;

/** A cover record that carries the product tier it was taken out on. */
public interface TieredCover {

    String getTier();

    void setTier(String tier);

    /** The rate table version that priced it ("RT-00001-v1"), or null for a flat-priced tier. */
    String getRateTableVersion();

    void setRateTableVersion(String rateTableVersion);
}
