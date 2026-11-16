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
            BigDecimal premiumsPaidAmount) {
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
            String ceaseReason) {
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
            LocalDateTime updatedAt) {
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
            List<String> endorsementTypes) {
    }

    public record RunResult(int created, List<String> refs) {
    }

    public record Summary(long endorsementsHeld, long reinstatementsPending, long benefitsDue, long benefitsApproved,
            long reviewsOpen) {
    }
}
