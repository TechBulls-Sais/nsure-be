package com.nsure.portfolio;

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
            // KYC and EDD review (demo server admin_review_customer_status); reasons are empty unless rejected.
            boolean pep,
            String eddStatus,
            String kycRejectionReason,
            String eddRejectionReason
    ) {
    }

    public record CoverView(
            String id,
            String name,
            String product,
            int amount,
            int premium,
            String status,
            String date,
            // Policy workflow (PolicyWorkflow); empty on applications and receipts.
            String coverStartDate,
            String nextPremiumDue,
            String gracePeriodEnd,
            String paymentDueBy,
            String lapseDate,
            String paymentMethod,
            // Active, past its due date and still within the grace period.
            boolean inGrace,
            String statusChangedBy,
            String statusNote,
            List<BeneficiaryView> beneficiaries
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

    public record ClaimView(
            String id,
            String name,
            String product,
            int amount,
            int premium,
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
            // Workflow actions legal from the current state (assess, approve, reject, pay), before capabilities.
            List<String> actions
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
            int count
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
