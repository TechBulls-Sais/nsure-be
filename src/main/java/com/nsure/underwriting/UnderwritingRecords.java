package com.nsure.underwriting;

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

/** IL02 underwriting records (Nsure Life NS-IL02-001..006). Fields are read and written by {@link UnderwritingService}. */
public final class UnderwritingRecords {

    private UnderwritingRecords() {
    }

    /** The versioned rules a product's applications are assessed against; the row with no product is the default. */
    @Entity(name = "UwRuleSet")
    @Table(name = "uw_rule_set")
    public static class RuleSet {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(length = 60)
        String product;
        @Column(name = "version_label", nullable = false, length = 40)
        String versionLabel;
        @Column(name = "min_entry_age", nullable = false)
        int minEntryAge;
        @Column(name = "max_entry_age", nullable = false)
        int maxEntryAge;
        @Column(name = "auto_max_sum_assured", nullable = false, precision = 19, scale = 2)
        BigDecimal autoMaxSumAssured;
        @Column(name = "medical_evidence_above", nullable = false, precision = 19, scale = 2)
        BigDecimal medicalEvidenceAbove;
        @Column(name = "aggregate_exposure_limit", nullable = false, precision = 19, scale = 2)
        BigDecimal aggregateExposureLimit;
        @Column(name = "refer_tobacco", nullable = false)
        boolean referTobacco;
        @Column(name = "refer_treatment", nullable = false)
        boolean referTreatment;
        /** Disclosure codes whose YES answer refers the case, comma-separated (NS-IL02-001). */
        @Column(name = "refer_disclosure_codes", length = 300)
        String referDisclosureCodes;
        /** Occupation words that refer the case, comma-separated (NS-IL02-001). */
        @Column(name = "refer_occupations", length = 1000)
        String referOccupations;
        /** The largest loading an offer may carry under these rules (NS-IL02-005). */
        @Column(name = "max_loading_percent", nullable = false, precision = 7, scale = 2)
        BigDecimal maxLoadingPercent;
        /** Days an underwriting decision stands before it must be reassessed (NS-IL02-003/006). */
        @Column(name = "decision_validity_days", nullable = false)
        int decisionValidityDays;
        @Column(name = "updated_by", length = 100)
        String updatedBy;
        @Column(name = "updated_at")
        LocalDateTime updatedAt;
    }

