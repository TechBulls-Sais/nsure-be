package com.nsure.portfolio;

import com.nsure.rating.RateTableViews;
import java.math.BigDecimal;
import java.util.List;

public final class PortfolioViews {

    private PortfolioViews() {
    }

    public record CustomerView(
            String id,
            String name,
            String email,
            String phone,
            String product,
            int policies,
            String status,
            String color,
            String kycStatus,
            String loginUsername,
            String poiType,
            String poiNumber,
            String dateOfBirth,
            String eddStatus,
            List<CustomerTierView> tiers,
            // Why staff rejected the KYC or EDD review; empty unless rejected.
            String kycRejectionReason,
            String eddRejectionReason,
            // Created although another customer holds the same identity number: the match and why (NS-SH01-002).
            String duplicateReview
    ) {
    }

    /** One policy's tier, newest first, for the customer directory Tier column. */
    public record CustomerTierView(
            String policy,
            String product,
            String tier
    ) {
    }

    public record ShopView(
            CustomerView customer,
            List<ProductView> products,
            // Accepted cover only; a request shows here once staff accept it.
            List<CoverView> policies,
            // Cover the customer asked for that staff have not accepted: awaiting review, or rejected with a reason.
            List<CoverView> requests,
            // Claims on the customer's policies, newest first.
            List<ClaimView> claims
    ) {
    }

    public record CoverView(
            String id,
            String name,
            String product,
            BigDecimal amount,
            BigDecimal premium,
            String status,
            String date,
            String tier,
            String rateTableVersion,
            // The covered lives a rate-table premium was summed from (spec 03 R-16); empty on a flat-priced record.
            List<LifeCoverView> lives,
            // Who accepted or declined a customer-bought policy, and the reason given; empty otherwise.
            String reviewedBy,
            String reviewNote,
            // Policy workflow (PolicyWorkflow); empty on applications and receipts.
            String coverStartDate,
            String nextPremiumDue,
            String gracePeriodEnd,
            String paymentDueBy,
            String lapseDate,
            String paymentMethod,
            // Active, past its due date and still within the grace period.
            boolean inGrace,
            List<BeneficiaryView> beneficiaries,
            // Documents filed on a policy (generated schedules); empty on applications and receipts.
            List<DocumentView> documents,
            // Spec 01 BR-23: the product's beneficiary limit (default 5; Tap Cover 1).
            int maxBeneficiaries,
            // The accepted quote this application or policy was priced from (NS-IL01-001).
            String quoteRef,
            Integer quoteVersion,
            // The customer record it belongs to: screens link records by this reference, never by name.
            String customerRef
    ) {
    }

    /** One version of a stored quote, with its calculation trace (NS-IL01-001, NS-SH03-005). */
    public record QuoteRecordView(String ref, int version, boolean latest, String customerRef, String customerName,
            String product, String tier, BigDecimal sumAssured, BigDecimal premium, String currency,
            String pricingMethod, String rateVersion, java.time.LocalDate pricedOn, java.time.LocalDate validUntil, boolean expired,
            String status, String inputHash, String serviceVersion, com.fasterxml.jackson.databind.JsonNode trace,
            String differences, String acceptedBy, java.time.LocalDateTime acceptedAt, String acceptanceNote,
            String applicationRef, String policyRef, String createdBy, java.time.LocalDateTime createdAt) {
    }

    /** One disclosure question and the answer given, if any (NS-IL01-003). */
    public record DisclosureItem(String code, String question, String answerType, boolean mandatory, String answer) {
    }

    /** One piece of evidence an application needs, and whether it is in. */
    public record ApplicationEvidenceItem(String documentType, boolean mandatory, boolean received, String note,
            String recordedBy, java.time.LocalDateTime recordedAt, String satisfiedBy) {
    }

    /** What an application captured, and what still stands between it and submission or issue (NS-IL01-002/003). */
    public record ApplicationChecklistView(String ref, String status, String proposer, String payer, String mandateMethod,
            String mandateInstitution, String mandateAccount, Integer mandateDebitDay, String channel,
            String intermediaryCode, String declaredBy, java.time.LocalDateTime declaredAt, String submittedBy,
            java.time.LocalDateTime submittedAt, List<DisclosureItem> disclosures, List<ApplicationEvidenceItem> evidence,
            List<String> submissionProblems, List<String> missingEvidence,
            // Submitted before disclosures and the declaration were captured: they were never recorded for it.
            boolean preCapture) {
    }

    /** A stored quote priced again under its own rate version: whether it reproduces the booked amount. */
    public record QuoteReplayView(String ref, int version, String rateVersion, BigDecimal original, BigDecimal replayed,
            boolean reproduced, String note) {
    }

    public record BeneficiaryView(
            Long id,
            String fullName,
            String relationship,
            String nationalId,
            String mobile,
            String payoutMethod,
            String accountRef,
            boolean primary
    ) {
    }

    /** One covered life: relationship, age at entry and the cover and monthly premium its rate row gave it. */
    public record LifeCoverView(
            String relationship,
            String fullName,
            String dateOfBirth,
            int age,
            BigDecimal coverAmount,
            BigDecimal monthlyPremium
    ) {
    }

    /**
     * A server-side price for a tier and its lives, as the record would be saved. Not rated: the tier's flat
     * premium, and no lives.
     */
    public record QuoteView(
            String product,
            String tier,
            int cover,
            boolean rated,
            String rateTableVersion,
            List<LifeCoverView> lives,
            BigDecimal premium
    ) {
    }

