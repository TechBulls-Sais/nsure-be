package com.nsure.carecash;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One hospital event on a Care Cash policy, with the benefit worked out for it and the trace of how. */
@Entity
@Table(name = "cc_event")
public class CareCashEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "record_ref", nullable = false, length = 20)
    String ref;
    @Column(name = "policy_ref", nullable = false, length = 20)
    String policyRef;
    @Column(name = "customer_name", length = 150)
    String customerName;
    @Column(name = "life_name", nullable = false, length = 150)
    String lifeName;
    @Column(name = "event_type", nullable = false, length = 30)
    String eventType;
    @Column(nullable = false, length = 20)
    String cause;
    @Column(name = "admit_date", nullable = false)
    LocalDate admitDate;
    @Column(name = "discharge_date")
    LocalDate dischargeDate;
    @Column(name = "icu_days", nullable = false)
    int icuDays;
    @Column(nullable = false)
    boolean surgery;
    @Column(name = "daily_benefit", nullable = false, precision = 19, scale = 2)
    BigDecimal dailyBenefit;
    @Column(name = "stay_days", nullable = false)
    int stayDays;
    @Column(name = "paid_days", nullable = false)
    int paidDays;
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal amount;
    @Column(name = "waiver_months", nullable = false)
    int waiverMonths;
    @Column(nullable = false, length = 3000)
    String trace;
    @Column(nullable = false, length = 20)
    String status;
    @Column(name = "created_by", nullable = false, length = 100)
    String createdBy;
    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;
    @Column(name = "decided_by", length = 100)
    String decidedBy;
    @Column(name = "decided_at")
    LocalDateTime decidedAt;
    @Column(name = "decision_note", length = 500)
    String decisionNote;
    @Column(name = "payment_reference", length = 60)
    String paymentReference;
    @Column(name = "paid_at")
    LocalDateTime paidAt;
}
