package com.nsure.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the policy servicing API returns. */
public final class ServicingViews {

    private ServicingViews() {
    }

    /** The contract, with its cover and payment positions reported separately from its status (NS-IL03-006). */
    public record PolicyView(
            String ref,
            String customerRef,
            String customerName,
            String product,
            String tier,
            BigDecimal sumAssured,
            BigDecimal premium,
            String premiumFrequency,
            BigDecimal modalPremium,
            String contractStatus,
            String coverStatus,
            String paymentStatus,
            LocalDate coverStartDate,
            LocalDate nextPremiumDue,
            LocalDate gracePeriodEnd,
            String terminationReason,
            LocalDate terminatedOn,
            String payerName,
            String payerRelationship,
            String underwritingTerms,
            long premiumsPaid,
            BigDecimal premiumsPaidAmount,
            // Section 06: the selected term and the date cover ends (term or cessation age, whichever is earlier).
            Integer termYears,
            LocalDate coverEndDate) {
    }

    public record LifeView(
            Long id,
            String fullName,
            String relationship,
            boolean mainInsured,
            LocalDate dateOfBirth,
            int ageAtEntry,
            Integer ageNow,
            BigDecimal coverAmount,
            BigDecimal monthlyPremium,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDate waitingPeriodStart,
            String ceaseReason,
            // A re-added person's first cover row (limits continue from it) and the date-of-birth evidence.
            Long continuityOf,
            String dobEvidence) {
    }

    /** What an endorsement would do, before anything is changed (NS-IL03-003). */
    public record Preview(
            String type,
            LocalDate effectiveDate,
            boolean backdated,
            String before,
            String after,
            BigDecimal oldSumAssured,
            BigDecimal newSumAssured,
            BigDecimal oldPremium,
            BigDecimal newPremium,
            // The difference for the rest of the current premium period.
            BigDecimal premiumAdjustment,
            BigDecimal commissionEffect,
            BigDecimal reinsuranceEffect,
            // APPLY, APPROVAL, UNDERWRITING or IMPACT_REVIEW.
            String route,
            String holdReason,
            List<String> documents) {
    }

    public record EndorsementView(
            String ref,
            String policyRef,
            String customerName,
            String product,
            String type,
            LocalDate effectiveDate,
            boolean backdated,
            String status,
            String reason,
            String before,
            String after,
            BigDecimal oldSumAssured,
            BigDecimal newSumAssured,
            BigDecimal oldPremium,
            BigDecimal newPremium,
            BigDecimal premiumAdjustment,
            BigDecimal commissionEffect,
            BigDecimal reinsuranceEffect,
            String holdReason,
            String requestedBy,
            LocalDateTime requestedAt,
            String decidedBy,
            LocalDateTime decidedAt,
            String decisionNote,
            LocalDateTime appliedAt) {
    }

    public record BenefitRuleView(
            Long id,
            String product,
            String benefitType,
            Integer intervalMonths,
            BigDecimal premiumMultiple,
            BigDecimal percent,
            Integer minMonths,
            Integer termYears,
            String description,
            boolean active,
            String updatedBy,
            LocalDateTime updatedAt) {
    }

    public record ServicingRuleView(
            Long id,
            String product,
            boolean reinstatementNeedsApproval,
            int maxBackdateDays,
            int childReviewAge,
            int maxCoverAge,
            String updatedBy,
            LocalDateTime updatedAt,
            String lifeContinuity,
            String removalApproval,
            int reviewLeadDays,
            int noCoverMonths,
            int coolingOffDays,
            String termOptions) {
    }

    /** A benefit the product's rules will make due, shown before it falls due. */
    public record ScheduledBenefit(String benefitType, LocalDate dueDate, BigDecimal amount, String basis) {
    }

    public record BenefitEventView(
            String ref,
            String policyRef,
            String customerName,
            String product,
            String benefitType,
            LocalDate dueDate,
            BigDecimal amount,
            String basis,
            String status,
            LocalDateTime createdAt,
            String decidedBy,
            LocalDateTime decidedAt,
            String decisionNote,
            String paidBy,
            LocalDateTime paidAt,
            String paymentReference) {
    }

    public record LifeReviewView(
            Long id,
            String policyRef,
            Long lifeId,
            String lifeName,
            String reviewType,
            LocalDate dueDate,
            String status,
            String note,
            String closedBy,
            LocalDateTime closedAt) {
    }

    public record SurrenderQuote(String policyRef, BigDecimal value, String basis, long monthsInForce) {
    }

    public record ServicingView(
            PolicyView policy,
            List<LifeView> lives,
            List<EndorsementView> endorsements,
            // The contractual benefits this product defines; an absent type is not offered at all.
            List<String> benefitsOffered,
            boolean surrenderOffered,
            boolean paidUpOffered,
            List<ScheduledBenefit> schedule,
            List<BenefitEventView> benefitEvents,
            List<LifeReviewView> reviews,
            List<String> endorsementTypes,
            // IL04 roster: per-life limits and use, claims, underwriting outcome and eligibility warnings.
            Roster roster,
            // IL03: every beneficiary designation, current and removed, with its dates (no contact or bank details).
            List<BeneficiaryEpisode> beneficiaryHistory,
            // NS-IL04-006: each insured life's own consent decisions about sharing their medical evidence.
            List<ConsentView> consents,
            // NS-IL03-001: the issue terms frozen when cover started (null before then), and its fingerprint.
            com.fasterxml.jackson.databind.JsonNode inceptionSnapshot,
            String inceptionSha256,
            LocalDateTime inceptionTakenAt) {
    }

    /** One insured life's consent decision (NS-IL04-006); the newest per life applies. */
    public record ConsentView(Long id, Long lifeId, String lifeName, boolean adult, String scope, String status,
            String evidence, String recordedBy, LocalDateTime recordedAt) {
    }

    public record BeneficiaryEpisode(
            String fullName,
            String relationship,
            boolean primary,
            LocalDate effectiveFrom,
            LocalDate removedFrom,
            String removedBy) {
    }

    /** One life's standing on the roster (NS-IL04-004): benefit limit, use, claims, underwriting, warnings. */
    public record LifeStanding(
            Long lifeId,
            BigDecimal limit,
            BigDecimal used,
            BigDecimal remaining,
            long claims,
            String underwriting,
            List<String> warnings) {
    }

    /** The policy-level aggregate limit every life shares, plan count warnings, and each life's standing. */
    public record Roster(
            BigDecimal policyLimit,
            BigDecimal policyUsed,
            BigDecimal policyRemaining,
            List<String> warnings,
            List<LifeStanding> lives) {
    }

    public record RunResult(int created, List<String> refs) {
    }

    public record Summary(long endorsementsHeld, long reinstatementsPending, long benefitsDue, long benefitsApproved,
            long reviewsOpen) {
    }
}
