package com.nsure.carecash;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Days a life used before this system (migrated opening consumption, section 07 day limits, D17). */
@Entity(name = "CareCashOpeningDays")
@Table(name = "cc_opening_days")
class CareCashOpeningDays {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "policy_ref", nullable = false, length = 20)
    String policyRef;
    @Column(name = "life_key", nullable = false, length = 40)
    String lifeKey;
    @Column(name = "life_name", nullable = false, length = 150)
    String lifeName;
    @Column(name = "lifetime_days", nullable = false)
    int lifetimeDays;
    @Column(name = "year_days", nullable = false)
    int yearDays;
    @Column(name = "as_of", nullable = false)
    LocalDate asOf;
    @Column(nullable = false, length = 300)
    String note;
    @Column(name = "recorded_by", nullable = false, length = 100)
    String recordedBy;
    @Column(name = "recorded_at", nullable = false)
    LocalDateTime recordedAt;
}
