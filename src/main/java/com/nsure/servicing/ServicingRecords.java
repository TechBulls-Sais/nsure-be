package com.nsure.servicing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** IL03/IL04 policy servicing records. Fields are read and written by {@link ServicingService}. */
public final class ServicingRecords {

    private ServicingRecords() {
    }

    /** A requested change to an in-force policy, with the effect it was previewed to have (NS-IL03-002/003). */
    @Entity(name = "Endorsement")
    @Table(name = "pol_endorsement")
    public static class Endorsement {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "policy_ref", nullable = false, length = 20)
        String policyRef;
        @Column(name = "endorsement_type", nullable = false, length = 30)
        String type;
        @Column(name = "effective_date", nullable = false)
        LocalDate effectiveDate;
        @Column(nullable = false)
        boolean backdated;
        @Column(nullable = false, length = 30)
        String status;
        @Column(nullable = false, length = 500)
        String reason;
        @Column(name = "changes_json", nullable = false, length = 4000)
        String changesJson;
        @Column(name = "before_summary", length = 1000)
        String beforeSummary;
        @Column(name = "after_summary", length = 1000)
        String afterSummary;
        @Column(name = "old_sum_assured", precision = 19, scale = 2)
        BigDecimal oldSumAssured;
        @Column(name = "new_sum_assured", precision = 19, scale = 2)
        BigDecimal newSumAssured;
        @Column(name = "old_premium", precision = 19, scale = 2)
        BigDecimal oldPremium;
        @Column(name = "new_premium", precision = 19, scale = 2)
        BigDecimal newPremium;
        @Column(name = "premium_adjustment", precision = 19, scale = 2)
        BigDecimal premiumAdjustment;
        @Column(name = "commission_effect", precision = 19, scale = 2)
        BigDecimal commissionEffect;
        @Column(name = "reinsurance_effect", precision = 19, scale = 2)
        BigDecimal reinsuranceEffect;
        @Column(name = "hold_reason", length = 500)
        String holdReason;
        @Column(name = "requested_by", nullable = false, length = 100)
        String requestedBy;
        @Column(name = "requested_at", nullable = false)
        LocalDateTime requestedAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "decision_note", length = 500)
        String decisionNote;
        @Column(name = "applied_at")
        LocalDateTime appliedAt;
        @Version
        @Column(name = "row_version", nullable = false)
        long version;
    }

    /** A contractual benefit a product defines (NS-IL03-005). A product without a rule does not offer the benefit. */
    @Entity(name = "BenefitRule")
    @Table(name = "prod_benefit_rule")
    public static class BenefitRule {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(nullable = false, length = 60)
        String product;
        @Column(name = "benefit_type", nullable = false, length = 30)
        String benefitType;
        @Column(name = "interval_months")
        Integer intervalMonths;
        @Column(name = "premium_multiple", precision = 7, scale = 2)
        BigDecimal premiumMultiple;
        @Column(precision = 7, scale = 2)
        BigDecimal percent;
        @Column(name = "min_months")
        Integer minMonths;
        @Column(name = "term_years")
        Integer termYears;
        @Column(nullable = false, length = 500)
        String description;
        @Column(nullable = false)
        boolean active;
        @Column(name = "updated_by", length = 100)
        String updatedBy;
        @Column(name = "updated_at")
        LocalDateTime updatedAt;
    }

    /** How a product's policies are serviced: reinstatement, backdating and age limits for insured lives. */
    @Entity(name = "ServicingRule")
    @Table(name = "prod_servicing_rule")
    public static class ServicingRule {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(nullable = false, length = 60)
        String product;
        @Column(name = "reinstatement_needs_approval", nullable = false)
        boolean reinstatementNeedsApproval;
        @Column(name = "max_backdate_days", nullable = false)
        int maxBackdateDays;
        @Column(name = "child_review_age", nullable = false)
        int childReviewAge;
        @Column(name = "max_cover_age", nullable = false)
        int maxCoverAge;
        /** KEEP_EARLIEST or RESTART: a re-added life's waiting period (NS-IL04-003). */
        @Column(name = "life_continuity", nullable = false, length = 20)
        String lifeContinuity = "KEEP_EARLIEST";
        /** BACKDATED or ALWAYS: when removing a life needs a second person (NS-IL04-003). */
        @Column(name = "removal_approval", nullable = false, length = 20)
        String removalApproval = "BACKDATED";
        /** Days ahead an age review or cessation task is raised (NS-IL04-005). */
        @Column(name = "review_lead_days", nullable = false)
        int reviewLeadDays = 90;
        /** Months a policy stays in force without cover before it lapses (NS-IL03-004). */
        @Column(name = "no_cover_months", nullable = false)
        int noCoverMonths = 3;
        /** Days after cover starts within which a cancellation is a cooling-off cancellation (NS-IL03-004). */
        @Column(name = "cooling_off_days", nullable = false)
        int coolingOffDays = 30;
        @Column(name = "updated_by", length = 100)
        String updatedBy;
        @Column(name = "updated_at")
        LocalDateTime updatedAt;
    }

    /** A contractual payout that has fallen due on a policy (cashback, return of premium, maturity, surrender). */
    @Entity(name = "BenefitEvent")
    @Table(name = "pol_benefit_event")
    public static class BenefitEvent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "policy_ref", nullable = false, length = 20)
        String policyRef;
        @Column(name = "customer_name", length = 150)
        String customerName;
        @Column(nullable = false, length = 60)
        String product;
        @Column(name = "benefit_type", nullable = false, length = 30)
        String benefitType;
        @Column(name = "due_date", nullable = false)
        LocalDate dueDate;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal amount;
        @Column(nullable = false, length = 1000)
        String basis;
        @Column(nullable = false, length = 20)
        String status;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "decision_note", length = 500)
        String decisionNote;
        @Column(name = "paid_by", length = 100)
        String paidBy;
        @Column(name = "paid_at")
        LocalDateTime paidAt;
        @Column(name = "payment_reference", length = 60)
        String paymentReference;
    }

    /** A dated task to review an insured life approaching an age limit (NS-IL04-005). */
    @Entity(name = "LifeReview")
    @Table(name = "pol_life_review")
    public static class LifeReview {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "policy_ref", nullable = false, length = 20)
        String policyRef;
        @Column(name = "life_id", nullable = false)
        Long lifeId;
        @Column(name = "life_name", nullable = false, length = 150)
        String lifeName;
        @Column(name = "review_type", nullable = false, length = 30)
        String reviewType;
        @Column(name = "due_date", nullable = false)
        LocalDate dueDate;
        @Column(nullable = false, length = 20)
        String status;
        @Column(length = 500)
        String note;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        @Column(name = "closed_by", length = 100)
        String closedBy;
        @Column(name = "closed_at")
        LocalDateTime closedAt;
    }
}
