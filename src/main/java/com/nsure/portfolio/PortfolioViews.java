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
            String eddRejectionReason
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
            int amount,
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
            List<DocumentView> documents
    ) {
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
            int amount,
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
            Integer payoutAmount,
            String payoutReference,
            String paidAt,
            // Workflow actions legal from the current state (assess, approve, reject, pay), before permissions.
            List<String> actions,
            List<DocumentView> documents
    ) {
    }

    /** One evidence file on a claim; download it from .../claims/{ref}/documents/{id}. */
    public record DocumentView(
            Long id,
            String type,
            String fileName,
            String contentType,
            long size,
            String uploadedBy,
            String uploadedAt
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
            String performedAt
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
