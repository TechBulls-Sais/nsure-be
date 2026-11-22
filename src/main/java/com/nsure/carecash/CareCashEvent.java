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
    // NS-SH05-007: who the payment instruction pays, when it was sent and, if it failed or came back, why.
    @Column(name = "payee_name", length = 150)
    String payeeName;
    @Column(name = "payout_method", length = 40)
    String payoutMethod;
    @Column(name = "payout_account", length = 60)
    String payoutAccount;
    @Column(name = "payment_sent_by", length = 100)
    String paymentSentBy;
    @Column(name = "payment_sent_at")
    LocalDateTime paymentSentAt;
    @Column(name = "payment_failure", length = 500)
    String paymentFailure;
    // Section 07 detail: the insured life (by key), the rule version, readmission link, the days after a cover
    // anniversary, the procedure, death evidence, the premium waiver window and a reversal.
    @Column(name = "life_key", length = 40)
    String lifeKey;
    @Column(name = "rule_version")
    Integer ruleVersion;
    @Column(name = "pre_existing", nullable = false)
    int preExisting;
    @Column(nullable = false)
    int births = 1;
    @Column(name = "linked_event_ref", length = 20)
    String linkedEventRef;
    @Column(name = "days_next_year", nullable = false)
    int daysNextYear;
    @Column(name = "procedure_name", length = 150)
    String procedureName;
    @Column(name = "death_evidence", length = 300)
    String deathEvidence;
    @Column(name = "waiver_from")
    java.time.LocalDate waiverFrom;
    @Column(name = "waiver_to")
    java.time.LocalDate waiverTo;
    @Column(name = "reversed_by", length = 100)
    String reversedBy;
    @Column(name = "reversed_at")
    LocalDateTime reversedAt;
    @Column(name = "reversal_reason", length = 500)
    String reversalReason;
}