    /** A proposed rule set change: applied only when a second person approves it; every proposal is kept. */
    @Entity(name = "UwRuleChange")
    @Table(name = "uw_rule_change")
    public static class RuleChange {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "rule_set_id", nullable = false)
        Long ruleSetId;
        @Column(name = "version_label", nullable = false, length = 40)
        String versionLabel;
        @Column(nullable = false, length = 4000)
        String proposal;
        @Column(length = 4000)
        String previous;
        @Column(nullable = false, length = 20)
        String status;
        @Column(name = "proposed_by", nullable = false, length = 100)
        String proposedBy;
        @Column(name = "proposed_at", nullable = false)
        LocalDateTime proposedAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "decision_note", length = 500)
        String decisionNote;
    }

    /** One name on a screening list. No real screening provider is connected: the demo lists are simulated. */
    @Entity(name = "UwWatchEntry")
    @Table(name = "uw_watchlist")
    public static class WatchEntry {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "full_name", nullable = false, length = 150)
        String fullName;
        @Column(name = "list_name", nullable = false, length = 80)
        String listName;
        @Column(length = 300)
        String note;
    }

    /** The largest sum assured a role may decide (NS-IL02-004). A role without a row is not limited. */
    @Entity(name = "UwAuthority")
    @Table(name = "uw_authority")
    public static class Authority {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "role_id", nullable = false)
        Long roleId;
        @Column(name = "max_sum_assured", nullable = false, precision = 19, scale = 2)
        BigDecimal maxSumAssured;
        /** The largest loading the role may offer; null is not limited. */
        @Column(name = "max_loading_percent", precision = 7, scale = 2)
        BigDecimal maxLoadingPercent;
        /** The decision types the role may make, comma-separated; null is every type. */
        @Column(name = "decision_types", length = 200)
        String decisionTypes;
        @Column(name = "updated_by", length = 100)
        String updatedBy;
        @Column(name = "updated_at")
        LocalDateTime updatedAt;
    }

    /** One application's underwriting: the facts assessed, why it was referred, the decision and the offer. */
    @Entity(name = "UwCase")
    @Table(name = "uw_case")
    public static class Case {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "application_ref", nullable = false, length = 20)
        String applicationRef;
        @Column(name = "customer_ref", length = 40)
        String customerRef;
        @Column(name = "customer_name", length = 150)
        String customerName;
        @Column(nullable = false, length = 60)
        String product;
        @Column(name = "sum_assured", nullable = false, precision = 19, scale = 2)
        BigDecimal sumAssured;
        @Column(name = "applied_premium", nullable = false, precision = 19, scale = 2)
        BigDecimal appliedPremium;
        @Column(name = "aggregate_exposure", nullable = false, precision = 19, scale = 2)
        BigDecimal aggregateExposure;
        @Column(name = "entry_age")
        Integer entryAge;
        @Column(name = "rule_set_version", nullable = false, length = 40)
        String ruleSetVersion;
        @Column(nullable = false, length = 30)
        String status;
        @Column(name = "referral_reasons", length = 2000)
        String referralReasons;
        @Column(length = 30)
        String decision;
        @Column(name = "loading_percent", precision = 7, scale = 2)
        BigDecimal loadingPercent;
        @Column(length = 1000)
        String exclusion;
        @Column(name = "postpone_until")
        LocalDate postponeUntil;
        @Column(name = "decision_reason", length = 1000)
        String decisionReason;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "offer_version", nullable = false)
        int offerVersion;
        @Column(name = "offer_premium", precision = 19, scale = 2)
        BigDecimal offerPremium;
        @Column(name = "offer_status", nullable = false, length = 20)
        String offerStatus;
        @Column(name = "offer_accepted_by", length = 100)
        String offerAcceptedBy;
        @Column(name = "offer_accepted_at")
        LocalDateTime offerAcceptedAt;
        @Column(name = "assigned_to", length = 100)
        String assignedTo;
        @Column(length = 100)
        String occupation;
        /** The decision must be reassessed after this date (NS-IL02-003/006). */
        @Column(name = "decision_valid_until")
        LocalDate decisionValidUntil;
        /** NOT_SCREENED, CLEAR, HIT, CLEARED or CONFIRMED (NS-IL01-006, NS-IL02-006). */
        @Column(name = "screening_status", nullable = false, length = 20)
        String screeningStatus = "NOT_SCREENED";
        @Column(name = "screening_detail", length = 500)
        String screeningDetail;
        @Column(name = "screening_resolved_by", length = 100)
        String screeningResolvedBy;
        @Column(name = "screening_resolved_at")
        LocalDateTime screeningResolvedAt;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        @Column(name = "updated_at", nullable = false)
        LocalDateTime updatedAt;
        @Version
        @Column(name = "row_version", nullable = false)
        long version;
    }

    /** Evidence asked of one life (NS-IL02-002). Restricted evidence is clinical: only underwriters see its detail. */
    @Entity(name = "UwEvidence")
    @Table(name = "uw_evidence")
    public static class Evidence {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "case_ref", nullable = false, length = 20)
        String caseRef;
        @Column(name = "life_name", nullable = false, length = 150)
        String lifeName;
        /** The application's insured life (policy_life_cover id) the evidence is asked of, when known. */
        @Column(name = "life_id")
        Long lifeId;
        @Column(name = "reminder_count", nullable = false)
        int reminderCount;
        @Column(name = "last_reminded_at")
        LocalDateTime lastRemindedAt;
        @Column(nullable = false, length = 150)
        String requirement;
        @Column(nullable = false)
        boolean restricted;
        @Column(nullable = false, length = 20)
        String status;
        @Column(name = "due_date", nullable = false)
        LocalDate dueDate;
        @Column(name = "requested_by", nullable = false, length = 100)
        String requestedBy;
        @Column(name = "requested_at", nullable = false)
        LocalDateTime requestedAt;
        @Column(name = "resolved_by", length = 100)
        String resolvedBy;
        @Column(name = "resolved_at")
        LocalDateTime resolvedAt;
        @Column(length = 500)
        String note;
    }

    /** Every decision ever made on a case, in order; a later decision never overwrites an earlier one. */
    @Entity(name = "UwDecision")
    @Table(name = "uw_decision")
    public static class Decision {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "case_ref", nullable = false, length = 20)
        String caseRef;
        @Column(name = "sequence_no", nullable = false)
        int sequenceNo;
        @Column(nullable = false, length = 30)
        String decision;
        @Column(name = "loading_percent", precision = 7, scale = 2)
        BigDecimal loadingPercent;
        @Column(length = 1000)
        String exclusion;
        @Column(name = "postpone_until")
        LocalDate postponeUntil;
        @Column(length = 1000)
        String reason;
        @Column(name = "offer_version", nullable = false)
        int offerVersion;
        @Column(name = "offer_premium", precision = 19, scale = 2)
        BigDecimal offerPremium;
        @Column(name = "decided_by", nullable = false, length = 100)
        String decidedBy;
        @Column(name = "decided_at", nullable = false)
        LocalDateTime decidedAt;
        // What the decision rested on (IL02): the life, the benefit and the evidence as it stood.
        @Column(name = "life_ref", length = 20)
        String lifeRef;
        @Column(length = 200)
        String benefit;
        @Column(name = "sum_assured", precision = 19, scale = 2)
        BigDecimal sumAssured;
        @Column(name = "evidence_basis", length = 1000)
        String evidenceBasis;
    }
}