    public record ClaimView(
            String id,
            String name,
            String product,
            BigDecimal amount,
            BigDecimal premium,
            String status,
            String date,
            String type,
            String description,
            String eventDate,
            String policy,
            String claimant,
            String claimantMobile,
            String claimantRelationship,
            // STP or Manual Review, and why a claim whose checks all passed was not approved straight through.
            String processingType,
            String stpIneligibleReason,
            List<CheckView> checks,
            PayeeView payee,
            String recommendedBy,
            String adjudicatedBy,
            Boolean fourEyesApplied,
            String rejectionReason,
            BigDecimal payoutAmount,
            String payoutReference,
            String paidAt,
            // Workflow actions legal from the current state (assess, approve, reject, pay), before permissions.
            List<String> actions,
            List<DocumentView> documents,
            // Why it was approved although Beneficiary Match or No Duplicate failed; empty when none failed.
            String checkOverrideReason,
            // Set on an approved or paid claim that went through despite a failed hard check, or with no checks
            // run ("Paid despite failed checks: Policy Active, ..."); empty otherwise. ClaimWorkflow.auditFlag.
            String auditFlag,
            // Spec 12 C-12: whether every STP gate passed, and the coded gate that stopped it (CHECKS_FAILED,
            // STP_DISABLED, OVER_CEILING, NO_VERIFIED_PAYOUT, PRODUCT_OPT_OUT, CLAIM_LIMIT).
            Boolean stpEligible,
            String stpIneligibleCode,
            // The written reason when the assessor approved their own claim under the break-glass authority (C-9).
            String fourEyesOverrideReason,
            // Send back in If-Match with a claim action (NS-TXN-001): a claim changed meanwhile is refused with 409.
            long version,
            // SH05: the covered life, the case reserve, the evidence checklist and other claims on the policy.
            String coveredLife,
            BigDecimal reserve,
            List<EvidenceItem> evidence,
            List<RelatedClaim> related
    ) {
    }

    /** One document a claim type needs, and whether a current copy is on file (NS-SH05-003). */
    public record EvidenceItem(String documentType, boolean mandatory, boolean received) {
    }

    /** Another claim on the same policy; {@code possibleDuplicate} when it is for the same life within a week. */
    public record RelatedClaim(String ref, String status, String eventDate, String coveredLife, String type,
            boolean possibleDuplicate) {
    }

    /** A claim's reserve history, decisions and the benefit limits it draws on (NS-SH05-003/004/005). */
    public record ClaimFinancialsView(String claimRef, BigDecimal reserve, List<ReserveMovementView> movements,
            BigDecimal lifeLimit, BigDecimal lifeUsed, BigDecimal policyLimit, BigDecimal policyUsed,
            List<ClaimDecisionView> decisions) {
    }

    /** One decision on a claim; {@code supersededBy} names the later decision that set it aside (§09 reopen). */
    public record ClaimDecisionView(int sequence, String decision, String reason, BigDecimal amount, String decidedBy,
            java.time.LocalDateTime decidedAt, Integer supersededBy) {
    }

    public record ReserveMovementView(BigDecimal change, BigDecimal balance, String reason, String by,
            java.time.LocalDateTime at) {
    }

    /**
     * One file on a policy or claim; download it from .../documents/{id}. {@code status} is CURRENT or SUPERSEDED (by
     * {@code supersededBy}, for {@code supersededReason}); {@code sha256} is the content hash checked on download.
     */
    public record DocumentView(
            Long id,
            String type,
            String fileName,
            String contentType,
            long size,
            String uploadedBy,
            String uploadedAt,
            String status,
            String sha256,
            Long supersededBy,
            String supersededReason
    ) {
    }

    public record CheckView(String name, String result) {
    }

    public record PayeeView(
            String name,
            String relationship,
            String mobile,
            String payoutMethod,
            String accountRef,
            String source,
            boolean verified,
            String verifiedBy
    ) {
    }

    /** One workflow transition, oldest first. */
    public record HistoryView(
            String action,
            String previousState,
            String newState,
            String details,
            String performedBy,
            String performedAt,
            // NS-DAT-002: the day the new state applied, which can differ from when it was recorded.
            String effectiveDate
    ) {
    }

    public record ProductView(
            String name,
            String icon,
            String color,
            String description,
            String cover,
            String premium,
            int count,
            List<TierView> tiers,
            String image,
            String imageFocus,
            String need,
            String needLabel,
            String needIcon,
            String highlight,
            String summary,
            String fits,
            List<String> benefits,
            String coverUnit,
            String productType
    ) {
    }

    /** Landing page card: customer-facing content only, no counts or staff fields. */
    public record PublicProductView(
            String name,
            String icon,
            String highlight,
            String summary,
            String tagline,
            List<String> benefits,
            String image,
            String imageFocus,
            String cover,
            String premium,
            String coverUnit,
            Integer featuredOrder,
            List<TierView> tiers,
            String productType
    ) {
    }

    public record TierView(
            String name,
            int cover,
            int premium,
            String frequency,
            boolean popular,
            String benefits,
            // True when the premium is a "from" price read off the tier's rate table, which prices each life by age.
            boolean priceIsFrom,
            String rateTable,
            // Who the tier may cover (its life assured rules), offered on the "add a life" screen.
            List<RateTableViews.LifeRule> lives
    ) {
    }

    public record SchemeView(
            String id,
            String name,
            String sector,
            String initials,
            int members,
            int premium,
            String renewal,
            String color
    ) {
    }

    public record ConfigView(
            String id,
            String name,
            String code,
            String domain,
            String currency,
            String description,
            String effective,
            int minAge,
            int maxAge,
            int waiting,
            int cover,
            int premium,
            String frequency,
            int grace,
            String underwriting,
            List<String> benefits,
            List<String> channels,
            String wording,
            boolean approvals,
            String status,
            int version
    ) {
    }
}
