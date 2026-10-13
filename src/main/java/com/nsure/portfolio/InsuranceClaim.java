package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "ins_claim")
public class InsuranceClaim extends CoverRecord {

    @Column(name = "claim_type", length = 80)
    private String claimType;

    @Column(length = 1000)
    private String description;

    @Column(name = "event_date", length = 20)
    private String eventDate;

    public String getClaimType() {
        return claimType;
    }

    public void setClaimType(String claimType) {
        this.claimType = claimType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEventDate() {
        return eventDate;
    }

    public void setEventDate(String eventDate) {
        this.eventDate = eventDate;
    }
}
